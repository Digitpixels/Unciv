package com.unciv.iaciv

import com.unciv.logic.civilization.Civilization
import com.unciv.utils.Log

/**
 * Entry points called by the one-line hooks in Unciv's core (`git grep "IA-Civ hook"`).
 * Nothing here may ever throw back into the game loop.
 */
object IaCivHooks {

    /**
     * H1 — start of `NextTurnAutomation.automateCivMoves(civInfo)`, after the barbarian/spectator checks and before
     * the native AI answers popups and trade requests.
     * [tradeAndChangeState] is false for "forced" automation (e.g. skipping a human's turn): the bridge does nothing then.
     */
    @JvmStatic
    fun onAiTurn(civInfo: Civilization, tradeAndChangeState: Boolean) {
        if (!tradeAndChangeState) return
        try {
            IaCivBridge.onAiTurn(civInfo)
        } catch (e: Exception) {
            Log.error("[IA-Civ] H1 failed for ${civInfo.civID}, native AI plays this turn", e)
        } catch (e: LinkageError) {
            Log.error("[IA-Civ] H1 failed for ${civInfo.civID}, native AI plays this turn", e)
        }
    }

    /**
     * H4 — last line of `NextTurnAutomation.automateCivMoves(civInfo)`, after `trainSettler` and the diplomatic vote:
     * the last point where the native AI acts for this civ this turn. Measures whether the orders applied at H1 are
     * still in place (`order_check` events) and re-enforces the disabled constructions. Same guard as H1.
     */
    @JvmStatic
    fun onAiTurnEnd(civInfo: Civilization, tradeAndChangeState: Boolean) {
        if (!tradeAndChangeState) return
        try {
            IaCivBridge.onAiTurnEnd(civInfo)
        } catch (e: Exception) {
            Log.error("[IA-Civ] H4 failed for ${civInfo.civID}", e)
        } catch (e: LinkageError) {
            Log.error("[IA-Civ] H4 failed for ${civInfo.civID}", e)
        }
    }

    /**
     * H5 — first line of `NextTurnAutomation.trainSettler(civInfo)`: true = skip it. For an LLM civ the native AI no
     * longer replaces the head of a city queue with a Settler (it undid the courts' orders): only the LLM decides to
     * expand. False when the bridge is inert, for other civs, during a native pause, and on any error.
     */
    @JvmStatic
    fun ownsExpansion(civInfo: Civilization): Boolean = try {
        IaCivBridge.ownsExpansion(civInfo)
    } catch (e: Exception) {
        Log.error("[IA-Civ] H5 failed for ${civInfo.civID}, native settler logic kept", e)
        false
    } catch (e: LinkageError) {
        Log.error("[IA-Civ] H5 failed for ${civInfo.civID}, native settler logic kept", e)
        false
    }

    /**
     * H2 — wraps the major-civ diplomacy block of `NextTurnAutomation.automateCivMoves` (war, peace, help requests,
     * friendship, denunciation, embassies, open borders, research agreements, defensive pacts, luxury exchanges,
     * demands): true = the native AI skips it (religion is kept by the hook's `else`). True for an LLM civ outside a
     * native pause, unless the orchestrator answered `native.diplomacy = "allowed"` this turn. False when the bridge is
     * inert, for other civs, and on any error (contract M3 §1: same choice as H5, provisional default).
     */
    @JvmStatic
    fun ownsDiplomacy(civInfo: Civilization): Boolean = try {
        IaCivBridge.ownsDiplomacy(civInfo)
    } catch (e: Exception) {
        Log.error("[IA-Civ] H2 failed for ${civInfo.civID}, native diplomacy kept this turn", e)
        false
    } catch (e: LinkageError) {
        Log.error("[IA-Civ] H2 failed for ${civInfo.civID}, native diplomacy kept this turn", e)
        false
    }
}
