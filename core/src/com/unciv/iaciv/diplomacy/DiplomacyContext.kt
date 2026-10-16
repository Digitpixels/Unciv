package com.unciv.iaciv.diplomacy

import com.unciv.iaciv.config.BridgeConfig
import com.unciv.logic.civilization.AlertType
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.diplomacy.DiplomacyFlags
import com.unciv.logic.civilization.diplomacy.DiplomaticStatus
import com.unciv.logic.trade.TradeRequest

/**
 * What the observation and the diplomatic orders need from the bridge's diplomatic memory (contract M3): the
 * settings, the items held by [hold] (they still count as pending at their target) and the watcher's registry
 * ([watch]: counter-proposals, ages, start of wars). The defaults give a bridge-less context (tests).
 */
class DiplomacyContext(
    val peaceRule: String = BridgeConfig.PEACE_RULE_UI,
    val pendingHoldTurns: Int = 2,
    val watch: DiplomacyWatch? = null,
    val hold: PendingHold? = null,
) {
    val uiPeaceRule get() = peaceRule != BridgeConfig.PEACE_RULE_NONE

    /** [civ]'s trade requests, held copies included (id -> request) */
    fun requestsOf(civ: Civilization): Map<String, TradeRequest> {
        val result = LinkedHashMap<String, TradeRequest>()
        for (request in civ.tradeRequests) result.putIfAbsent(DiplomacyIds.tradeRequestId(request), request)
        for (held in hold?.heldTrades(civ.civID) ?: listOf()) result.putIfAbsent(held.id, held.request)
        return result
    }

    /** [civ]'s popup alerts as (type, value), held copies included */
    fun alertsOf(civ: Civilization): List<Pair<AlertType, String>> =
        civ.popupAlerts.map { it.type to it.value } + (hold?.heldAlerts(civ.civID)?.map { it.type to it.value } ?: listOf())

    fun hasAlertFrom(target: Civilization, type: AlertType, from: String) = alertsOf(target).any { it.first == type && it.second == from }

    /** Same rule as the trade screen: one offer of ours at a time per civ (`TradeView.hasPendingOfferFromUs`) */
    fun hasPendingOfferFrom(target: Civilization, from: String) = requestsOf(target).values.any { it.requestingCiv == from }

    /**
     * Turns before [civ] may propose peace to [other] (0 = now), null if not at war. With `peace_rule = "ui"` it is the
     * game's peace button rule: blocked while [other] keeps the `DeclaredWar` flag towards us (MajorCivDiplomacyTable,
     * CityStateDiplomacyTable), i.e. 10 turns after a declaration.
     */
    fun peacePossibleIn(civ: Civilization, other: Civilization): Int? {
        val dm = civ.getDiplomacyManager(other) ?: return null
        if (dm.diplomaticStatus != DiplomaticStatus.War) return null
        if (!uiPeaceRule) return 0
        return other.getDiplomacyManager(civ)?.getFlag(DiplomacyFlags.DeclaredWar) ?: 0
    }

    /** Turns since the war with [other] started (watcher), else an estimate from our `DeclaredWar` flag; null if not at war */
    fun atWarSince(civ: Civilization, other: Civilization): Int? {
        val dm = civ.getDiplomacyManager(other) ?: return null
        if (dm.diplomaticStatus != DiplomaticStatus.War) return null
        watch?.warStartTurn(civ.civID, other.civID)?.let { return (civ.gameInfo.turns - it).coerceAtLeast(0) }
        val minimum = civ.gameInfo.ruleset.modOptions.constants.minimumWarDuration
        val flag = dm.getFlag(DiplomacyFlags.DeclaredWar)
        return if (flag > 0) (minimum - flag).coerceAtLeast(0) else minimum
    }
}
