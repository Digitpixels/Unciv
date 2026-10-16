package com.unciv.iaciv.orders

import com.unciv.Constants
import com.unciv.iaciv.diplomacy.DiplomacyIds
import com.unciv.iaciv.diplomacy.DiplomacyWatch
import com.unciv.iaciv.diplomacy.PendingHold
import com.unciv.iaciv.dto.OrderResultDto
import com.unciv.iaciv.observe.LegalOptions
import com.unciv.logic.civilization.AlertType
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.NotificationCategory
import com.unciv.logic.civilization.NotificationIcon
import com.unciv.logic.civilization.PopupAlert
import com.unciv.logic.civilization.diplomacy.Demand
import com.unciv.logic.civilization.diplomacy.DiplomacyFlags
import com.unciv.logic.civilization.diplomacy.DiplomaticStatus
import com.unciv.logic.trade.TradeEvaluation
import com.unciv.logic.trade.TradeLogic
import com.unciv.logic.trade.TradeOffer
import com.unciv.logic.trade.TradeOfferType
import com.unciv.logic.trade.TradeOffersList
import com.unciv.logic.trade.TradeRequest
import com.unciv.models.ruleset.unique.UniqueType
import kotlinx.serialization.json.JsonObject

/**
 * The 9 diplomatic orders of contract M3 §4, applied through Unciv's managers exactly as `DiplomacyAutomation`,
 * `TradeAutomation` and the diplomacy screen do (no view method exists for them). Everything is validated before the
 * game is touched (most of these functions check nothing); a rejection throws [OrderReject], caught per order by
 * [OrdersApplier]. The bridge's watcher is told what each answer means, so that the global events use the same keys.
 */
internal object DiplomacyOrders {

    val TYPES = setOf(
        "declare_war", "propose_peace", "offer_friendship", "respond_friendship", "respond_trade", "propose_trade",
        "denounce", "respond_demand", "make_demand",
    )

    private val FRIENDSHIP_DECISIONS = setOf("accept", "refuse", "native")
    private val TRADE_DECISIONS = setOf("accept", "refuse", "counter", "native")
    private val DEMAND_DECISIONS = setOf("agree", "refuse", "native")

    fun apply(context: OrdersApplier.Context, id: String, type: String, order: JsonObject): OrderResultDto = when (type) {
        "declare_war" -> declareWar(context, id, order)
        "propose_peace" -> proposePeace(context, id, order)
        "offer_friendship" -> offerFriendship(context, id, order)
        "respond_friendship" -> respondFriendship(context, id, order)
        "respond_trade" -> respondTrade(context, id, order)
        "propose_trade" -> proposeTrade(context, id, order)
        "denounce" -> denounce(context, id, order)
        "respond_demand" -> respondDemand(context, id, order)
        "make_demand" -> makeDemand(context, id, order)
        else -> throw OrderReject("unknown_order_type", type)
    }

    private fun applied(id: String, detail: String, requestId: String? = null) = OrderResultDto(id, "applied", null, detail, requestId = requestId)

    private val OrdersApplier.Context.watch: DiplomacyWatch? get() = diplomacy.watch
    private val OrdersApplier.Context.hold: PendingHold? get() = diplomacy.hold

    /** A player civ of this game (no spectator, no barbarians), else `unknown_civ` */
    private fun playerCiv(civ: Civilization, name: String): Civilization {
        val other = civ.gameInfo.getCivilizationOrNull(name)
        if (other == null || !DiplomacyIds.isPlayerCiv(other)) throw OrderReject("unknown_civ", name)
        return other
    }

    /** [playerCiv], not ourselves, already met: else `unknown_civ` */
    private fun metCiv(civ: Civilization, name: String): Civilization {
        val other = playerCiv(civ, name)
        if (other == civ || civ.getDiplomacyManager(other) == null) throw OrderReject("unknown_civ", if (other == civ) "$name : nous-mêmes" else "$name : jamais rencontrée")
        return other
    }

