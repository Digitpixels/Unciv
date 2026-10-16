package com.unciv.iaciv

import com.unciv.iaciv.config.BridgeConfig
import com.unciv.iaciv.diplomacy.DiplomacyContext
import com.unciv.iaciv.diplomacy.DiplomacyIds
import com.unciv.iaciv.diplomacy.DiplomacyWatch
import com.unciv.iaciv.diplomacy.PendingHold
import com.unciv.iaciv.dto.OrderResultDto
import com.unciv.iaciv.orders.OrderChecker
import com.unciv.iaciv.orders.OrdersApplier
import com.unciv.logic.civilization.AlertType
import com.unciv.logic.civilization.PopupAlert
import com.unciv.logic.civilization.diplomacy.DiplomacyFlags
import com.unciv.logic.civilization.diplomacy.DiplomaticStatus
import com.unciv.testing.BaseTestRunner
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The 9 diplomatic orders of contract M3 §4: each one applied, and each rejection code */
@RunWith(BaseTestRunner::class)
class DiplomacyOrdersTest {
    private val world = DiplomacyWorld()
    private val us = world.us
    private val them = world.them
    private val third = world.third
    private val cityState = world.cityState
    private val hold = PendingHold()
    private val watch = DiplomacyWatch()
    private var peaceRule = BridgeConfig.PEACE_RULE_UI
    private val ctx get() = DiplomacyContext(peaceRule, 2, watch, hold)

    private fun order(type: String?, vararg fields: Pair<String, Any?>) = world.order(type, *fields)
    private fun apply(vararg orders: JsonObject) = OrdersApplier.apply(us, orders.toList(), ctx)
    private fun apply1(order: JsonObject): OrderResultDto = apply(order).results.single()

    /** H4 check of the orders of [outcome] */
    private fun checks(outcome: OrdersApplier.Outcome) =
        OrderChecker.verify(us, OrderChecker.capture(us, outcome.applied)!!, diplomacy = ctx).checks

    private fun declareWarOnFrance() = assertApplied(apply1(order("declare_war", "target" to "France")))

    // region declare_war

    @Test
    fun `declare war on a major civ and on a city-state`() {
        val outcome = apply(order("declare_war", "target" to "France"), order("declare_war", "target" to cityState.civID))
        outcome.results.forEach { assertApplied(it) }
        assertEquals(DiplomaticStatus.War, us.getDiplomacyManager(them)!!.diplomaticStatus)
        assertTrue(us.isAtWarWith(cityState))
        assertTrue("the target gets the game's war alert", them.popupAlerts.any { it.type == AlertType.WarDeclaration && it.value == "America" })
        assertEquals(listOf(OrderChecker.IN_PLACE, OrderChecker.IN_PLACE), checks(outcome).map { it.status })
    }

    @Test
    fun `declare war rejections`() {
        assertRejected("unknown_civ", apply1(order("declare_war", "target" to "Atlantide")))
        assertRejected("unknown_civ", apply1(order("declare_war", "target" to "Spectator")))
        assertRejected("self_target", apply1(order("declare_war", "target" to "America")))
        assertRejected("not_met", apply1(order("declare_war", "target" to "Germany")))
        assertRejected("missing_field", apply1(order("declare_war")))
        val dead = world.addDeadCiv()
        assertRejected("target_defeated", apply1(order("declare_war", "target" to dead.civID)))
        declareWarOnFrance()
        assertRejected("already_at_war", apply1(order("declare_war", "target" to "France")))
        // peace accepted -> 10-turn treaty
        world.sendTrade(them, us, peace = true)
        assertApplied(apply1(order("respond_trade", "request_id" to DiplomacyIds.tradeRequestId(us.tradeRequests.single()), "decision" to "accept")))
        assertTrue(us.getDiplomacyManager(them)!!.turnsToPeaceTreaty() > 0)
        assertRejected("peace_treaty_active", apply1(order("declare_war", "target" to "France")))
    }

