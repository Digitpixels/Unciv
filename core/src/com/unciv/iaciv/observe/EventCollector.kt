package com.unciv.iaciv.observe

import com.unciv.iaciv.diplomacy.DiplomacyContext
import com.unciv.iaciv.diplomacy.DiplomacyIds
import com.unciv.iaciv.diplomacy.PendingHold
import com.unciv.iaciv.dto.OutgoingOfferDto
import com.unciv.iaciv.dto.PendingDto
import com.unciv.iaciv.dto.PopupAlertDto
import com.unciv.iaciv.dto.TradeOfferDto
import com.unciv.iaciv.dto.TradeRequestDto
import com.unciv.logic.civilization.AlertType
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.PopupAlert
import com.unciv.logic.trade.TradeEvaluation
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * What a civ remembers between two turn-starts: identifiers and names only, never game objects
 * (in UI mode every turn runs on a clone of GameInfo, see architecture.md §4).
 */
data class CivSnapshot(
    val turn: Int,
    val cities: Map<String, String>,
    val eraNumber: Int,
    val eraName: String,
    val techs: Set<String>,
    val policies: Set<String>,
    val atWarWith: Set<String>,
    val knownCivs: Set<String>,
    val goldenAge: Boolean,
) {
    companion object {
        fun of(civ: Civilization) = CivSnapshot(
            turn = civ.gameInfo.turns,
            cities = civ.cities.associate { it.id to it.name },
            eraNumber = civ.getEraNumber(),
            eraName = civ.getEra().name,
            techs = civ.tech.techsResearched.toSet(),
            policies = civ.policies.getAdoptedPolicies().toSet(),
            atWarWith = civ.getKnownCivs().filter { civ.isAtWarWith(it) && !it.isBarbarian }.map { it.civID }.toSet(),
            knownCivs = civ.getKnownCivs().filter { !it.isSpectator() && !it.isBarbarian }.map { it.civID }.toSet(),
            goldenAge = civ.goldenAges.isGoldenAge(),
        )
    }
}

object EventCollector {

    /**
     * Offers and alerts waiting for an answer from [civ] (read BEFORE NextTurnAutomation answers and clears them),
     * and our own offers waiting at the other civs (contract M3 §3). Ages come from [ctx]'s hold (first turn-start of
     * [civ] that showed the item) and, for our offers, from the watcher (first time seen at the target).
     */
    fun pending(civ: Civilization, ctx: DiplomacyContext = DiplomacyContext()): PendingDto {
        val turn = civ.gameInfo.turns
        val hold = ctx.hold
        val seenRequests = HashSet<String>()
        val trades = civ.tradeRequests.mapNotNull { request ->
            val from = civ.gameInfo.getCivilization(request.requestingCiv)
            val theyGive = DiplomacyIds.offers(request.trade.theirOffers)
            val weGive = DiplomacyIds.offers(request.trade.ourOffers)
            val id = DiplomacyIds.tradeRequestId(request.requestingCiv, theyGive, weGive)
            if (!seenRequests.add(id)) return@mapNotNull null
            TradeRequestDto(
                requestId = id,
                from = request.requestingCiv,
                theyGive = theyGive,
                weGive = weGive,
                isPeaceTreaty = request.trade.isPeaceTreaty(),
                valid = civ.getDiplomacyManager(from) != null && TradeEvaluation().isTradeValid(request.trade, civ, from),
                ageTurns = hold?.age(civ.civID, id, turn) ?: 0,
                expiresIn = (ctx.pendingHoldTurns - (hold?.age(civ.civID, id, turn) ?: 0)).coerceAtLeast(0),
                counterTo = ctx.watch?.counterTo(id),
            )
        }
        val seenAlerts = HashSet<String>()
        val alerts = civ.popupAlerts.mapNotNull { alert ->
            val id = DiplomacyIds.alertId(alert)
            if (!seenAlerts.add(id)) return@mapNotNull null
            val actionable = DiplomacyIds.isActionable(alert.type)
            val age = hold?.age(civ.civID, id, turn) ?: 0
            PopupAlertDto(
                alertId = id,
                type = alert.type.name,
                value = alert.value,
                ageTurns = age,
                expiresIn = if (actionable) (ctx.pendingHoldTurns - age).coerceAtLeast(0) else null,
                actionable = actionable,
                demand = DiplomacyIds.demandOf(alert.type)?.name,
            )
        }
        return PendingDto(trades, alerts, outgoing(civ, ctx))
    }

    /** Our offers still waiting at the other civs: trade and peace requests, friendship offers, demands */
    private fun outgoing(civ: Civilization, ctx: DiplomacyContext): List<OutgoingOfferDto> {
        val turn = civ.gameInfo.turns
        val result = ArrayList<OutgoingOfferDto>()
        for (other in civ.gameInfo.civilizations) {
            if (other == civ || !DiplomacyIds.isPlayerCiv(other) || other.isDefeated()) continue
            for ((id, request) in ctx.requestsOf(other)) {
                if (request.requestingCiv != civ.civID) continue
                val kind = if (request.trade.isPeaceTreaty()) "peace" else "trade"
                result += OutgoingOfferDto(id, other.civID, kind, ctx.watch?.ageOf(other.civID, id, turn) ?: 0)
            }
            for ((type, value) in ctx.alertsOf(other).distinct()) {
                if (value != civ.civID) continue
                val kind = when {
                    type == AlertType.DeclarationOfFriendship -> "friendship"
                    DiplomacyIds.demandOf(type) != null -> "demand"
                    else -> continue
                }
                val id = DiplomacyIds.alertId(type, value)
                result += OutgoingOfferDto(id, other.civID, kind, ctx.watch?.ageOf(other.civID, id, turn) ?: 0)
            }
        }
        return result
    }