    private fun locked(civ: Civilization) = civ.gameInfo.ruleset.modOptions.hasUnique(UniqueType.DiplomaticRelationshipsCannotChange)

    private fun checkDecision(decision: String, allowed: Set<String>) {
        if (decision !in allowed) throw OrderReject("invalid_decision", "$decision (attendu : ${allowed.joinToString(" / ")})")
    }

    // region war and peace

    private fun declareWar(context: OrdersApplier.Context, id: String, order: JsonObject): OrderResultDto {
        val civ = context.civ
        val name = order.reqStr("target")
        if (name == civ.civID) throw OrderReject("self_target", name)
        val target = playerCiv(civ, name)
        val dm = civ.getDiplomacyManager(target) ?: throw OrderReject("not_met", name)
        if (locked(civ)) throw OrderReject("relationships_locked")
        if (target.isDefeated()) throw OrderReject("target_defeated", name)
        if (dm.diplomaticStatus == DiplomaticStatus.War) throw OrderReject("already_at_war", name)
        val treaty = dm.turnsToPeaceTreaty()
        if (treaty > 0) throw OrderReject("peace_treaty_active", "traité de paix avec $name encore $treaty tour(s)")
        if (!dm.canDeclareWar()) throw OrderReject("target_defeated", name) // last case of canDeclareWar
        val atWarBefore = civ.getCivsAtWarWith().map { it.civID }.toSet()
        context.watch?.noteWarDeclared(civ.civID, target.civID)
        dm.declareWar()
        context.record(AppliedOrder.DeclareWar(id, target.civID))
        val also = civ.getCivsAtWarWith().map { it.civID }.filter { it !in atWarBefore && it != target.civID }.sorted()
        return applied(id, "guerre déclarée à $name" + if (also.isEmpty()) "" else " ; aussi en guerre avec ${also.joinToString(", ")} (pactes, cités-États)")
    }

    private fun proposePeace(context: OrdersApplier.Context, id: String, order: JsonObject): OrderResultDto {
        val civ = context.civ
        val target = metCiv(civ, order.reqStr("target"))
        val dm = civ.getDiplomacyManager(target)!!
        if (locked(civ)) throw OrderReject("relationships_locked")
        if (dm.diplomaticStatus != DiplomaticStatus.War) throw OrderReject("not_at_war", target.civID)
        val goldOffered = order.optInt("gold_offered") ?: 0
        val goldRequested = order.optInt("gold_requested") ?: 0
        if (goldOffered < 0) throw OrderReject("invalid_field", "gold_offered")
        if (goldRequested < 0) throw OrderReject("invalid_field", "gold_requested")
        val wait = context.diplomacy.peacePossibleIn(civ, target) ?: 0
        if (wait > 0) throw OrderReject("war_too_recent", "paix possible avec ${target.civID} dans $wait tour(s) (règle du bouton de paix du jeu)")
        val speed = civ.gameInfo.speed

        if (target.isCityState) { // the diplomacy screen applies peace with a city-state at once (CityStateDiplomacyTable)
            val ally = target.allyCiv
            if (context.diplomacy.uiPeaceRule && ally != null && ally != civ && civ.isAtWarWith(ally))
                throw OrderReject("trade_invalid", "en guerre avec ${ally.civID}, allié de ${target.civID} (règle du jeu)")
            val logic = TradeLogic(civ, target)
            logic.currentTrade.ourOffers.add(TradeOffer(Constants.peaceTreaty, TradeOfferType.Treaty, speed = speed))
            logic.currentTrade.theirOffers.add(TradeOffer(Constants.peaceTreaty, TradeOfferType.Treaty, speed = speed))
            logic.acceptTrade()
            context.record(AppliedOrder.ProposePeace(id, target.civID, null))
            val gold = if (goldOffered > 0 || goldRequested > 0) " (or ignoré : pas d'échange avec une cité-État)" else ""
            return applied(id, "paix immédiate avec la cité-État ${target.civID}$gold")
        }

        if (context.diplomacy.hasPendingOfferFrom(target, civ.civID))
            throw OrderReject("offer_already_pending", "une offre à ${target.civID} attend déjà sa réponse")
        if (goldOffered > 0 && goldOffered * 0.9f >= civ.gold) throw OrderReject("not_enough_gold", "$goldOffered or offert, trésor ${civ.gold}")
        val logic = TradeLogic(civ, target)
        logic.currentTrade.ourOffers.add(TradeOffer(Constants.peaceTreaty, TradeOfferType.Treaty, speed = speed))
        logic.currentTrade.theirOffers.add(TradeOffer(Constants.peaceTreaty, TradeOfferType.Treaty, speed = speed))
        if (goldOffered > 0) logic.currentTrade.ourOffers.add(TradeOffer(Constants.flatGold, TradeOfferType.Gold, goldOffered, speed))
        if (goldRequested > 0) logic.currentTrade.theirOffers.add(TradeOffer(Constants.flatGold, TradeOfferType.Gold, goldRequested, speed))
        if (!TradeEvaluation().isTradeValid(logic.currentTrade, civ, target))
            throw OrderReject("trade_invalid", if (goldRequested > 0) "${target.civID} n'a pas $goldRequested or" else "offre refusée par les règles du jeu")
        val requestId = send(civ, target, logic)
        context.record(AppliedOrder.ProposePeace(id, target.civID, requestId))
        return applied(id, "paix proposée à ${target.civID}" + goldText(goldOffered, goldRequested) + " ; réponse à son tour", requestId)
    }