    // endregion
    // region propose_peace

    @Test
    fun `propose peace follows the peace button rule then reaches the target`() {
        assertRejected("not_at_war", apply1(order("propose_peace", "target" to "France")))
        assertRejected("unknown_civ", apply1(order("propose_peace", "target" to "Germany")))
        declareWarOnFrance()
        val tooEarly = apply1(order("propose_peace", "target" to "France"))
        assertRejected("war_too_recent", tooEarly)
        assertTrue(tooEarly.detail!!.contains("10"))
        assertEquals(10, ctx.peacePossibleIn(us, them))

        peaceRule = BridgeConfig.PEACE_RULE_NONE // hard rules only
        assertEquals(0, ctx.peacePossibleIn(us, them))
        val result = apply1(order("propose_peace", "target" to "France", "gold_offered" to 0))
        assertApplied(result)
        val request = them.tradeRequests.single()
        assertTrue(request.trade.isPeaceTreaty())
        assertEquals("request_id = the id France sees", DiplomacyIds.tradeRequestId(request), result.requestId)
        assertRejected("offer_already_pending", apply1(order("propose_peace", "target" to "France")))
    }

    @Test
    fun `propose peace with gold, and its rejections`() {
        declareWarOnFrance()
        them.getDiplomacyManager(us)!!.removeFlag(DiplomacyFlags.DeclaredWar) // 10 turns later
        assertEquals(0, ctx.peacePossibleIn(us, them))
        us.addGold(-us.gold)
        assertRejected("not_enough_gold", apply1(order("propose_peace", "target" to "France", "gold_offered" to 500)))
        them.addGold(-them.gold)
        assertRejected("trade_invalid", apply1(order("propose_peace", "target" to "France", "gold_requested" to 500)))
        assertRejected("invalid_field", apply1(order("propose_peace", "target" to "France", "gold_offered" to -5)))
        us.addGold(1000)
        val outcome = apply(order("propose_peace", "target" to "France", "gold_offered" to 200))
        assertApplied(outcome.results.single())
        val stored = them.tradeRequests.single().trade
        assertTrue("France's view: we give the gold", stored.theirOffers.any { it.name == "Gold" && it.amount == 200 })
        assertEquals(OrderChecker.IN_PLACE, checks(outcome).single().status)
    }

    @Test
    fun `peace with a city-state is immediate`() {
        assertApplied(apply1(order("declare_war", "target" to cityState.civID)))
        assertRejected("war_too_recent", apply1(order("propose_peace", "target" to cityState.civID)))
        cityState.getDiplomacyManager(us)!!.removeFlag(DiplomacyFlags.DeclaredWar)
        val result = apply1(order("propose_peace", "target" to cityState.civID))
        assertApplied(result)
        assertNull(result.requestId)
        assertFalse(us.isAtWarWith(cityState))
        assertTrue("a peace treaty protects for 10 turns", us.getDiplomacyManager(cityState)!!.turnsToPeaceTreaty() > 0)
    }

    // endregion
    // region friendship

    @Test
    fun `offer friendship and its rejections`() {
        assertRejected("unknown_civ", apply1(order("offer_friendship", "target" to "Germany")))
        assertRejected("not_major_civ", apply1(order("offer_friendship", "target" to cityState.civID)))
        val outcome = apply(order("offer_friendship", "target" to "France"))
        assertApplied(outcome.results.single())
        assertTrue(them.popupAlerts.any { it.type == AlertType.DeclarationOfFriendship && it.value == "America" })
        assertEquals(OrderChecker.IN_PLACE, checks(outcome).single().status)
        assertRejected("friendship_already_offered", apply1(order("offer_friendship", "target" to "France")))
        // France is an LLM civ whose offers are held by the bridge until the end of its turn: still "already offered"
        hold.hold(them, setOf())
        assertTrue(them.popupAlerts.isEmpty())
        assertRejected("friendship_already_offered", apply1(order("offer_friendship", "target" to "France")))
        assertEquals("the held copy counts at H4", OrderChecker.IN_PLACE, checks(outcome).single().status)
        hold.restore(them, null)

        them.popupAlerts.clear()
        us.getDiplomacyManager(them)!!.setFlag(DiplomacyFlags.DeclinedDeclarationOfFriendship, 10)
        assertRejected("friendship_recently_declined", apply1(order("offer_friendship", "target" to "France")))
        us.getDiplomacyManager(them)!!.removeFlag(DiplomacyFlags.DeclinedDeclarationOfFriendship)
        assertApplied(apply1(order("denounce", "target" to "France")))
        assertRejected("cannot_sign_friendship", apply1(order("offer_friendship", "target" to "France")))
    }

