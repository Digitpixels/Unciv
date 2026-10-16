package com.unciv.iaciv

import com.unciv.iaciv.config.BridgeConfig
import com.unciv.iaciv.diplomacy.DiplomacyContext
import com.unciv.iaciv.diplomacy.DiplomacyIds
import com.unciv.iaciv.diplomacy.DiplomacyWatch
import com.unciv.iaciv.diplomacy.PendingHold
import com.unciv.iaciv.orders.OrdersApplier
import com.unciv.logic.civilization.Civilization
import com.unciv.testing.BaseTestRunner
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Contract M3 §6: global diplomatic events, detected once, with the expected `event_key` */
@RunWith(BaseTestRunner::class)
class DiplomacyWatchTest {
    private val world = DiplomacyWorld()
    private val gameInfo = world.testGame.gameInfo
    private val us = world.us
    private val them = world.them
    private val third = world.third
    private val hold = PendingHold()
    private val watch = DiplomacyWatch().apply { llmCivs = setOf("America") }
    private val ctx = DiplomacyContext(BridgeConfig.PEACE_RULE_NONE, 2, watch, hold)
    private val turn get() = gameInfo.turns

    private fun observe(hook: String, window: Civilization): List<JsonObject> = watch.observe(gameInfo, hook, window, hold)
    private fun h1(window: Civilization) = observe(DiplomacyWatch.HOOK_H1, window)
    private fun h4(window: Civilization) = observe(DiplomacyWatch.HOOK_H4, window)
    private fun applyForUs(vararg orders: JsonObject) = OrdersApplier.apply(us, orders.toList(), ctx).results.forEach { assertApplied(it) }
    private fun List<JsonObject>.ofType(type: String) = filter { it.text("type") == type }
    private fun keys(events: List<JsonObject>) = events.map { it.text("event_key") }

    @Test
    fun `the first snapshot is a base, without events`() {
        assertTrue(h1(us).isEmpty())
        assertTrue(h4(us).isEmpty())
    }

    @Test
    fun `a war declared by an LLM order is reported once, with its cause`() {
        h1(us)
        applyForUs(world.order("declare_war", "target" to "France"))
        val events = h4(us)
        val war = events.ofType("war_declared").single()
        assertEquals("war:America>France:$turn", war.text("event_key"))
        assertEquals("America", war.text("aggressor"))
        assertEquals("France", war.text("target"))
        assertEquals("declared", war.text("cause"))
        assertEquals("global", war.text("scope"))
        assertEquals("America", war.text("during_turn_of"))
        assertEquals("H4", war.text("hook"))
        assertEquals("true", war.text("llm_involved"))
        assertTrue("never twice", h1(them).ofType("war_declared").isEmpty())
        assertTrue(h4(them).ofType("war_declared").isEmpty())
    }

    @Test
    fun `peace accepted by the LLM - peace and trade outcome with the detection keys`() {
        us.getDiplomacyManager(them)!!.declareWar()
        h1(us)
        world.sendTrade(them, us, peace = true)
        val proposed = h4(them).ofType("trade_proposed").single()
        val id = DiplomacyIds.tradeRequestId(us.tradeRequests.single())
        assertEquals("trade:$id:$turn:proposed", proposed.text("event_key"))
        assertEquals("true", proposed.text("is_peace_treaty"))

        gameInfo.turns++
        h1(us)
        applyForUs(world.order("respond_trade", "request_id" to id, "decision" to "accept"))
        val events = h4(us)
        val peace = events.ofType("peace_signed").single()
        assertEquals("peace:America|France:$turn", peace.text("event_key"))
        assertEquals(listOf("America", "France"), (peace["civs"] as JsonArray).map { (it as JsonPrimitive).content })
        assertEquals("true", peace.text("via_trade"))
        val accepted = events.ofType("trade_accepted").single()
        assertEquals("trade:$id:$turn:accepted", accepted.text("event_key"))
        assertEquals("llm", accepted.text("decided_by"))
        assertTrue(h1(them).isEmpty())
    }

    @Test
    fun `a native answer to our offer is inferred - refused, accepted or countered`() {
        us.addGold(500)
        them.addGold(500)
        h4(us)
        applyForUs(world.order("propose_trade", "target" to "France", "we_give" to listOf(world.item("Gold", "Gold", 50))))
        val id = DiplomacyIds.tradeRequestId(them.tradeRequests.single())
        assertEquals(listOf("trade:$id:$turn:proposed"), keys(h4(us).ofType("trade_proposed")))
        h1(them)
        them.tradeRequests.clear() // France's native AI refuses (TradeAutomation.respondToTradeRequests)
        val refused = h4(them).ofType("trade_refused").single()
        assertEquals("trade:$id:$turn:refused", refused.text("event_key"))
        assertEquals("native", refused.text("decided_by"))
        assertEquals("America is involved", "true", refused.text("llm_involved"))

        // counter-proposal: our offer replaced by one of France's
        gameInfo.turns++
        applyForUs(world.order("propose_trade", "target" to "France", "we_give" to listOf(world.item("Gold", "Gold", 60))))
        val second = DiplomacyIds.tradeRequestId(them.tradeRequests.single())
        h4(us)
        them.tradeRequests.clear()
        world.sendTrade(them, us, goldAsked = 90)
        val events = h4(them)
        assertEquals("trade:$second:$turn:countered", events.ofType("trade_countered").single().text("event_key"))
        val counter = events.ofType("trade_proposed").single()
        assertEquals(second, watch.counterTo(counter.text("request_id")!!))
    }