    private fun goldText(offered: Int, requested: Int) = buildString {
        if (offered > 0) append(", nous donnons $offered or")
        if (requested > 0) append(", ils donnent $requested or")
    }

    /** Puts our offer in [target]'s requests (as TradeView.tryProposeStagedTrade) and returns its id as the target sees it */
    private fun send(civ: Civilization, target: Civilization, logic: TradeLogic): String {
        val request = TradeRequest(civ.civID, logic.currentTrade.reverse())
        target.tradeRequests.add(request)
        civ.cache.updateCivResources()
        return DiplomacyIds.tradeRequestId(request)
    }

    // endregion
    // region friendship and denunciation

    private fun offerFriendship(context: OrdersApplier.Context, id: String, order: JsonObject): OrderResultDto {
        val civ = context.civ
        val target = metCiv(civ, order.reqStr("target"))
        if (!target.isMajorCiv()) throw OrderReject("not_major_civ", target.civID)
        val dm = civ.getDiplomacyManager(target)!!
        if (!civ.diplomacyFunctions.canSignDeclarationOfFriendshipWith(target)) throw OrderReject("cannot_sign_friendship", when {
            civ.isAtWarWith(target) -> "en guerre avec ${target.civID}"
            dm.hasFlag(DiplomacyFlags.Denunciation) -> "nous avons dénoncé ${target.civID}"
            else -> "déjà amis avec ${target.civID}"
        })
        if (context.diplomacy.hasAlertFrom(target, AlertType.DeclarationOfFriendship, civ.civID))
            throw OrderReject("friendship_already_offered", target.civID)
        if (dm.hasFlag(DiplomacyFlags.DeclinedDeclarationOfFriendship))
            throw OrderReject("friendship_recently_declined", "refusée par ${target.civID}, nouvelle offre possible dans ${dm.getFlag(DiplomacyFlags.DeclinedDeclarationOfFriendship)} tour(s)")
        target.popupAlerts.add(PopupAlert(AlertType.DeclarationOfFriendship, civ.civID))
        context.record(AppliedOrder.OfferFriendship(id, target.civID))
        val warning = if (target.getDiplomacyManager(civ)?.hasFlag(DiplomacyFlags.Denunciation) == true)
            " ; attention : ${target.civID} nous a dénoncés, elle ne pourra pas accepter" else ""
        return applied(id, "amitié offerte à ${target.civID}, réponse à son tour$warning")
    }

