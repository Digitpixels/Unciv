package com.unciv.iaciv

import com.unciv.iaciv.config.BridgeConfig
import com.unciv.iaciv.diplomacy.DiplomacyContext
import com.unciv.iaciv.diplomacy.DiplomacyIds
import com.unciv.iaciv.diplomacy.DiplomacyWatch
import com.unciv.iaciv.diplomacy.PendingHold
import com.unciv.iaciv.dto.ObservationDto
import com.unciv.iaciv.dto.iaCivJson
import com.unciv.iaciv.observe.EventCollector
import com.unciv.iaciv.observe.LegalOptions
import com.unciv.iaciv.observe.ObservationBuilder
import com.unciv.iaciv.orders.OrdersApplier
import com.unciv.logic.civilization.AlertType
import com.unciv.logic.civilization.PopupAlert
import com.unciv.logic.civilization.diplomacy.DiplomacyFlags
import com.unciv.testing.BaseTestRunner
import com.unciv.view.GameView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Contract M3 §2-§3: hold of the unanswered offers, expiry, stable ids, and the M3 fields of the observation */
@RunWith(BaseTestRunner::class)
class PendingHoldTest {
    private val world = DiplomacyWorld()
    private val gameInfo = world.testGame.gameInfo
    private val us = world.us
    private val them = world.them
    private val hold = PendingHold()
    private val watch = DiplomacyWatch().apply { llmCivs = setOf("America") }
    private val ctx = DiplomacyContext(BridgeConfig.PEACE_RULE_UI, 2, watch, hold)

    private fun nextTurn() {
        gameInfo.turns++
    }

    /** The bridge's sequence for the LLM civ [us]: H1 (track, expire), orders, hold; then H4 (restore) */
    private fun h1(): List<kotlinx.serialization.json.JsonObject> {
        hold.track(us)
        val events = hold.expire(us, 2, watch)
        hold.track(us)
        return events
    }

    @Test
    fun `unanswered offers are held during the native AI's window and come back at H4`() {
        world.sendTrade(them, us)
        us.popupAlerts.add(PopupAlert(AlertType.DeclarationOfFriendship, "France"))
        us.popupAlerts.add(PopupAlert(AlertType.DemandToStopSettlingCitiesNear, "France"))
        us.popupAlerts.add(PopupAlert(AlertType.WarDeclaration, "France")) // informative: left for the native AI to clear
        h1()
        val held = hold.hold(us, setOf())
        assertEquals(1, held.trades.size)
        assertEquals(listOf("PA-DeclarationOfFriendship-France", "PA-DemandToStopSettlingCitiesNear-France"), held.alerts)
        assertTrue(us.tradeRequests.isEmpty())
        assertEquals(listOf(AlertType.WarDeclaration), us.popupAlerts.map { it.type })
        us.popupAlerts.clear() // what respondToPopupAlerts does

        val dropped = hold.restore(us, watch)
        assertTrue(dropped.isEmpty())
        assertEquals(1, us.tradeRequests.size)
        assertEquals(setOf(AlertType.DeclarationOfFriendship, AlertType.DemandToStopSettlingCitiesNear), us.popupAlerts.map { it.type }.toSet())
        assertFalse(hold.isHolding("America"))
    }

    @Test
    fun `items left to the native AI are not held`() {
        world.sendTrade(them, us)
        us.popupAlerts.add(PopupAlert(AlertType.DeclarationOfFriendship, "France"))
        val requestId = DiplomacyIds.tradeRequestId(us.tradeRequests.single())
        val outcome = OrdersApplier.apply(us, listOf(
            world.order("respond_trade", "request_id" to requestId, "decision" to "native"),
            world.order("respond_friendship", "alert_id" to "PA-DeclarationOfFriendship-France", "decision" to "native"),
        ), ctx)
        val held = hold.hold(us, outcome.nativeIds)
        assertTrue(held.isEmpty())
        assertEquals(1, us.tradeRequests.size)
        assertEquals(1, us.popupAlerts.size)
    }

    @Test
    fun `a held copy that became invalid is not restored`() {
        them.addGold(500)
        world.sendTrade(them, us, goldGiven = 300)
        val id = DiplomacyIds.tradeRequestId(us.tradeRequests.single())
        h1()
        hold.hold(us, setOf())
        them.addGold(-them.gold) // France spends its gold during the round
        val dropped = hold.restore(us, watch)
        assertEquals(listOf(id), dropped.trades)
        assertTrue(us.tradeRequests.isEmpty())
    }

