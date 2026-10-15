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
}
