package com.unciv.iaciv.display

import com.unciv.iaciv.dto.DisplayResultDto
import com.unciv.logic.GameInfo
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.LocationAction
import com.unciv.logic.civilization.NotificationAction
import com.unciv.logic.civilization.NotificationCategory
import com.unciv.logic.civilization.NotificationIcon
import com.unciv.logic.civilization.PlayerType
import com.unciv.utils.Log
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Contract M3 §5 — messages shown in the game (`OrdersResponse.display[]`): a notification added to the spectators
 * (else to the human civs) of the game being played (the clone in UI mode), at H1 after the orders, on the turn thread.
 * Pure data: never on the GL thread, never an exception back to the game. The notification is saved with the game,
 * so the display can be checked in `turn_{N+1}.json`.
 *
 * The text goes through `tr()` when shown: `[ ] { } < >` are removed, line breaks flattened, the text cut at
 * [MAX_TEXT] characters with "…", and the template always holds at least one `[parameter]` (nation or label), so
 * the translation keeps the free text as it is (no reformatting of numbers, research RM §1.4).
 */
object InGameMessages {
    const val MAX_TEXT = 200
    private const val MAX_LABEL = 60
    private const val SECRET_PREFIX = "(secret) "
    private val FORBIDDEN = Regex("[\\[\\]{}<>]")
    private val SPACES = Regex("\\s+")

    const val KIND_MESSAGE = "diplomatic_message"
    const val KIND_DECREE = "decree_headline"

    private class Reject(val reason: String) : RuntimeException(reason) {
        override fun fillInStackTrace(): Throwable = this
    }

    /** Ready-to-add notification */
    internal class Prepared(val text: String, val category: NotificationCategory, val icons: Array<String>,
                            val actions: List<NotificationAction>, val truncated: Boolean)

    /** One result per item, in order. Never throws. */
    fun show(gameInfo: GameInfo, display: List<JsonObject>): List<DisplayResultDto> = display.mapIndexed { index, item ->
        val kind = item.str("kind") ?: ""
        val messageId = item.str("message_id")
        try {
            val prepared = when (kind) {
                "" -> throw Reject("missing_field")
                KIND_MESSAGE -> prepareDiplomaticMessage(gameInfo, item)
                KIND_DECREE -> prepareDecreeHeadline(gameInfo, item)
                else -> throw Reject("unknown_kind")
            }
            val viewers = viewers(gameInfo)
            if (viewers.isEmpty()) throw Reject("no_viewer")
            for (viewer in viewers) viewer.addNotification(prepared.text, prepared.actions, prepared.category, *prepared.icons)
            DisplayResultDto(index, kind, messageId, "shown", null, prepared.text, prepared.truncated)
        } catch (reject: Reject) {
            DisplayResultDto(index, kind, messageId, "rejected", reject.reason)
        } catch (e: Exception) {
            Log.error("[IA-Civ] display item $index failed", e)
            DisplayResultDto(index, kind, messageId, "rejected", "exception")
        }
    }

    /** Spectators; else the human civs (their notifications are kept, an AI's are dropped by addNotification) */
    fun viewers(gameInfo: GameInfo): List<Civilization> {
        val spectators = gameInfo.civilizations.filter { it.isSpectator() }
        return spectators.ifEmpty { gameInfo.civilizations.filter { it.playerType == PlayerType.Human && !it.isDefeated() } }
    }

    /** Removes `[ ] { } < >`, flattens line breaks and spaces, cuts at [max] characters with "…". Second = cut? */
    fun sanitize(raw: String, max: Int = MAX_TEXT): Pair<String, Boolean> {
        val clean = raw.replace(FORBIDDEN, "").replace(SPACES, " ").trim()
        if (clean.length <= max) return clean to false
        return clean.take(max - 1).trimEnd() + "…" to true
    }

    /** "[America] à [France] — Speaker : « text »"; "(secret) " first if private; Diplomacy; icons to ✉ from */
    internal fun prepareDiplomaticMessage(gameInfo: GameInfo, item: JsonObject): Prepared {
        val from = civ(gameInfo, item.str("from") ?: throw Reject("missing_field"))
        val to = civ(gameInfo, item.str("to") ?: throw Reject("missing_field"))
        val (text, truncated) = sanitize(item.str("text") ?: throw Reject("missing_field"))
        if (text.isEmpty()) throw Reject("empty_text")
        val speaker = item.str("speaker")?.let { sanitize(it, MAX_LABEL).first }?.takeIf { it.isNotEmpty() }
        val private = item.str("visibility") == "private"
        val message = buildString {
            if (private) append(SECRET_PREFIX)
            append(name(from, item.str("from_label"))).append(" à ").append(name(to, item.str("to_label")))
            append(" — ")
            if (speaker != null) append(speaker).append(" : ")
            append("« ").append(text).append(" »")
        }
        return Prepared(message, NotificationCategory.Diplomacy, icons(gameInfo, to.civID, NotificationIcon.Diplomacy, from.civID),
            locations(from, to), truncated)
    }

    /** "Décret de [America] : text"; General (shown first, no header); icon of the nation */
    internal fun prepareDecreeHeadline(gameInfo: GameInfo, item: JsonObject): Prepared {
        val civ = civ(gameInfo, item.str("civ") ?: throw Reject("missing_field"))
        val (text, truncated) = sanitize(item.str("text") ?: throw Reject("missing_field"))
        if (text.isEmpty()) throw Reject("empty_text")
        val message = "Décret de ${name(civ, item.str("civ_label"))} : $text"
        return Prepared(message, NotificationCategory.General, icons(gameInfo, civ.civID), locations(civ), truncated)
    }

    private fun civ(gameInfo: GameInfo, id: String): Civilization {
        val civ = gameInfo.getCivilizationOrNull(id)
        if (civ == null || civ.isSpectator() || civ.isBarbarian) throw Reject("unknown_civ")
        return civ
    }

    /** `[label]` (cleaned) if given, else `[civID]`: translated to the nation's name by the notification screen */
    private fun name(civ: Civilization, label: String?): String {
        val clean = label?.let { sanitize(it, MAX_LABEL).first }?.takeIf { it.isNotEmpty() }
        return "[${clean ?: civ.civID}]"
    }

    /** Nation names become portraits; only names the ruleset knows (else the texture lookup fails) */
    private fun icons(gameInfo: GameInfo, vararg names: String): Array<String> =
        names.filter { it.startsWith("OtherIcons/") || gameInfo.ruleset.nations.containsKey(it) }.toTypedArray()

    /** A click centres the map on the capitals (not DiplomacyAction: the spectator may not know these civs) */
    private fun locations(vararg civs: Civilization): List<NotificationAction> =
        LocationAction(*civs.map { it.getCapital()?.location }.toTypedArray()).toList()

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull && it.isString }?.content
}
