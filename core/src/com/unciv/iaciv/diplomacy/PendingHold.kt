package com.unciv.iaciv.diplomacy

import com.unciv.logic.civilization.AlertType
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.NotificationCategory
import com.unciv.logic.civilization.NotificationIcon
import com.unciv.logic.civilization.PopupAlert
import com.unciv.logic.civilization.diplomacy.DiplomacyFlags
import com.unciv.logic.trade.TradeEvaluation
import com.unciv.logic.trade.TradeRequest
import kotlinx.serialization.json.JsonObject

/**
 * Contract M3 §2 — offers and actionable alerts received by an LLM civ. The native AI answers them right after H1
 * (`respondToPopupAlerts`, `respondToTradeRequests`, not guarded by H2), so at H1, once the orders are applied, the
 * bridge takes the unanswered ones out of the civ's lists and keeps a **data copy**; at H4 it puts them back (minus
 * the ones that became invalid). At the civ's next H1 the items at least `pending_hold_turns` old are refused
 * explicitly (as a human clicking "no"), then duplicates are removed. If H4 was missed, the next H1 restores the copies.
 * Only identifiers and detached copies are kept from one turn to the next (in UI mode each turn is a clone of GameInfo).
 */
class PendingHold {

    class HeldTrade(val id: String, val request: TradeRequest)
    class HeldAlert(val id: String, val type: AlertType, val value: String)

    private class Held(val gameId: String, val turn: Int, val trades: List<HeldTrade>, val alerts: List<HeldAlert>)

    /** What [hold] took out of the lists (for the log) and what [restore] dropped as invalid */
    data class Summary(val trades: List<String>, val alerts: List<String>) {
        fun isEmpty() = trades.isEmpty() && alerts.isEmpty()
        override fun toString() = (trades + alerts).joinToString(", ")
    }

    /** civId -> items held since H1 of the turn (absent once restored) */
    private val held = HashMap<String, Held>()
    /** "civId|id" -> turn of the civ's first H1 that showed the item (the age shown to the LLM counts from there) */
    private val firstSeen = HashMap<String, Int>()

    fun heldTrades(civId: String): List<HeldTrade> = held[civId]?.trades ?: listOf()
    fun heldAlerts(civId: String): List<HeldAlert> = held[civId]?.alerts ?: listOf()
    fun isHolding(civId: String) = held.containsKey(civId)

    /** Age of an item for its owner: 0 the first turn the owner saw it at H1 */
    fun age(civId: String, id: String, turn: Int): Int = firstSeen["$civId|$id"]?.let { (turn - it).coerceAtLeast(0) } ?: 0

    /** Turns before the bridge refuses an item ([holdTurns] - age, at least 0) */
    fun expiresIn(civId: String, id: String, turn: Int, holdTurns: Int) = (holdTurns - age(civId, id, turn)).coerceAtLeast(0)

    /** H1, before the observation: records the first sighting of each item of the civ and forgets the vanished ones */
    fun track(civ: Civilization) {
        val turn = civ.gameInfo.turns
        val present = HashSet<String>()
        for (request in civ.tradeRequests) present += DiplomacyIds.tradeRequestId(request)
        for (alert in civ.popupAlerts) present += DiplomacyIds.alertId(alert)
        val prefix = "${civ.civID}|"
        firstSeen.keys.removeAll { it.startsWith(prefix) && it.removePrefix(prefix) !in present }
        for (id in present) firstSeen.putIfAbsent(prefix + id, turn)
    }

    /** The bridge answered an item for the LLM (or refused it on expiry): its age is forgotten */
    fun forget(civId: String, id: String) {
        firstSeen.remove("$civId|$id")
    }

    // region H1

    /** H1: the copies are still out of the lists (H4 not reached): put them back first. Returns what was dropped. */
    fun restoreStale(civ: Civilization, watch: DiplomacyWatch?): Summary? {
        val stored = held[civ.civID] ?: return null
        if (stored.gameId == civ.gameInfo.gameId && stored.turn == civ.gameInfo.turns) return null
        return restore(civ, watch)
    }