    @Test
    fun `an LLM refusal and a refusal on expiry keep a single event`() {
        world.sendTrade(them, us)
        h4(them)
        val id = DiplomacyIds.tradeRequestId(us.tradeRequests.single())
        h1(us)
        applyForUs(world.order("respond_trade", "request_id" to id, "decision" to "refuse"))
        val refused = h4(us).filter { it.text("type")!!.startsWith("trade_") }
        assertEquals(listOf("trade:$id:$turn:refused"), keys(refused))
        assertEquals("llm", refused.single().text("decided_by"))

        world.sendTrade(them, us, goldAsked = 1) // another request, refused on expiry two turns later
        h4(them)
        val expiring = DiplomacyIds.tradeRequestId(us.tradeRequests.single())
        h1(us)
        hold.track(us)
        gameInfo.turns += 2
        val emitted = hold.expire(us, 2, watch)
        assertEquals(listOf("trade:$expiring:$turn:expired_refused"), keys(emitted))
        assertTrue("the detection does not report it again", h4(us).none { it.text("type")!!.startsWith("trade_") })
    }

    @Test
    fun `friendship offered then declared, denunciation, all once`() {
        h1(us)
        applyForUs(world.order("offer_friendship", "target" to "France"))
        val offered = h4(us).ofType("friendship_offered").single()
        assertEquals("friendship:America>France:$turn:friendship_offered", offered.text("event_key"))
        assertEquals("America", offered.text("from"))

        h1(them)
        them.popupAlerts.clear()
        them.getDiplomacyManager(us)!!.signDeclarationOfFriendship() // France's native AI accepts
        val declared = h4(them).ofType("friendship_declared").single()
        assertEquals("friendship:America|France:$turn:friendship_declared", declared.text("event_key"))

        gameInfo.turns++
        us.diplomacyFunctions.makeCivilizationsMeet(third)
        val contact = h1(us).ofType("first_contact").single()
        assertEquals("contact:America|Germany", contact.text("event_key"))
        applyForUs(world.order("denounce", "target" to "Germany"))
        val denounced = h4(us).ofType("denounced").single()
        assertEquals("denounce:America>Germany:$turn:denounced", denounced.text("event_key"))
        assertEquals("America", denounced.text("by"))
        assertTrue(h1(them).ofType("denounced").isEmpty())
        us.getDiplomacyManager(third)!!.removeFlag(com.unciv.logic.civilization.diplomacy.DiplomacyFlags.Denunciation) // 30 turns later
        val expired = h1(third).ofType("denunciation_expired").single()
        assertEquals("denounce:America>Germany:$turn:denunciation_expired", expired.text("event_key"))
    }

    @Test
    fun `native pairs - kept for wars between majors, filtered with none`() {
        h1(them)
        them.getDiplomacyManager(third)!!.declareWar()
        val war = h4(them).ofType("war_declared").single()
        assertEquals("war:France>Germany:$turn", war.text("event_key"))
        assertEquals("false", war.text("llm_involved"))
        assertEquals("declared", war.text("cause"))

        watch.nativePairs = DiplomacyWatch.NATIVE_NONE
        h1(third)
        third.getDiplomacyManager(them)!!.denounce()
        assertTrue(h4(third).isEmpty())

        watch.nativePairs = DiplomacyWatch.NATIVE_MAJOR_ONLY
        world.sendTrade(them, third) // trades between natives: not with major_only
        assertTrue(h4(them).isEmpty())
    }

    @Test
    fun `demands and city-state allies`() {
        h1(us)
        applyForUs(world.order("make_demand", "target" to "France", "demand" to "DoNotSettleNearUs"))
        val issued = h4(us).ofType("demand_issued").single()
        assertEquals("demand:America>France:DoNotSettleNearUs:$turn:demand_issued", issued.text("event_key"))
        h1(them)
        them.popupAlerts.clear()
        them.getDiplomacyManager(us)!!.agreeToDemand(com.unciv.logic.civilization.diplomacy.Demand.DoNotSettleNearUs)
        val accepted = h4(them).ofType("demand_accepted").single()
        assertEquals("demand:America>France:DoNotSettleNearUs:$turn:demand_accepted", accepted.text("event_key"))

        world.cityState.allyCiv = us
        val ally = h4(world.cityState).ofType("city_state_ally_changed").single()
        assertEquals("csally:${world.cityState.civID}:$turn", ally.text("event_key"))
        assertEquals("America", ally.text("new_ally"))
    }
}