    @Test
    fun `H4 missed - the next H1 restores the copies`() {
        world.sendTrade(them, us)
        h1()
        hold.hold(us, setOf())
        assertTrue(us.tradeRequests.isEmpty())
        assertNull("same turn: nothing to restore yet", hold.restoreStale(us, watch))
        nextTurn()
        hold.restoreStale(us, watch)
        assertEquals(1, us.tradeRequests.size)
    }

    @Test
    fun `offers are refused explicitly after pending_hold_turns turns, with their events`() {
        us.diplomacyFunctions.makeCivilizationsMeet(world.third)
        us.popupAlerts.clear()
        world.sendTrade(them, us, peace = false)
        us.popupAlerts.add(PopupAlert(AlertType.DeclarationOfFriendship, "France"))
        us.popupAlerts.add(PopupAlert(AlertType.DemandToStopSettlingCitiesNear, "Germany"))
        val requestId = DiplomacyIds.tradeRequestId(us.tradeRequests.single())
        val turn0 = gameInfo.turns

        assertTrue(h1().isEmpty())
        val first = EventCollector.pending(us, ctx)
        assertEquals(0, first.tradeRequests.single().ageTurns)
        assertEquals(2, first.tradeRequests.single().expiresIn)
        hold.hold(us, setOf()); hold.restore(us, watch)

        nextTurn()
        assertTrue(h1().isEmpty())
        val second = EventCollector.pending(us, ctx)
        assertEquals(1, second.tradeRequests.single().ageTurns)
        assertEquals(1, second.popupAlerts.first { it.actionable }.expiresIn)
        hold.hold(us, setOf()); hold.restore(us, watch)

        nextTurn()
        val events = h1()
        assertEquals(turn0 + 2, gameInfo.turns)
        assertTrue(us.tradeRequests.isEmpty())
        assertTrue(us.popupAlerts.isEmpty())
        val byType = events.associateBy { it.text("type") }
        assertEquals(setOf("trade_expired_refused", "friendship_expired_refused", "demand_expired_refused"), byType.keys)
        assertEquals("trade:$requestId:${turn0 + 2}:expired_refused", byType.getValue("trade_expired_refused").text("event_key"))
        assertEquals("expiry", byType.getValue("trade_expired_refused").text("decided_by"))
        assertEquals("friendship:France>America:${turn0 + 2}:friendship_expired_refused", byType.getValue("friendship_expired_refused").text("event_key"))
        assertEquals("demand:Germany>America:DoNotSettleNearUs:${turn0 + 2}:demand_expired_refused", byType.getValue("demand_expired_refused").text("event_key"))
        // the game's own refusal effects
        assertEquals(PendingHold.FRIENDSHIP_REFUSAL_TURNS, them.getDiplomacyManager(us)!!.getFlag(DiplomacyFlags.DeclinedDeclarationOfFriendship))
        assertTrue(world.third.getDiplomacyManager(us)!!.hasFlag(DiplomacyFlags.IgnoreThemSettlingNearUs))
    }

    @Test
    fun `duplicates are removed and alert ids are stable from one turn to the next`() {
        repeat(2) { us.popupAlerts.add(PopupAlert(AlertType.DeclarationOfFriendship, "France")) }
        repeat(2) { world.sendTrade(them, us) }
        h1()
        assertEquals(1, us.popupAlerts.size)
        assertEquals(1, us.tradeRequests.size)
        val id = EventCollector.pending(us, ctx).popupAlerts.single().alertId
        assertEquals("PA-DeclarationOfFriendship-France", id)
        hold.hold(us, setOf()); hold.restore(us, watch)
        nextTurn()
        h1()
        val again = EventCollector.pending(us, ctx).popupAlerts.single()
        assertEquals(id, again.alertId)
        assertTrue(Regex("^PA-[A-Za-z]+-.+$").matches(again.alertId))
        assertTrue(again.actionable)
        assertNull(again.demand)
        assertEquals(1, again.ageTurns)
    }

