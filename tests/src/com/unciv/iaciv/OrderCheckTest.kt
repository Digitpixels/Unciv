package com.unciv.iaciv

import com.unciv.iaciv.observe.LegalOptions
import com.unciv.iaciv.orders.OrderCheck
import com.unciv.iaciv.orders.OrderChecker
import com.unciv.iaciv.orders.OrdersApplier
import com.unciv.iaciv.orders.PendingCheck
import com.unciv.iaciv.orders.StandingOrders
import com.unciv.logic.city.City
import com.unciv.logic.city.CityFocus
import com.unciv.logic.civilization.Civilization
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** H4 measure: the native AI's changes are simulated between the H1 capture and the check */
@RunWith(BaseTestRunner::class)
class OrderCheckTest {
    private val testGame = TestGame().apply { makeHexagonalMap(8) }
    private lateinit var us: Civilization
    private lateinit var city: City
    private var counter = 0

    @Before
    fun setUp() {
        us = testGame.addCiv(testGame.ruleset.nations["America"]!!)
        testGame.addCiv(testGame.ruleset.nations["France"]!!)
        city = testGame.addCity(us, testGame.getTile(-4, -4))
    }

    private fun order(type: String, vararg fields: Pair<String, Any>): JsonObject = buildJsonObject {
        put("order_id", "O-${++counter}")
        put("type", type)
        for ((key, value) in fields) when (value) {
            is String -> put(key, value)
            is List<*> -> put(key, JsonArray(value.map { JsonPrimitive(it as String) }))
        }
    }

    private fun queueOrder(mode: String, vararg items: String) =
        order("set_production_queue", "city_id" to city.id, "mode" to mode, "items" to items.toList())

    /** H1: apply, then capture */
    private fun applyAndCapture(vararg orders: JsonObject): PendingCheck {
        val outcome = OrdersApplier.apply(us, orders.toList())
        assertTrue("orders must apply: ${outcome.results}", outcome.results.all { it.status == "applied" })
        return OrderChecker.capture(us, outcome.applied)!!
    }

    /** H4 */
    private fun checks(pending: PendingCheck): List<OrderCheck> = OrderChecker.verify(us, pending).checks
    private fun check(pending: PendingCheck): OrderCheck = checks(pending).single()

    @Test
    fun `research, free tech, policy and purchase orders are in place or completed`() {
        us.policies.storedCulture = 1000
        us.tech.freeTechs = 1
        us.addGold(1000)
        city.cityConstructions.editQueue { clear(); add("Worker") } // a purchase never leaves the queue empty
        val free = LegalOptions.build(us).techs.first()
        val pending = applyAndCapture(
            order("set_research_goal", "tech" to "Writing"),
            order("adopt_policy", "policy" to "Tradition"),
            order("choose_free_tech", "tech" to free),
            order("purchase", "city_id" to city.id, "construction" to "Monument", "stat" to "Gold"),
        )
        val byType = checks(pending).associateBy { it.orderType }
        assertEquals(OrderChecker.IN_PLACE, byType.getValue("set_research_goal").status)
        assertEquals(OrderChecker.IN_PLACE, byType.getValue("adopt_policy").status)
        assertEquals(OrderChecker.COMPLETED, byType.getValue("choose_free_tech").status)
        assertEquals(OrderChecker.COMPLETED, byType.getValue("purchase").status)
    }

    @Test
    fun `a research goal reached during the turn is completed, a rewritten queue is overridden`() {
        val tech = LegalOptions.build(us).techs.first()
        val pending = applyAndCapture(order("set_research_goal", "tech" to tech))
        us.tech.addTechnology(tech) // e.g. a Great Scientist during the native AI's window
        assertEquals(OrderChecker.COMPLETED, check(pending).status)

        val other = LegalOptions.build(us).techs.first()
        val second = applyAndCapture(order("set_research_goal", "tech" to "Writing"))
        us.tech.techsToResearch = arrayListOf(other)
        val overridden = check(second)
        assertEquals(OrderChecker.OVERRIDDEN, overridden.status)
        assertEquals("native_ai", overridden.overriddenBy)
    }

    @Test
    fun `production items still queued are in place, even behind an inserted wonder`() {
        val pending = applyAndCapture(queueOrder("replace", "Monument", "Warrior"))
        assertEquals(OrderChecker.IN_PLACE, check(pending).status)
        city.cityConstructions.editQueue { add(0, "Pyramids") } // Great Engineer
        assertEquals(OrderChecker.IN_PLACE, check(pending).status)
    }

    @Test
    fun `a head replaced by a military unit is a defensive override`() {
        val pending = applyAndCapture(queueOrder("replace", "Monument", "Warrior"))
        city.cityConstructions.setCurrentConstruction("Spearman") // Automation.tryTrainMilitaryUnit
        val result = check(pending)
        assertEquals(OrderChecker.OVERRIDDEN, result.status)
        assertEquals("native_defense", result.overriddenBy)
        assertTrue(result.detail!!.contains("Spearman"))
    }

