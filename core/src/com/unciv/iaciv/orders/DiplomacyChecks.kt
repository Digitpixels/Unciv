package com.unciv.iaciv.orders

import com.unciv.iaciv.diplomacy.DiplomacyContext
import com.unciv.iaciv.diplomacy.DiplomacyIds
import com.unciv.iaciv.orders.OrderChecker.COMPLETED
import com.unciv.iaciv.orders.OrderChecker.IN_PLACE
import com.unciv.iaciv.orders.OrderChecker.NOT_CHECKED
import com.unciv.iaciv.orders.OrderChecker.OVERRIDDEN
import com.unciv.logic.civilization.AlertType
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.diplomacy.Demand
import com.unciv.logic.civilization.diplomacy.DiplomacyFlags
import com.unciv.logic.civilization.diplomacy.DiplomaticStatus

/**
 * H4 check of the diplomatic orders (contract M3 §4, last column): is the effect still in place at the end of the
 * civ's turn? Offers count as pending when the target holds them, in its lists or in the bridge's copies.
 */
internal object DiplomacyChecks {

    /** null if [order] is not a diplomatic order */
    fun check(civ: Civilization, order: AppliedOrder, ctx: DiplomacyContext): OrderCheck? {
        fun result(status: String, detail: String, by: String? = null) = OrderCheck(order.orderId, order.orderType, status, by, detail)
        fun civOf(id: String) = civ.gameInfo.getCivilizationOrNull(id)
        fun atWar(other: Civilization) = civ.getDiplomacyManager(other)?.diplomaticStatus == DiplomaticStatus.War
        fun gone(id: String) = result(NOT_CHECKED, "$id a disparu de la partie")
        return when (order) {
            is AppliedOrder.DeclareWar -> {
                val target = civOf(order.target)
                when {
                    target == null || target.isDefeated() -> result(COMPLETED, "${order.target} vaincue")
                    atWar(target) -> result(IN_PLACE, "en guerre avec ${order.target}")
                    else -> result(OVERRIDDEN, "plus en guerre avec ${order.target}", "native_ai")
                }
            }
            is AppliedOrder.ProposePeace -> {
                val target = civOf(order.target) ?: return gone(order.target)
                when {
                    order.requestId == null -> if (!atWar(target)) result(IN_PLACE, "paix avec la cité-État ${order.target}")
                        else result(OVERRIDDEN, "de nouveau en guerre avec ${order.target}", "native_ai")
                    order.requestId in ctx.requestsOf(target) -> result(IN_PLACE, "offre de paix en attente chez ${order.target}")
                    !atWar(target) -> result(COMPLETED, "paix signée avec ${order.target}")
                    else -> result(OVERRIDDEN, "offre de paix retirée de chez ${order.target}", "native_ai")
                }
            }
            is AppliedOrder.OfferFriendship -> {
                val target = civOf(order.target) ?: return gone(order.target)
                when {
                    ctx.hasAlertFrom(target, AlertType.DeclarationOfFriendship, civ.civID) -> result(IN_PLACE, "offre d'amitié en attente chez ${order.target}")
                    civ.getDiplomacyManager(target)?.hasFlag(DiplomacyFlags.DeclarationOfFriendship) == true -> result(COMPLETED, "amitié signée avec ${order.target}")
                    else -> result(OVERRIDDEN, "offre d'amitié disparue de chez ${order.target}", "native_ai")
                }
            }
            is AppliedOrder.RespondFriendship -> {
                val proposer = civOf(order.proposer) ?: return gone(order.proposer)
                val ours = civ.getDiplomacyManager(proposer)
                val theirs = proposer.getDiplomacyManager(civ)
                if (order.accept) {
                    if (ours?.hasFlag(DiplomacyFlags.DeclarationOfFriendship) == true && theirs?.hasFlag(DiplomacyFlags.DeclarationOfFriendship) == true)
                        result(IN_PLACE, "amitié en place avec ${order.proposer}")
                    else result(OVERRIDDEN, "amitié avec ${order.proposer} rompue", "native_ai")
                } else {
                    if (theirs?.hasFlag(DiplomacyFlags.DeclinedDeclarationOfFriendship) == true) result(IN_PLACE, "refus noté chez ${order.proposer}")
                    else result(OVERRIDDEN, "drapeau de refus absent chez ${order.proposer}", "native_ai")
                }
            }
            is AppliedOrder.RespondTrade -> {
                val other = civOf(order.other) ?: return gone(order.other)
                when (order.decision) {
                    "accept" -> when {
                        order.isPeace -> if (!atWar(other)) result(IN_PLACE, "paix en place avec ${order.other}")
                            else result(OVERRIDDEN, "de nouveau en guerre avec ${order.other}", "native_ai")
                        civ.getDiplomacyManager(other)?.trades?.any { DiplomacyIds.tradeFingerprint(it) == order.fingerprint } == true ->
                            result(IN_PLACE, "échange en place avec ${order.other}")
                        else -> result(OVERRIDDEN, "échange avec ${order.other} absent des accords en cours", "native_ai")
                    }
                    "refuse" -> if (order.requestId !in ctx.requestsOf(civ)) result(IN_PLACE, "offre de ${order.other} retirée")
                        else result(OVERRIDDEN, "offre de ${order.other} de nouveau en attente", "native_ai")
                    else -> if (order.counterId != null && order.counterId in ctx.requestsOf(other)) result(IN_PLACE, "contre-proposition en attente chez ${order.other}")
                        else result(OVERRIDDEN, "contre-proposition disparue de chez ${order.other}", "native_ai")
                }
            }
            is AppliedOrder.ProposeTrade -> {
                val target = civOf(order.target) ?: return gone(order.target)
                if (order.requestId in ctx.requestsOf(target)) result(IN_PLACE, "offre en attente chez ${order.target}")
                else result(OVERRIDDEN, "offre disparue de chez ${order.target}", "native_ai")
            }
            is AppliedOrder.Denounce -> {
                val target = civOf(order.target) ?: return gone(order.target)
                if (civ.getDiplomacyManager(target)?.hasFlag(DiplomacyFlags.Denunciation) == true) result(IN_PLACE, "dénonciation de ${order.target} en place")
                else result(OVERRIDDEN, "dénonciation de ${order.target} levée", "native_ai")
            }
            is AppliedOrder.RespondDemand -> {
                val demander = civOf(order.demander) ?: return gone(order.demander)
                val demand = Demand.entries.firstOrNull { it.name == order.demand } ?: return gone(order.demand)
                val flag = if (order.agree) demand.agreedToDemand else demand.willIgnoreViolation
                if (demander.getDiplomacyManager(civ)?.hasFlag(flag) == true) result(IN_PLACE, "${flag.name} noté chez ${order.demander}")
                else result(OVERRIDDEN, "${flag.name} absent chez ${order.demander}", "native_ai")
            }
            is AppliedOrder.MakeDemand -> {
                val target = civOf(order.target) ?: return gone(order.target)
                val demand = Demand.entries.firstOrNull { it.name == order.demand } ?: return gone(order.demand)
                val dm = civ.getDiplomacyManager(target)
                when {
                    ctx.hasAlertFrom(target, demand.demandAlert, civ.civID) -> result(IN_PLACE, "exigence en attente chez ${order.target}")
                    dm?.hasFlag(demand.agreedToDemand) == true || dm?.hasFlag(demand.willIgnoreViolation) == true ->
                        result(COMPLETED, "${order.target} a déjà répondu")
                    else -> result(OVERRIDDEN, "exigence disparue de chez ${order.target}", "native_ai")
                }
            }
            else -> null
        }
    }
}