    private fun respondFriendship(context: OrdersApplier.Context, id: String, order: JsonObject): OrderResultDto {
        val civ = context.civ
        val alertId = order.reqStr("alert_id")
        val decision = order.reqStr("decision")
        checkDecision(decision, FRIENDSHIP_DECISIONS)
        val alert = civ.popupAlerts.firstOrNull { it.type == AlertType.DeclarationOfFriendship && DiplomacyIds.alertId(it) == alertId }
            ?: throw OrderReject("unknown_alert", alertId)
        if (decision == "native") {
            context.nativeIds += alertId
            return applied(id, "offre d'amitié de ${alert.value} laissée à l'IA native (réponse juste après)")
        }
        val proposer = civ.gameInfo.getCivilizationOrNull(alert.value)
        val dm = proposer?.let { civ.getDiplomacyManager(it) }
        if (proposer == null || dm == null || proposer.isDefeated() || civ.isAtWarWith(proposer)) {
            removeAlert(context, alertId)
            throw OrderReject("alert_obsolete", if (proposer != null && civ.isAtWarWith(proposer)) "en guerre avec ${alert.value}" else "${alert.value} n'existe plus")
        }
        if (decision == "accept") {
            if (!civ.diplomacyFunctions.canSignDeclarationOfFriendshipWith(proposer))
                throw OrderReject("cannot_sign_friendship", if (dm.hasFlag(DiplomacyFlags.Denunciation)) "nous avons dénoncé ${proposer.civID}" else "déjà amis")
            dm.signDeclarationOfFriendship()
            proposer.addNotification("We have signed a Declaration of Friendship with [${civ.civName}]!", NotificationCategory.Diplomacy, NotificationIcon.Diplomacy, civ.civName)
        } else {
            dm.otherCivDiplomacy().setFlag(DiplomacyFlags.DeclinedDeclarationOfFriendship, PendingHold.FRIENDSHIP_REFUSAL_TURNS)
            proposer.addNotification("[${civ.civName}] has denied our Declaration of Friendship!", NotificationCategory.Diplomacy, NotificationIcon.Diplomacy, civ.civName)
        }
        removeAlert(context, alertId)
        context.record(AppliedOrder.RespondFriendship(id, proposer.civID, decision == "accept"))
        return applied(id, if (decision == "accept") "amitié signée avec ${proposer.civID} (30 tours)" else "amitié de ${proposer.civID} refusée")
    }

    private fun removeAlert(context: OrdersApplier.Context, alertId: String) {
        context.civ.popupAlerts.removeAll { DiplomacyIds.alertId(it) == alertId }
        context.hold?.forget(context.civ.civID, alertId)
    }

    private fun denounce(context: OrdersApplier.Context, id: String, order: JsonObject): OrderResultDto {
        val civ = context.civ
        val target = metCiv(civ, order.reqStr("target"))
        if (!target.isMajorCiv()) throw OrderReject("not_major_civ", target.civID)
        val dm = civ.getDiplomacyManager(target)!!
        if (dm.diplomaticStatus == DiplomaticStatus.War) throw OrderReject("at_war", target.civID)
        if (dm.hasFlag(DiplomacyFlags.Denunciation)) throw OrderReject("already_denounced", "encore ${dm.getFlag(DiplomacyFlags.Denunciation)} tour(s)")
        if (dm.hasFlag(DiplomacyFlags.DeclarationOfFriendship)) throw OrderReject("friendship_active", target.civID)
        dm.denounce()
        context.record(AppliedOrder.Denounce(id, target.civID))
        return applied(id, "${target.civID} dénoncée (30 tours, ambassades fermées)")
    }

    // endregion
    // region trades