    @Test
    fun `pending shows demands, counter-proposals and our own offers`() {
        us.popupAlerts.add(PopupAlert(AlertType.DemandToStopSettlingCitiesNear, "France"))
        us.popupAlerts.add(PopupAlert(AlertType.WonderBuilt, "Pyramids"))
        us.addGold(500)
        them.addGold(500)
        // our offer to France, then France's counter-proposal
        OrdersApplier.apply(us, listOf(world.order("propose_trade", "target" to "France", "we_give" to listOf(world.item("Gold", "Gold", 50)))), ctx)
        val ours = DiplomacyIds.tradeRequestId(them.tradeRequests.single())
        watch.observe(gameInfo, DiplomacyWatch.HOOK_H4, us, hold) // base snapshot
        them.tradeRequests.clear()
        world.sendTrade(them, us, goldAsked = 80)
        watch.observe(gameInfo, DiplomacyWatch.HOOK_H4, them, hold) // France's window: our offer replaced by its own
        them.popupAlerts.add(PopupAlert(AlertType.DeclarationOfFriendship, "America"))
        hold.track(us)
        val pending = EventCollector.pending(us, ctx)
        val demand = pending.popupAlerts.single { it.type == "DemandToStopSettlingCitiesNear" }
        assertEquals("DoNotSettleNearUs", demand.demand)
        assertTrue(demand.actionable)
        val wonder = pending.popupAlerts.single { it.type == "WonderBuilt" }
        assertFalse(wonder.actionable)
        assertNull(wonder.expiresIn)
        assertEquals("counter_to = our offer as France saw it", ours, pending.tradeRequests.single().counterTo)
        val outgoing = pending.outgoing.single()
        assertEquals("France", outgoing.to)
        assertEquals("friendship", outgoing.kind)
        assertEquals("PA-DeclarationOfFriendship-America", outgoing.requestId)
    }

    @Test
    fun `observation and legal options carry the M3 diplomacy fields`() {
        val cityState = world.cityState
        assertApplied(OrdersApplier.apply(us, listOf(world.order("declare_war", "target" to "France")), ctx).results.single())
        watch.observe(gameInfo, DiplomacyWatch.HOOK_H4, us, hold)
        val observation = ObservationBuilder(GameView(gameInfo, us), ctx).build()
        val france = observation.diplomacy.single { it.civId == "France" }
        assertEquals(10, france.peacePossibleIn)
        assertTrue(france.atWarSinceTurns!! >= 0)
        assertFalse(france.friendship)
        assertFalse(observation.cityStates.single().atWar)

        val legal = LegalOptions.build(us, ctx)
        assertTrue("city-states can be declared war on", cityState.civID in legal.canDeclareWarOn)
        assertFalse("France", "France" in legal.canDeclareWarOn)
        assertTrue("10-turn rule", legal.canProposePeaceTo.isEmpty())
        assertTrue("no denunciation at war", legal.canDenounce.isEmpty())
        assertTrue(legal.demandsAvailable.isEmpty())
        val peaceInTrade = legal.tradable.getValue("France").ours.single { it.name == "Peace Treaty" }
        assertEquals(1, peaceInTrade.amountMax)

        them.getDiplomacyManager(us)!!.removeFlag(com.unciv.logic.civilization.diplomacy.DiplomacyFlags.DeclaredWar)
        assertEquals(listOf("France"), LegalOptions.build(us, ctx).canProposePeaceTo)
        val json = iaCivJson.encodeToString(ObservationDto.serializer(), observation)
        assertTrue(json.contains("\"peace_possible_in\""))
        assertTrue(json.contains("\"at_war_since_turns\""))
        assertTrue(json.contains("\"they_denounced\""))
        val legalJson = iaCivJson.encodeToString(com.unciv.iaciv.dto.LegalOptionsDto.serializer(), legal)
        assertTrue(legalJson.contains("\"amount_max\""))
        assertTrue(legalJson.contains("\"demands_available\""))
    }

    @Test
    fun `legal options at peace - friendship, denunciation, demands and trades`() {
        us.addGold(120)
        val legal = LegalOptions.build(us, ctx)
        assertEquals(listOf("France"), legal.canOfferFriendshipTo)
        assertEquals(listOf("France"), legal.canDenounce)
        assertTrue("DoNotSettleNearUs" in legal.demandsAvailable.getValue("France"))
        assertFalse("never offered to AIs by the game", "DoNotAttackUs" in legal.demandsAvailable.getValue("France"))
        val gold = legal.tradable.getValue("France").ours.single { it.type == "Gold" }
        assertEquals(us.gold, gold.amountMax)
        assertFalse("only tradable offers (amount > 0)", legal.tradable.getValue("France").theirs.any { it.amountMax <= 0 })
        assertFalse("no trade with a city-state", world.cityState.civID in legal.tradable)

        them.popupAlerts.add(PopupAlert(AlertType.DeclarationOfFriendship, "America"))
        them.popupAlerts.add(PopupAlert(AlertType.DemandToStopSettlingCitiesNear, "America"))
        val after = LegalOptions.build(us, ctx)
        assertTrue("already offered", after.canOfferFriendshipTo.isEmpty())
        assertFalse("already demanded", "DoNotSettleNearUs" in (after.demandsAvailable["France"] ?: listOf()))
    }
}