    @Test
    fun `answer a friendship offer`() {
        us.diplomacyFunctions.makeCivilizationsMeet(third)
        us.popupAlerts.clear()
        us.popupAlerts.add(PopupAlert(AlertType.DeclarationOfFriendship, "France"))
        us.popupAlerts.add(PopupAlert(AlertType.DeclarationOfFriendship, "Germany"))
        val fromFrance = "PA-DeclarationOfFriendship-France"
        assertRejected("invalid_decision", apply1(order("respond_friendship", "alert_id" to fromFrance, "decision" to "peut-être")))
        assertRejected("unknown_alert", apply1(order("respond_friendship", "alert_id" to "PA-DeclarationOfFriendship-Atlantide", "decision" to "accept")))
        assertRejected("unknown_alert", apply1(order("respond_friendship", "alert_id" to "PA-3-0", "decision" to "accept")))
        val native = apply(order("respond_friendship", "alert_id" to fromFrance, "decision" to "native"))
        assertApplied(native.results.single())
        assertEquals(setOf(fromFrance), native.nativeIds)

        val outcome = apply(order("respond_friendship", "alert_id" to fromFrance, "decision" to "accept"),
            order("respond_friendship", "alert_id" to "PA-DeclarationOfFriendship-Germany", "decision" to "refuse"))
        outcome.results.forEach { assertApplied(it) }
        assertTrue(us.getDiplomacyManager(them)!!.hasFlag(DiplomacyFlags.DeclarationOfFriendship))
        assertTrue(them.getDiplomacyManager(us)!!.hasFlag(DiplomacyFlags.DeclarationOfFriendship))
        assertEquals(10, third.getDiplomacyManager(us)!!.getFlag(DiplomacyFlags.DeclinedDeclarationOfFriendship))
        assertTrue(us.popupAlerts.isEmpty())
        assertEquals(listOf(OrderChecker.IN_PLACE, OrderChecker.IN_PLACE), checks(outcome).map { it.status })
    }

    @Test
    fun `an obsolete or impossible friendship offer is rejected`() {
        us.diplomacyFunctions.makeCivilizationsMeet(third)
        us.getDiplomacyManager(third)!!.declareWar()
        us.popupAlerts.clear()
        us.popupAlerts.add(PopupAlert(AlertType.DeclarationOfFriendship, "Germany"))
        assertRejected("alert_obsolete", apply1(order("respond_friendship", "alert_id" to "PA-DeclarationOfFriendship-Germany", "decision" to "accept")))
        assertTrue("an obsolete offer is removed", us.popupAlerts.isEmpty())
        us.getDiplomacyManager(them)!!.denounce()
        us.popupAlerts.add(PopupAlert(AlertType.DeclarationOfFriendship, "France"))
        assertRejected("cannot_sign_friendship", apply1(order("respond_friendship", "alert_id" to "PA-DeclarationOfFriendship-France", "decision" to "accept")))
    }