    /**
     * H1, before the observation: explicit refusal of the items at least [holdTurns] old (`TradeRequest.decline`,
     * `DeclinedDeclarationOfFriendship` = 10 on the proposer, `refuseDemand`), then one request per request_id and one
     * alert per alert_id. Returns the global events to send (`trade_expired_refused`…), already registered in [watch].
     */
    fun expire(civ: Civilization, holdTurns: Int, watch: DiplomacyWatch?): List<JsonObject> {
        val turn = civ.gameInfo.turns
        val events = ArrayList<JsonObject>()
        for (request in civ.tradeRequests.toList()) {
            val id = DiplomacyIds.tradeRequestId(request)
            if (age(civ.civID, id, turn) < holdTurns) continue
            civ.tradeRequests.remove(request)
            forget(civ.civID, id)
            val requester = civ.gameInfo.getCivilizationOrNull(request.requestingCiv)
            if (requester == null || requester.isDefeated() || civ.getDiplomacyManager(requester) == null) {
                watch?.noteTradeDecision(id, DiplomacyWatch.TRADE_EXPIRED, DiplomacyWatch.BY_EXPIRY)
                continue
            }
            request.decline(civ)
            requester.addNotification("[${civ.civName}] has denied your trade request", NotificationCategory.Trade, civ.civName, NotificationIcon.Trade)
            watch?.let { events += it.emitTradeExpiredRefused(civ, request.requestingCiv, id, request.trade.isPeaceTreaty()) }
        }
        for (alert in civ.popupAlerts.toList()) {
            if (!DiplomacyIds.isActionable(alert.type)) continue
            val id = DiplomacyIds.alertId(alert)
            if (age(civ.civID, id, turn) < holdTurns) continue
            civ.popupAlerts.remove(alert)
            forget(civ.civID, id)
            val other = civ.gameInfo.getCivilizationOrNull(alert.value)
            val dm = other?.let { civ.getDiplomacyManager(it) }
            if (other == null || other.isDefeated() || dm == null) continue
            val demand = DiplomacyIds.demandOf(alert.type)
            if (demand != null) {
                dm.refuseDemand(demand)
                watch?.let { events += it.emitDemandExpiredRefused(civ, other.civID, demand.name) }
            } else if (!civ.isAtWarWith(other)) {
                dm.otherCivDiplomacy().setFlag(DiplomacyFlags.DeclinedDeclarationOfFriendship, FRIENDSHIP_REFUSAL_TURNS)
                other.addNotification("[${civ.civName}] has denied our Declaration of Friendship!", NotificationCategory.Diplomacy, NotificationIcon.Diplomacy, civ.civName)
                watch?.let { events += it.emitFriendshipExpiredRefused(civ, other.civID) }
            }
        }
        dedupe(civ)
        return events
    }

    /** One request per request_id and one alert per alert_id (the native AI may send the same one every turn) */
    fun dedupe(civ: Civilization) {
        val requestIds = HashSet<String>()
        civ.tradeRequests.removeAll { !requestIds.add(DiplomacyIds.tradeRequestId(it)) }
        val alertIds = HashSet<String>()
        civ.popupAlerts.removeAll { !alertIds.add(DiplomacyIds.alertId(it)) }
    }