    private fun respondTrade(context: OrdersApplier.Context, id: String, order: JsonObject): OrderResultDto {
        val civ = context.civ
        val requestId = order.reqStr("request_id")
        val decision = order.reqStr("decision")
        checkDecision(decision, TRADE_DECISIONS)
        val request = civ.tradeRequests.firstOrNull { DiplomacyIds.tradeRequestId(it) == requestId } ?: throw OrderReject("unknown_request", requestId)
        if (decision == "native") {
            context.nativeIds += requestId
            return applied(id, "offre de ${request.requestingCiv} laissée à l'IA native (réponse juste après)")
        }
        val other = civ.gameInfo.getCivilizationOrNull(request.requestingCiv)
        fun drop() {
            civ.tradeRequests.removeAll { DiplomacyIds.tradeRequestId(it) == requestId }
            context.hold?.forget(civ.civID, requestId)
            context.watch?.noteTradeDecision(requestId, DiplomacyWatch.TRADE_EXPIRED, DiplomacyWatch.BY_EXPIRY)
        }
        if (other == null || other.isDefeated() || civ.getDiplomacyManager(other) == null) {
            drop()
            throw OrderReject("requester_defeated", request.requestingCiv)
        }
        // RD R6: the request may have become invalid since the observation (city lost, gold spent…): acceptTrade would throw
        if (!TradeEvaluation().isTradeValid(request.trade, civ, other)) {
            drop()
            throw OrderReject("trade_invalid", "l'offre de ${other.civID} n'est plus valide (retirée)")
        }
        val isPeace = request.trade.isPeaceTreaty()
        val fingerprint = DiplomacyIds.tradeFingerprint(request.trade)
        return when (decision) {
            "accept" -> {
                civ.tradeRequests.removeAll { DiplomacyIds.tradeRequestId(it) == requestId } // before acceptTrade (TradeAutomation)
                val logic = TradeLogic(civ, other)
                logic.currentTrade.set(request.trade)
                context.watch?.noteTradeDecision(requestId, DiplomacyWatch.TRADE_ACCEPTED, DiplomacyWatch.BY_LLM)
                logic.acceptTrade()
                other.addNotification("[${civ.civName}] has accepted your trade request", NotificationCategory.Trade, NotificationIcon.Trade, civ.civName)
                context.hold?.forget(civ.civID, requestId)
                context.record(AppliedOrder.RespondTrade(id, requestId, other.civID, decision, isPeace, fingerprint, null))
                applied(id, if (isPeace) "paix acceptée avec ${other.civID}" else "échange de ${other.civID} accepté")
            }
            "refuse" -> {
                civ.tradeRequests.removeAll { DiplomacyIds.tradeRequestId(it) == requestId }
                request.decline(civ)
                context.watch?.noteTradeDecision(requestId, DiplomacyWatch.TRADE_REFUSED, DiplomacyWatch.BY_LLM)
                other.addNotification("[${civ.civName}] has denied your trade request", NotificationCategory.Trade, civ.civName, NotificationIcon.Trade)
                context.hold?.forget(civ.civID, requestId)
                context.record(AppliedOrder.RespondTrade(id, requestId, other.civID, decision, isPeace, fingerprint, null))
                applied(id, "offre de ${other.civID} refusée")
            }
            else -> { // counter: built and checked before the original is withdrawn (an invalid counter leaves it pending)
                val counter = order.optObject("counter") ?: throw OrderReject("missing_field", "counter")
                val logic = buildTrade(civ, other, counter.optObjectList("we_give"), counter.optObjectList("they_give"), "counter_invalid")
                if (logic.currentTrade.ourOffers.isEmpty() && logic.currentTrade.theirOffers.isEmpty()) throw OrderReject("counter_invalid", "contre-proposition vide")
                if (!TradeEvaluation().isTradeValid(logic.currentTrade, civ, other)) throw OrderReject("counter_invalid", "refusée par les règles du jeu")
                civ.tradeRequests.removeAll { DiplomacyIds.tradeRequestId(it) == requestId }
                context.hold?.forget(civ.civID, requestId)
                context.watch?.noteTradeDecision(requestId, DiplomacyWatch.TRADE_COUNTERED, DiplomacyWatch.BY_LLM)
                val counterId = send(civ, other, logic)
                context.watch?.noteCounter(counterId, requestId)
                other.addNotification("[${civ.civName}] has made a counteroffer to your trade request", NotificationCategory.Trade, NotificationIcon.Trade, civ.civName)
                context.record(AppliedOrder.RespondTrade(id, requestId, other.civID, decision, isPeace, fingerprint, counterId))
                applied(id, "contre-proposition envoyée à ${other.civID}", counterId)
            }
        }
    }

