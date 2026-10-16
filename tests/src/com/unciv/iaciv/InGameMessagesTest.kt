package com.unciv.iaciv

import com.unciv.Constants
import com.unciv.iaciv.display.InGameMessages
import com.unciv.iaciv.dto.TurnEndRequest
import com.unciv.iaciv.dto.iaCivJson
import com.unciv.logic.civilization.NotificationCategory
import com.unciv.logic.civilization.PlayerType
import com.unciv.models.translations.tr
import com.unciv.testing.BaseTestRunner
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Contract M3 §5: messages shown in game as notifications of the spectator */
@RunWith(BaseTestRunner::class)
class InGameMessagesTest {
    private val world = DiplomacyWorld()
    private val gameInfo = world.testGame.gameInfo
    private val spectator = world.testGame.addCiv(world.testGame.ruleset.nations[Constants.spectator]!!, isPlayer = true)

    private fun message(vararg fields: Pair<String, String>): JsonObject = buildJsonObject {
        put("kind", "diplomatic_message")
        for ((key, value) in fields) put(key, value)
    }

    @Test
    fun `text is cleaned of the translation markers and cut at 200 characters`() {
        val (clean, cut) = InGameMessages.sanitize("Votre [trahison]\n{sera} <payée>\t au   centuple.")
        assertEquals("Votre trahison sera payée au centuple.", clean)
        assertFalse(cut)
        val (long, truncated) = InGameMessages.sanitize("é".repeat(300))
        assertTrue(truncated)
        assertEquals(200, long.length)
        assertTrue(long.endsWith("…"))
    }

    @Test
    fun `a diplomatic message becomes a Diplomacy notification of the spectator`() {
        val results = InGameMessages.show(gameInfo, listOf(message(
            "message_id" to "M-1", "from" to "America", "to" to "France", "speaker" to "Chancelière Hale",
            "text" to "En l'an 1453, [nous] vous offrons « la paix ».", "visibility" to "public",
        )))
        val result = results.single()
        assertEquals("shown", result.status)
        assertEquals("M-1", result.messageId)
        val notification = spectator.notifications.single()
        assertEquals(NotificationCategory.Diplomacy, notification.category)
        assertEquals(result.shownText, notification.text)
        assertEquals("[America] à [France] — Chancelière Hale : « En l'an 1453, nous vous offrons « la paix ». »", notification.text)
        assertTrue("nation portraits around the diplomacy icon", notification.icons.containsAll(listOf("America", "France")))
        assertFalse("the free text keeps its numbers through tr()", notification.text.tr().contains("1 453"))
        assertTrue(notification.text.tr().contains("1453"))
    }

    @Test
    fun `labels, private messages and decree headlines`() {
        val results = InGameMessages.show(gameInfo, listOf(
            message("from" to "America", "to" to "France", "text" to "Rien ne sera oublié.", "visibility" to "private",
                "from_label" to "empire [Claude]", "to_label" to "empire Mistral"),
            buildJsonObject { put("kind", "decree_headline"); put("civ", "France"); put("text", "La Grande Armée se lève."); put("civ_label", "empire Mistral") },
        ))
        assertEquals(listOf("shown", "shown"), results.map { it.status })
        val (secret, decree) = spectator.notifications
        assertEquals("(secret) [empire Claude] à [empire Mistral] — « Rien ne sera oublié. »", secret.text)
        assertEquals("Décret de [empire Mistral] : La Grande Armée se lève.", decree.text)
        assertEquals(NotificationCategory.General, decree.category)
    }

    @Test
    fun `invalid items are rejected with their reason, never thrown`() {
        val results = InGameMessages.show(gameInfo, listOf(
            buildJsonObject { put("text", "sans genre") },
            buildJsonObject { put("kind", "fanfare"); put("text", "?") },
            message("from" to "America", "text" to "pas de destinataire"),
            message("from" to "America", "to" to "Atlantide", "text" to "?"),
            message("from" to "America", "to" to "France", "text" to " [ ] { } < > "),
            message("from" to "America", "to" to "France", "text" to "x".repeat(250)),
        ))
        assertEquals(listOf("missing_field", "unknown_kind", "missing_field", "unknown_civ", "empty_text", null), results.map { it.reason })
        assertEquals(listOf(0, 1, 2, 3, 4, 5), results.map { it.index })
        assertTrue(results.last().truncated)
        assertEquals(1, spectator.notifications.size)
    }

    @Test
    fun `without spectator the human civs see the messages, without viewer the item is rejected`() {
        gameInfo.civilizations.remove(spectator)
        val noViewer = InGameMessages.show(gameInfo, listOf(message("from" to "America", "to" to "France", "text" to "Allô ?"))).single()
        assertEquals("no_viewer", noViewer.reason)
        world.them.playerType = PlayerType.Human
        val shown = InGameMessages.show(gameInfo, listOf(message("from" to "America", "to" to "France", "text" to "Allô ?"))).single()
        assertEquals("shown", shown.status)
        assertEquals(1, world.them.notifications.size)
        assertTrue("an AI civ never keeps notifications", world.us.notifications.isEmpty())
    }

    @Test
    fun `display results go on the wire in snake_case`() {
        val results = InGameMessages.show(gameInfo, listOf(message("message_id" to "M-9", "from" to "America", "to" to "France", "text" to "Bonjour.")))
        val request = TurnEndRequest(gameId = "g", turn = 1, civId = "America", decreeId = null, mode = "llm", results = listOf(),
            fallback = null, timingMs = mapOf(), displayResults = results)
        val json = iaCivJson.encodeToString(TurnEndRequest.serializer(), request)
        assertTrue(json.contains("\"display_results\""))
        assertTrue(json.contains("\"shown_text\""))
        assertTrue(json.contains("\"message_id\":\"M-9\""))
        assertNull(results.single().reason)
    }
}