    /**
     * H1, after the orders: takes out of the lists every request and actionable alert still there (= not answered),
     * except those the orchestrator explicitly left to the native AI ([nativeIds]). Nothing is held for a civ in a
     * native pause (the caller does not call this then).
     */
    fun hold(civ: Civilization, nativeIds: Set<String>): Summary {
        val trades = ArrayList<HeldTrade>()
        for (request in civ.tradeRequests.toList()) {
            val id = DiplomacyIds.tradeRequestId(request)
            if (id in nativeIds) continue
            civ.tradeRequests.remove(request)
            trades += HeldTrade(id, DiplomacyIds.detachedCopy(request))
        }
        val alerts = ArrayList<HeldAlert>()
        for (alert in civ.popupAlerts.toList()) {
            if (!DiplomacyIds.isActionable(alert.type)) continue
            val id = DiplomacyIds.alertId(alert)
            if (id in nativeIds) continue
            civ.popupAlerts.remove(alert)
            alerts += HeldAlert(id, alert.type, alert.value)
        }
        // merge with copies still held (should not happen: restoreStale runs first)
        val previous = held[civ.civID]
        val allTrades = (previous?.trades ?: listOf()).filter { old -> trades.none { it.id == old.id } } + trades
        val allAlerts = (previous?.alerts ?: listOf()).filter { old -> alerts.none { it.id == old.id } } + alerts
        if (allTrades.isEmpty() && allAlerts.isEmpty()) held.remove(civ.civID)
        else held[civ.civID] = Held(civ.gameInfo.gameId, civ.gameInfo.turns, allTrades, allAlerts)
        return Summary(trades.map { it.id }, alerts.map { it.id })
    }

    // endregion
    // region H4

    /**
     * H4 (or the next H1 if H4 was missed): puts the copies back, except those no longer valid: proposer gone or
     * defeated, trade no longer valid (`isTradeValid`), proposer now at war with us (except a peace offer), friendship
     * offer from a civ at war with us. Returns what was dropped; dropped trades are noted as `trade_expired` in [watch].
     */
    fun restore(civ: Civilization, watch: DiplomacyWatch?): Summary {
        val stored = held.remove(civ.civID) ?: return Summary(listOf(), listOf())
        val droppedTrades = ArrayList<String>()
        val droppedAlerts = ArrayList<String>()
        val presentRequests = civ.tradeRequests.map { DiplomacyIds.tradeRequestId(it) }.toHashSet()
        for (item in stored.trades) {
            if (item.id in presentRequests) continue
            val requester = civ.gameInfo.getCivilizationOrNull(item.request.requestingCiv)
            val valid = requester != null && !requester.isDefeated() && civ.getDiplomacyManager(requester) != null
                && (!civ.isAtWarWith(requester) || item.request.trade.isPeaceTreaty())
                && TradeEvaluation().isTradeValid(item.request.trade, civ, requester)
            if (valid) {
                civ.tradeRequests.add(DiplomacyIds.detachedCopy(item.request))
                presentRequests += item.id
            } else {
                droppedTrades += item.id
                forget(civ.civID, item.id)
                watch?.noteTradeDecision(item.id, DiplomacyWatch.TRADE_EXPIRED, DiplomacyWatch.BY_EXPIRY)
            }
        }
        val presentAlerts = civ.popupAlerts.map { DiplomacyIds.alertId(it) }.toHashSet()
        for (item in stored.alerts) {
            if (item.id in presentAlerts) continue
            val proposer = civ.gameInfo.getCivilizationOrNull(item.value)
            val valid = proposer != null && !proposer.isDefeated() && civ.getDiplomacyManager(proposer) != null
                && (item.type != AlertType.DeclarationOfFriendship || !civ.isAtWarWith(proposer))
            if (valid) {
                civ.popupAlerts.add(PopupAlert(item.type, item.value))
                presentAlerts += item.id
            } else {
                droppedAlerts += item.id
                forget(civ.civID, item.id)
            }
        }
        return Summary(droppedTrades, droppedAlerts)
    }

    // endregion

    fun clear() {
        held.clear()
        firstSeen.clear()
    }

    companion object {
        /** Same duration as the native AI's refusal (`NextTurnAutomation.respondToDeclarationOfFriendship`); provisional */
        const val FRIENDSHIP_REFUSAL_TURNS = 10
    }
}