    @Test
    fun `denounce and its rejections`() {
        assertRejected("not_major_civ", apply1(order("denounce", "target" to cityState.civID)))
        assertRejected("unknown_civ", apply1(order("denounce", "target" to "Germany")))
        val outcome = apply(order("denounce", "target" to "France"))
        assertApplied(outcome.results.single())
        assertTrue(us.getDiplomacyManager(them)!!.hasFlag(DiplomacyFlags.Denunciation))
        assertEquals(OrderChecker.IN_PLACE, checks(outcome).single().status)
        assertRejected("already_denounced", apply1(order("denounce", "target" to "France")))
        us.getDiplomacyManager(them)!!.removeFlag(DiplomacyFlags.Denunciation)
        us.getDiplomacyManager(them)!!.signDeclarationOfFriendship()
        assertRejected("friendship_active", apply1(order("denounce", "target" to "France")))
        us.getDiplomacyManager(them)!!.declareWar()
        assertRejected("at_war", apply1(order("denounce", "target" to "France")))
    }

    // endregion
    // region trades

    @Test
    fun `accept and refuse a trade request`() {
        them.addGold(500)
        world.sendTrade(them, us, goldGiven = 100)
        val id = DiplomacyIds.tradeRequestId(us.tradeRequests.single())
        assertRejected("invalid_decision", apply1(order("respond_trade", "request_id" to id, "decision" to "peut-être")))
        assertRejected("unknown_request", apply1(order("respond_trade", "request_id" to "TR-France-00000000", "decision" to "accept")))
        val goldBefore = us.gold
        val outcome = apply(order("respond_trade", "request_id" to id, "decision" to "accept"))
        assertApplied(outcome.results.single())
        assertEquals(goldBefore + 100, us.gold)
        assertTrue(us.tradeRequests.isEmpty())
        assertNull("request_id only for counters", outcome.results.single().requestId)
        assertEquals(OrderChecker.IN_PLACE, checks(outcome).single().status)

        world.sendTrade(them, us, goldGiven = 50)
        val second = DiplomacyIds.tradeRequestId(us.tradeRequests.single())
        val refused = apply(order("respond_trade", "request_id" to second, "decision" to "refuse"))
        assertApplied(refused.results.single())
        assertTrue(us.tradeRequests.isEmpty())
        assertEquals(OrderChecker.IN_PLACE, checks(refused).single().status)
    }

    @Test
    fun `counter-proposal goes back to the requester`() {
        them.addGold(500)
        us.addGold(500)
        world.sendTrade(them, us, goldGiven = 100)
        val id = DiplomacyIds.tradeRequestId(us.tradeRequests.single())
        assertRejected("missing_field", apply1(order("respond_trade", "request_id" to id, "decision" to "counter")))
        val tooMuch = mapOf("we_give" to listOf<Any>(), "they_give" to listOf(world.item("Gold", "Gold", 100_000)))
        assertRejected("counter_invalid", apply1(order("respond_trade", "request_id" to id, "decision" to "counter", "counter" to tooMuch)))
        assertEquals("an invalid counter leaves the offer pending", 1, us.tradeRequests.size)
        val counter = mapOf("we_give" to listOf<Any>(), "they_give" to listOf(world.item("Gold", "Gold", 200)))
        val outcome = apply(order("respond_trade", "request_id" to id, "decision" to "counter", "counter" to counter))
        val result = outcome.results.single()
        assertApplied(result)
        assertTrue(us.tradeRequests.isEmpty())
        val ours = them.tradeRequests.single()
        assertEquals("America", ours.requestingCiv)
        assertEquals(DiplomacyIds.tradeRequestId(ours), result.requestId)
        assertEquals("counter_to", id, watch.counterTo(result.requestId!!))
        assertEquals(OrderChecker.IN_PLACE, checks(outcome).single().status)
    }