    private fun proposeTrade(context: OrdersApplier.Context, id: String, order: JsonObject): OrderResultDto {
        val civ = context.civ
        val target = metCiv(civ, order.reqStr("target"))
        if (!target.isMajorCiv() || !civ.isMajorCiv()) throw OrderReject("not_major_civ", target.civID) // TradeLogic: no trade with city-states
        val weGive = order.optObjectList("we_give")
        val theyGive = order.optObjectList("they_give")
        if (context.diplomacy.hasPendingOfferFrom(target, civ.civID))
            throw OrderReject("offer_already_pending", "une offre à ${target.civID} attend déjà sa réponse")
        if (weGive.isEmpty() && theyGive.isEmpty()) throw OrderReject("empty_trade")
        val logic = buildTrade(civ, target, weGive, theyGive, "offer_not_available")
        if (!TradeEvaluation().isTradeValid(logic.currentTrade, civ, target)) throw OrderReject("trade_invalid", "refusée par les règles du jeu")
        val requestId = send(civ, target, logic)
        context.record(AppliedOrder.ProposeTrade(id, target.civID, requestId))
        return applied(id, "échange proposé à ${target.civID} ; réponse à son tour", requestId)
    }

    /**
     * A trade made only of offers the trade screen would show ([TradeLogic.ourAvailableOffers] /
     * [TradeLogic.theirAvailableOffers]), copied to keep their duration. Items: `{type, name, amount}`; the amount
     * (default 1) counts for gold, gold per turn and resources, it is ignored otherwise. Treaties (peace, research agreement, defensive
     * pact) are shared by both sides: one listed on one side only is added to the other. Bad item -> [reason].
     */
    private fun buildTrade(civ: Civilization, other: Civilization, weGive: List<JsonObject>, theyGive: List<JsonObject>, reason: String): TradeLogic {
        val logic = TradeLogic(civ, other)
        fun pick(item: JsonObject, available: TradeOffersList, side: String, chosen: TradeOffersList): TradeOffer {
            val type = item.reqStr("type")
            val name = item.reqStr("name")
            val amount = item.optInt("amount")
            val offer = available.firstOrNull { it.type.name == type && it.name == name && it.isTradable() }
                ?: throw OrderReject(reason, "$side : $type « $name » non disponible")
            if (chosen.any { it.type == offer.type && it.name == offer.name }) throw OrderReject(reason, "$side : « $name » en double")
            val quantity = if (offer.type.numberType == TradeOfferType.TradeTypeNumberType.None) offer.amount
                else amount ?: 1 // same default as the orchestrator's Item model
            if (quantity < 1 || quantity > offer.amount) throw OrderReject(reason, "$side : $name × $quantity, ${offer.amount} disponible(s)")
            return offer.copy(amount = quantity)
        }
        for (item in weGive) logic.currentTrade.ourOffers.add(pick(item, logic.ourAvailableOffers, "nous donnons", logic.currentTrade.ourOffers))
        for (item in theyGive) logic.currentTrade.theirOffers.add(pick(item, logic.theirAvailableOffers, "ils donnent", logic.currentTrade.theirOffers))
        fun mirror(from: List<TradeOffer>, to: TradeOffersList, available: TradeOffersList) {
            for (treaty in from.filter { it.type == TradeOfferType.Treaty }) {
                if (to.any { it.type == TradeOfferType.Treaty && it.name == treaty.name }) continue
                val match = available.firstOrNull { it.type == TradeOfferType.Treaty && it.name == treaty.name && it.isTradable() }
                    ?: throw OrderReject(reason, "traité « ${treaty.name} » impossible de l'autre côté")
                to.add(match.copy())
            }
        }
        mirror(logic.currentTrade.ourOffers.toList(), logic.currentTrade.theirOffers, logic.theirAvailableOffers)
        mirror(logic.currentTrade.theirOffers.toList(), logic.currentTrade.ourOffers, logic.ourAvailableOffers)
        return logic
    }