    @Test
    fun `a head replaced by a settler or a building is told apart`() {
        val pending = applyAndCapture(queueOrder("replace", "Monument", "Warrior"))
        city.cityConstructions.setCurrentConstruction("Settler") // trainSettler
        assertEquals("native_settler", check(pending).overriddenBy)
        city.cityConstructions.setCurrentConstruction("Barracks")
        assertEquals("native_ai", check(pending).overriddenBy)
    }

    @Test
    fun `items bought by the native AI are completed`() {
        val pending = applyAndCapture(queueOrder("replace", "Warrior", "Scout"), queueOrder("prepend", "Monument"))
        assertEquals(listOf("Monument", "Warrior", "Scout"), city.cityConstructions.constructionQueue)
        // UseGoldAutomation buys the head of the queue, then the next one
        city.cityConstructions.purchaseConstruction("Monument", 0, automatic = true)
        city.cityConstructions.purchaseConstruction("Warrior", 0, automatic = true)
        val (replace, prepend) = checks(pending)
        assertEquals(OrderChecker.COMPLETED, prepend.status)
        assertEquals("one item bought, one still queued", OrderChecker.IN_PLACE, replace.status)
        assertTrue(replace.detail!!.contains("Warrior acheté"))
    }

    @Test
    fun `a focus changed during the turn is overridden, and the standing focus comes back`() {
        val pending = applyAndCapture(order("set_city_focus", "city_id" to city.id, "focus" to "GoldFocus"))
        assertEquals(OrderChecker.IN_PLACE, check(pending).status)
        city.setCityFocus(CityFocus.NoFocus)
        assertEquals("native_ai", check(pending).overriddenBy)

        // next turn start: CityTurnManager resets the focus, the bridge re-applies it at H1
        val standing = StandingOrders()
        standing.recordFocus(us.civID, city.id, "O-1", "GoldFocus", 1)
        assertEquals(listOf("${city.name} : GoldFocus"), standing.reapplyFocus(us))
        assertEquals(CityFocus.GoldFocus, city.getCityFocus())
        assertTrue("nothing to do when the focus is already right", standing.reapplyFocus(us).isEmpty())
        val status = standing.statuses(us).single()
        assertEquals("O-1", (status["order_id"] as JsonPrimitive).content)
        assertEquals("standing", (status["status"] as JsonPrimitive).content)
    }

    @Test
    fun `a disabled construction re-queued by the native AI is removed by the bridge`() {
        val pending = applyAndCapture(queueOrder("replace", "Warrior"), order("disable_construction", "name" to "Scout"))
        city.cityConstructions.editQueue { add(0, "Scout") } // ConstructionAutomation ignores the flag for an AI
        val disable = checks(pending).single { it.orderType == "disable_construction" }
        assertEquals(OrderChecker.IN_PLACE, disable.status)
        assertTrue(disable.detail!!.contains("retirée par le pont"))
        assertFalse("Scout" in city.cityConstructions.constructionQueue)

        city.cityConstructions.editQueue { clear(); add("Scout") }
        val alone = checks(pending).single { it.orderType == "disable_construction" }
        assertEquals(OrderChecker.OVERRIDDEN, alone.status)
        assertEquals("native_ai", alone.overriddenBy)
        assertEquals("the only item is never removed", listOf("Scout"), city.cityConstructions.constructionQueue)
    }

    @Test
    fun `noop is not checked and a later order of the same decree supersedes an earlier one`() {
        assertNull(OrderChecker.capture(us, OrdersApplier.apply(us, listOf(order("noop"))).applied))
        val legal = LegalOptions.build(us).techs.take(1)
        val pending = applyAndCapture(order("set_research_goal", "tech" to "Writing"), order("set_research_queue", "techs" to legal))
        val (goal, queue) = checks(pending)
        assertEquals(OrderChecker.OVERRIDDEN, goal.status)
        assertEquals("later_order", goal.overriddenBy)
        assertEquals(OrderChecker.IN_PLACE, queue.status)
    }

    @Test
    fun `order_check events carry the contract fields`() {
        val event = OrderCheck("O-1", "set_city_focus", "overridden", "native_ai", "détail").toEvent("America", 12)
        assertEquals(setOf("type", "civ_id", "turn", "order_id", "order_type", "status", "overridden_by", "detail"), event.keys)
        assertEquals("order_check", (event["type"] as JsonPrimitive).content)
        assertEquals(12, (event["turn"] as JsonPrimitive).content.toInt())
        val inPlace = OrderCheck("O-2", "adopt_policy", "in_place").toEvent("America", 12)
        assertFalse("overridden_by" in inPlace.keys)
        assertEquals(setOf("order_id", "status", "detail"), OrderCheck("O-2", "adopt_policy", "in_place").toActiveStatus().keys)
    }

    @Test
    fun `orders never checked at H4 are reported as not checked`() {
        val pending = applyAndCapture(order("adopt_policy", "policy" to "Tradition").also { us.policies.storedCulture = 1000 })
        val result = OrderChecker.notChecked(pending, "fin de tour non atteinte").single()
        assertEquals(OrderChecker.NOT_CHECKED, result.status)
    }
}