    @Test
    fun `an answer to a trade that became invalid or whose sender is gone is rejected`() {
        them.addGold(500)
        world.sendTrade(them, us, goldGiven = 300)
        val id = DiplomacyIds.tradeRequestId(us.tradeRequests.single())
        them.addGold(-them.gold) // France spent its gold since the observation
        assertRejected("trade_invalid", apply1(order("respond_trade", "request_id" to id, "decision" to "accept")))
        assertTrue("the invalid offer is withdrawn", us.tradeRequests.isEmpty())

        val dead = world.addDeadCiv()
        us.tradeRequests.add(com.unciv.logic.trade.TradeRequest(dead.civID, com.unciv.logic.trade.Trade()))
        assertRejected("requester_defeated", apply1(order("respond_trade", "request_id" to DiplomacyIds.tradeRequestId(us.tradeRequests.single()), "decision" to "accept")))

        world.sendTrade(them, us)
        val nativeId = DiplomacyIds.tradeRequestId(us.tradeRequests.single())
        val native = apply(order("respond_trade", "request_id" to nativeId, "decision" to "native"))
        assertApplied(native.results.single())
        assertEquals(setOf(nativeId), native.nativeIds)
        assertEquals("left in the list for the native AI", 1, us.tradeRequests.size)
    }

    @Test
    fun `accepting a peace offer ends the war`() {
        declareWarOnFrance()
        world.sendTrade(them, us, peace = true)
        val outcome = apply(order("respond_trade", "request_id" to DiplomacyIds.tradeRequestId(us.tradeRequests.single()), "decision" to "accept"))
        assertApplied(outcome.results.single())
        assertFalse(us.isAtWarWith(them))
        assertEquals(OrderChecker.IN_PLACE, checks(outcome).single().status)
    }

    @Test
    fun `propose a trade and its rejections`() {
        us.addGold(300)
        assertRejected("not_major_civ", apply1(order("propose_trade", "target" to cityState.civID, "we_give" to listOf(world.item("Gold", "Gold", 10)))))
        assertRejected("empty_trade", apply1(order("propose_trade", "target" to "France")))
        assertRejected("offer_not_available", apply1(order("propose_trade", "target" to "France", "we_give" to listOf(world.item("Gold", "Gold", 100_000)))))
        assertRejected("offer_not_available", apply1(order("propose_trade", "target" to "France", "we_give" to listOf(world.item("Strategic_Resource", "Iron", 1)))))
        val outcome = apply(order("propose_trade", "target" to "France", "we_give" to listOf(world.item("Gold", "Gold", 100))))
        val result = outcome.results.single()
        assertApplied(result)
        val request = them.tradeRequests.single()
        assertEquals(DiplomacyIds.tradeRequestId(request), result.requestId)
        assertTrue("France's view: we give 100 gold", request.trade.theirOffers.any { it.name == "Gold" && it.amount == 100 })
        assertEquals(OrderChecker.IN_PLACE, checks(outcome).single().status)
        assertRejected("offer_already_pending", apply1(order("propose_trade", "target" to "France", "we_give" to listOf(world.item("Gold", "Gold", 10)))))
    }

    @Test
    fun `a treaty listed on one side only is shared by both sides`() {
        declareWarOnFrance()
        them.tradeRequests.clear()
        assertApplied(apply1(order("propose_trade", "target" to "France", "we_give" to listOf(world.item("Treaty", "Peace Treaty")))))
        assertTrue(them.tradeRequests.single().trade.isPeaceTreaty())
    }

    // endregion
    // region demands