    // endregion
    // region demands

    private fun respondDemand(context: OrdersApplier.Context, id: String, order: JsonObject): OrderResultDto {
        val civ = context.civ
        val alertId = order.reqStr("alert_id")
        val decision = order.reqStr("decision")
        checkDecision(decision, DEMAND_DECISIONS)
        val alert = civ.popupAlerts.firstOrNull { DiplomacyIds.alertId(it) == alertId } ?: throw OrderReject("unknown_alert", alertId)
        val demand = DiplomacyIds.demandOf(alert.type) ?: throw OrderReject("not_a_demand", "${alert.type.name} n'est pas une exigence")
        if (decision == "native") {
            context.nativeIds += alertId
            return applied(id, "exigence de ${alert.value} laissée à l'IA native (réponse juste après)")
        }
        val demander = civ.gameInfo.getCivilizationOrNull(alert.value)
        val dm = demander?.let { civ.getDiplomacyManager(it) } // the manager of the civ that RECEIVED the demand
        if (demander == null || dm == null || demander.isDefeated()) {
            removeAlert(context, alertId)
            throw OrderReject("demander_defeated", alert.value)
        }
        if (decision == "agree") dm.agreeToDemand(demand) else dm.refuseDemand(demand)
        removeAlert(context, alertId)
        context.record(AppliedOrder.RespondDemand(id, demander.civID, demand.name, decision == "agree"))
        val warning = if (decision == "refuse" && demand == Demand.DoNotAttackUs) " (refus = déclaration de guerre, règle du jeu)" else ""
        return applied(id, "exigence ${demand.name} de ${demander.civID} " + (if (decision == "agree") "acceptée" else "refusée") + warning)
    }

    private fun makeDemand(context: OrdersApplier.Context, id: String, order: JsonObject): OrderResultDto {
        val civ = context.civ
        val target = metCiv(civ, order.reqStr("target"))
        val demandName = order.reqStr("demand")
        val demand = Demand.entries.firstOrNull { it.name == demandName } ?: throw OrderReject("unknown_demand", demandName)
        if (context.diplomacy.hasAlertFrom(target, demand.demandAlert, civ.civID)) throw OrderReject("demand_already_pending", "${target.civID} : $demandName")
        if (demandName !in LegalOptions.demandsAvailable(civ, target, context.diplomacy)) {
            val dm = civ.getDiplomacyManager(target)!!
            throw OrderReject("demand_not_available", when {
                !target.isMajorCiv() -> "${target.civID} n'est pas une civilisation majeure"
                dm.diplomaticStatus == DiplomaticStatus.War -> "en guerre avec ${target.civID}"
                dm.hasFlag(demand.agreedToDemand) -> "${target.civID} l'a déjà promis (encore ${dm.getFlag(demand.agreedToDemand)} tour(s))"
                else -> "$demandName indisponible dans cette partie"
            })
        }
        target.popupAlerts.add(PopupAlert(demand.demandAlert, civ.civID))
        context.record(AppliedOrder.MakeDemand(id, target.civID, demandName))
        return applied(id, "exigence $demandName adressée à ${target.civID}, réponse à son tour")
    }

    // endregion
}