    /** See [DiplomacyIds.tradeRequestId] */
    fun tradeRequestId(from: String, theyGive: List<TradeOfferDto>, weGive: List<TradeOfferDto>): String =
        DiplomacyIds.tradeRequestId(from, theyGive, weGive)

    /** Alerts already covered by the snapshot diff ([eventsSince]): not repeated as events */
    private val alertsCoveredByDiff = setOf(AlertType.FirstContact, AlertType.TechResearched, AlertType.GoldenAge)

    /**
     * Events derived from the popup alerts addressed to [civ] (they are consumed by the native AI right after H1),
     * skipping the first [from] alerts (used for the alerts created while the orders are applied). An actionable alert
     * kept by the bridge's hold comes back every turn: it is reported only the first turn [civ] sees it ([hold]).
     */
    fun eventsFromPopupAlerts(civ: Civilization, from: Int = 0, hold: PendingHold? = null): List<JsonObject> =
        eventsFromAlerts(civ, civ.popupAlerts.drop(from), hold)

    /** Same as [eventsFromPopupAlerts] for the alerts of [civ] not in [before] (compared by identity) */
    fun eventsFromNewAlerts(civ: Civilization, before: List<PopupAlert>, hold: PendingHold? = null): List<JsonObject> =
        eventsFromAlerts(civ, civ.popupAlerts.filter { alert -> before.none { it === alert } }, hold)

    private fun eventsFromAlerts(civ: Civilization, alerts: List<PopupAlert>, hold: PendingHold?): List<JsonObject> {
        val turn = civ.gameInfo.turns
        return alerts.filter { it.type !in alertsCoveredByDiff }
            .filter { hold == null || !DiplomacyIds.isActionable(it.type) || hold.age(civ.civID, DiplomacyIds.alertId(it), turn) == 0 }
            .distinctBy { DiplomacyIds.alertId(it) }
            .map { alert ->
            val type = when (alert.type) {
                AlertType.WarDeclaration -> "war_declared_on_us"
                AlertType.DeclarationOfFriendship -> "friendship_offered"
                AlertType.Denounced -> "denounced_by"
                AlertType.CityConquered -> "city_conquered"
                AlertType.WonderBuilt -> "wonder_built"
                AlertType.Defeated -> "civ_defeated"
                else -> "alert_" + alert.type.name
            }
            buildJsonObject {
                put("turn", turn)
                put("type", type)
                put("alert_type", alert.type.name)
                put("value", alert.value)
            }
        }
    }

    /** Diff between the last successfully sent snapshot and now (contract §4.1 `events_since_last`) */
    fun eventsSince(previous: CivSnapshot?, current: CivSnapshot, civ: Civilization): List<JsonObject> {
        if (previous == null) return emptyList()
        val turn = current.turn
        val events = ArrayList<JsonObject>()
        fun event(type: String, vararg fields: Pair<String, String?>) {
            events += buildJsonObject {
                put("turn", turn)
                put("type", type)
                for ((key, value) in fields) put(key, value)
            }
        }

        for ((id, name) in current.cities - previous.cities.keys) {
            val city = civ.cities.firstOrNull { it.id == id }
            val founder = city?.foundingCivObject?.civID
            if (founder == null || founder == civ.civID) event("city_founded", "city_id" to id, "city_name" to name)
            else event("city_taken", "city_id" to id, "city_name" to name, "from" to founder)
        }
        for ((id, name) in previous.cities - current.cities.keys) {
            val newOwner = civ.gameInfo.civilizations.firstOrNull { other -> other.cities.any { it.id == id } }
            if (newOwner == null) event("city_destroyed", "city_id" to id, "city_name" to name)
            else event("city_lost", "city_id" to id, "city_name" to name, "by" to newOwner.civID)
        }
        if (current.eraNumber > previous.eraNumber) event("era_entered", "era" to current.eraName)
        for (tech in (current.techs - previous.techs).sorted()) event("tech_researched", "tech" to tech)
        for (policy in (current.policies - previous.policies).sorted()) event("policy_adopted", "policy" to policy)
        for (other in (current.atWarWith - previous.atWarWith).sorted()) event("war_started", "with" to other)
        for (other in (previous.atWarWith - current.atWarWith).sorted()) event("peace_signed", "with" to other)
        for (other in (current.knownCivs - previous.knownCivs).sorted()) event("first_contact", "with" to other)
        if (current.goldenAge && !previous.goldenAge) event("golden_age_started")
        if (!current.goldenAge && previous.goldenAge) event("golden_age_ended")
        return events
    }
}