    @Test
    fun `answer demands`() {
        us.popupAlerts.add(PopupAlert(AlertType.DemandToStopSettlingCitiesNear, "France"))
        us.popupAlerts.add(PopupAlert(AlertType.DemandToStopSpreadingReligion, "France"))
        us.popupAlerts.add(PopupAlert(AlertType.DeclarationOfFriendship, "France"))
        val settle = "PA-DemandToStopSettlingCitiesNear-France"
        assertRejected("invalid_decision", apply1(order("respond_demand", "alert_id" to settle, "decision" to "accept")))
        assertRejected("unknown_alert", apply1(order("respond_demand", "alert_id" to "PA-DemandToStopSettlingCitiesNear-Germany", "decision" to "agree")))
        assertRejected("not_a_demand", apply1(order("respond_demand", "alert_id" to "PA-DeclarationOfFriendship-France", "decision" to "agree")))
        val outcome = apply(order("respond_demand", "alert_id" to settle, "decision" to "agree"),
            order("respond_demand", "alert_id" to "PA-DemandToStopSpreadingReligion-France", "decision" to "refuse"))
        outcome.results.forEach { assertApplied(it) }
        val theirs = them.getDiplomacyManager(us)!!
        assertTrue(theirs.hasFlag(DiplomacyFlags.AgreedToNotSettleNearUs))
        assertTrue(theirs.hasFlag(DiplomacyFlags.IgnoreThemSpreadingReligion))
        assertEquals(listOf(AlertType.DeclarationOfFriendship), us.popupAlerts.map { it.type })
        assertEquals(listOf(OrderChecker.IN_PLACE, OrderChecker.IN_PLACE), checks(outcome).map { it.status })

        val dead = world.addDeadCiv()
        us.popupAlerts.add(PopupAlert(AlertType.DemandToStopSettlingCitiesNear, dead.civID))
        assertRejected("demander_defeated", apply1(order("respond_demand", "alert_id" to "PA-DemandToStopSettlingCitiesNear-${dead.civID}", "decision" to "agree")))
    }

    @Test
    fun `make a demand and its rejections`() {
        assertRejected("unknown_demand", apply1(order("make_demand", "target" to "France", "demand" to "DoNotBreathe")))
        assertRejected("unknown_civ", apply1(order("make_demand", "target" to "Germany", "demand" to "DoNotSettleNearUs")))
        assertRejected("demand_not_available", apply1(order("make_demand", "target" to "France", "demand" to "DoNotAttackUs")))
        assertRejected("demand_not_available", apply1(order("make_demand", "target" to cityState.civID, "demand" to "DoNotSettleNearUs")))
        val outcome = apply(order("make_demand", "target" to "France", "demand" to "DoNotSettleNearUs"))
        assertApplied(outcome.results.single())
        assertTrue(them.popupAlerts.any { it.type == AlertType.DemandToStopSettlingCitiesNear && it.value == "America" })
        assertEquals(OrderChecker.IN_PLACE, checks(outcome).single().status)
        assertRejected("demand_already_pending", apply1(order("make_demand", "target" to "France", "demand" to "DoNotSettleNearUs")))
        them.popupAlerts.clear()
        us.getDiplomacyManager(them)!!.setFlag(DiplomacyFlags.AgreedToNotSettleNearUs, 50)
        assertRejected("demand_not_available", apply1(order("make_demand", "target" to "France", "demand" to "DoNotSettleNearUs")))
    }

    // endregion
    // region order of application

    @Test
    fun `answers are applied before a war declaration of the same decree`() {
        them.addGold(500)
        world.sendTrade(them, us, goldGiven = 100)
        val id = DiplomacyIds.tradeRequestId(us.tradeRequests.single())
        val war = order("declare_war", "target" to "France")
        val answer = order("respond_trade", "request_id" to id, "decision" to "accept")
        val results = apply(war, answer).results
        assertEquals("results in the order received", listOf(war, answer).map { it.text("order_id") }, results.map { it.orderId })
        results.forEach { assertApplied(it) }
        assertTrue(us.isAtWarWith(them))
    }

    @Test
    fun `every diplomatic order without its fields is rejected, never thrown`() {
        val types = listOf("declare_war", "propose_peace", "offer_friendship", "respond_friendship", "respond_trade",
            "propose_trade", "denounce", "respond_demand", "make_demand")
        val results = apply(*types.map { order(it) }.toTypedArray()).results
        assertEquals(types.size, results.size)
        results.forEach { assertRejected("missing_field", it) }
        assertNotNull(results.first().detail)
    }

    // endregion
}
