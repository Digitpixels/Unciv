package com.unciv.iaciv

import com.unciv.iaciv.dto.OrderResultDto
import com.unciv.iaciv.dto.iaCivJson
import com.unciv.iaciv.observe.LegalOptions
import com.unciv.iaciv.orders.OrdersApplier
import com.unciv.iaciv.orders.StandingOrders
import com.unciv.logic.city.City
import com.unciv.logic.city.CityFocus
import com.unciv.logic.civilization.Civilization
import com.unciv.models.stats.Stat
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
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

@RunWith(BaseTestRunner::class)
class OrdersApplierTest {
    private val testGame = TestGame().apply { makeHexagonalMap(8) }
    private lateinit var us: Civilization
    private lateinit var them: Civilization
    private lateinit var city: City
    private lateinit var theirCity: City
    private var counter = 0

    @Before
    fun setUp() {
        us = testGame.addCiv(testGame.ruleset.nations["America"]!!)
        them = testGame.addCiv(testGame.ruleset.nations["France"]!!)
        city = testGame.addCity(us, testGame.getTile(-4, -4))
        theirCity = testGame.addCity(them, testGame.getTile(4, 4))
    }

    private fun order(type: String?, vararg fields: Pair<String, Any?>): JsonObject = buildJsonObject {
        put("order_id", "O-${++counter}")
        if (type != null) put("type", type)
        for ((key, value) in fields) when (value) {
            is String -> put(key, value)
            is Number -> put(key, value)
            is List<*> -> put(key, JsonArray(value.map { JsonPrimitive(it as String) }))
            null -> put(key, JsonNull)
        }
    }

    private fun apply(vararg orders: JsonObject) = OrdersApplier.apply(us, orders.toList())
    private fun apply1(order: JsonObject): OrderResultDto = apply(order).results.single()
    private fun queueOrder(mode: String, vararg items: String, cityId: String = city.id) =
        order("set_production_queue", "city_id" to cityId, "mode" to mode, "items" to items.toList())
    private fun queue() = city.cityConstructions.constructionQueue

    private fun assertRejected(reason: String, result: OrderResultDto) {
        assertEquals("status of ${result.orderId} (${result.reason})", "rejected", result.status)
        assertEquals(reason, result.reason)
    }

    private fun assertApplied(result: OrderResultDto) =
        assertEquals("${result.orderId} : ${result.reason} ${result.detail}", "applied", result.status)

    // region science

    @Test
    fun `research goal queues the path to the tech, prerequisites first`() {
        assertApplied(apply1(order("set_research_goal", "tech" to "Writing")))
        val queue = us.tech.techsToResearch
        assertEquals("Writing", queue.last())
        val known = HashSet(us.tech.techsResearched)
        for (name in queue) {
            assertTrue("$name queued before its prerequisites", testGame.ruleset.technologies[name]!!.prerequisites.all { it in known })
            known += name
        }
    }

    @Test
    fun `invalid research goals are rejected and leave the queue untouched`() {
        val before = us.tech.techsToResearch.toList()
        assertRejected("unknown_tech", apply1(order("set_research_goal", "tech" to "Teleportation")))
        assertRejected("tech_already_researched", apply1(order("set_research_goal", "tech" to us.tech.techsResearched.first())))
        assertRejected("missing_field", apply1(order("set_research_goal")))
        assertRejected("missing_field", apply1(order("set_research_goal", "tech" to null)))
        assertRejected("invalid_field", apply1(order("set_research_goal", "tech" to 42)))
        assertEquals(before, us.tech.techsToResearch)
    }

    @Test
    fun `research queue must follow the prerequisites`() {
        val legal = LegalOptions.build(us).techs.take(2)
        assertRejected("prerequisite_gap", apply1(order("set_research_queue", "techs" to listOf("Rocketry"))))
        assertRejected("duplicate_tech", apply1(order("set_research_queue", "techs" to listOf(legal[0], legal[0]))))
        assertRejected("empty_queue", apply1(order("set_research_queue", "techs" to listOf<String>())))
        assertRejected("invalid_field", apply1(order("set_research_queue", "techs" to 3)))
        assertRejected("unknown_tech", apply1(order("set_research_queue", "techs" to listOf(legal[0], "Teleportation"))))
        assertTrue("an atomic rejection leaves the queue as it was", us.tech.techsToResearch.isEmpty())
        assertApplied(apply1(order("set_research_queue", "techs" to legal)))
        assertEquals(legal, us.tech.techsToResearch)
    }

    @Test
    fun `free tech needs a free tech and a researchable tech`() {
        val tech = LegalOptions.build(us).techs.first()
        assertRejected("no_free_tech", apply1(order("choose_free_tech", "tech" to tech)))
        us.tech.freeTechs = 1
        assertRejected("prerequisite_gap", apply1(order("choose_free_tech", "tech" to "Rocketry")))
        assertRejected("unknown_tech", apply1(order("choose_free_tech", "tech" to "Teleportation")))
        assertEquals(1, us.tech.freeTechs)
        assertApplied(apply1(order("choose_free_tech", "tech" to tech)))
        assertTrue(us.tech.isResearched(tech))
        assertEquals(0, us.tech.freeTechs)
    }

    @Test
    fun `free tech is applied before the research goal of the same decree`() {
        val path = us.tech.getRequiredTechsToDestination(testGame.ruleset.technologies["Writing"]!!).map { it.name }
        assertTrue("Writing needs at least one prerequisite", path.size >= 2)
        us.tech.freeTechs = 1
        val goal = order("set_research_goal", "tech" to "Writing")
        val free = order("choose_free_tech", "tech" to path.first())
        val results = apply(goal, free).results
        assertEquals("results come back in the order received", listOf(goal["order_id"], free["order_id"]).map { (it as JsonPrimitive).content },
            results.map { it.orderId })
        assertEquals(listOf("applied", "applied"), results.map { it.status })
        assertEquals(path.drop(1), us.tech.techsToResearch)
    }

    // endregion
    // region policies

    @Test
    fun `policies need culture and their branch`() {
        assertRejected("unknown_policy", apply1(order("adopt_policy", "policy" to "Politique imaginaire")))
        assertRejected("policy_not_adoptable", apply1(order("adopt_policy", "policy" to "Aristocracy")))
        us.policies.storedCulture = 0
        assertRejected("not_enough_culture", apply1(order("adopt_policy", "policy" to "Tradition")))
        us.policies.storedCulture = 1000
        assertApplied(apply1(order("adopt_policy", "policy" to "Tradition")))
        assertTrue(us.policies.isAdopted("Tradition"))
        assertRejected("policy_already_adopted", apply1(order("adopt_policy", "policy" to "Tradition")))
    }

    // endregion
    // region production

    @Test
    fun `append is applied item by item`() {
        assertApplied(apply1(queueOrder("replace", "Warrior")))
        assertEquals(listOf("Warrior"), queue())
        val result = apply1(queueOrder("append", "Monument", "Batiment imaginaire", "Apollo Program"))
        assertEquals("partial", result.status)
        val items = result.itemResults!!
        assertEquals(listOf("Monument", "Batiment imaginaire", "Apollo Program"), items.map { it.item })
        assertEquals(listOf("applied", "rejected", "rejected"), items.map { it.status })
        assertEquals(listOf(null, "unknown_construction", "cannot_build"), items.map { it.reason })
        assertTrue("cannot_build says why", items[2].detail!!.isNotBlank())
        assertEquals(listOf("Warrior", "Monument"), queue())
    }

    @Test
    fun `replace and prepend give the exact queue`() {
        assertApplied(apply1(queueOrder("replace", "Warrior", "Monument")))
        assertEquals(listOf("Warrior", "Monument"), queue())
        val prepend = apply1(queueOrder("prepend", "Scout", "Monument"))
        assertApplied(prepend)
        assertEquals(listOf("Scout", "Monument", "Warrior"), queue())
        assertEquals("déplacé en tête", prepend.itemResults!![1].detail)
        assertApplied(apply1(queueOrder("replace", "Worker")))
        assertEquals(listOf("Worker"), queue())
        assertTrue(city.cityConstructions.currentConstructionIsUserSet)
    }

    @Test
    fun `a full queue rejects the extra items`() {
        assertApplied(apply1(queueOrder("replace", "Warrior")))
        assertApplied(apply1(queueOrder("append", *Array(9) { "Warrior" })))
        assertEquals(10, queue().size)
        val append = apply1(queueOrder("append", "Warrior"))
        assertRejected("queue_full", append)
        assertEquals("queue_full", append.itemResults!!.single().reason)
        assertRejected("queue_full", apply1(queueOrder("prepend", "Scout")))
        assertEquals(10, queue().size)
    }

    @Test
    fun `invalid production orders are rejected and leave the queue untouched`() {
        assertApplied(apply1(queueOrder("replace", "Warrior")))
        assertRejected("perpetual_not_allowed", apply1(queueOrder("replace", "Nothing")))
        assertRejected("invalid_mode", apply1(queueOrder("insert", "Monument")))
        assertRejected("empty_items", apply1(queueOrder("append")))
        assertRejected("unknown_city", apply1(queueOrder("append", "Monument", cityId = "ville-inexistante-0000")))
        assertRejected("not_our_city", apply1(queueOrder("append", "Monument", cityId = theirCity.id)))
        assertRejected("missing_field", apply1(order("set_production_queue", "city_id" to city.id, "items" to listOf("Monument"))))
        assertEquals(listOf("Warrior"), queue())
        val duplicate = apply1(queueOrder("replace", "Monument", "Monument"))
        assertEquals("partial", duplicate.status) // the first Monument is applied
        assertEquals("already_in_queue", duplicate.itemResults!![1].reason)
        assertEquals(listOf("Monument"), queue())
    }

    // endregion
    // region focus, purchase, toggles

    @Test
    fun `city focus is set and invalid focus orders are rejected`() {
        assertApplied(apply1(order("set_city_focus", "city_id" to city.id, "focus" to "GoldFocus")))
        assertEquals(CityFocus.GoldFocus, city.getCityFocus())
        assertRejected("unknown_focus", apply1(order("set_city_focus", "city_id" to city.id, "focus" to "FocusInexistant")))
        assertRejected("focus_not_supported", apply1(order("set_city_focus", "city_id" to city.id, "focus" to "Manual")))
        assertRejected("missing_field", apply1(order("set_city_focus", "city_id" to city.id)))
        assertRejected("unknown_city", apply1(order("set_city_focus", "city_id" to "ville-inexistante-0000", "focus" to "ProductionFocus")))
        assertEquals(CityFocus.GoldFocus, city.getCityFocus())
    }

    @Test
    fun `purchase checks the treasury and pays the price`() {
        fun buy(name: String, stat: String = "Gold") = apply1(order("purchase", "city_id" to city.id, "construction" to name, "stat" to stat))
        assertApplied(apply1(queueOrder("replace", "Monument"))) // a purchase never leaves the queue empty
        val cost = testGame.ruleset.units["Warrior"]!!.getStatBuyCost(city, Stat.Gold)!!
        us.addGold(-us.gold)
        assertRejected("not_enough_gold", buy("Warrior"))
        us.addGold(cost + 100)
        val unitsBefore = us.units.getCivUnits().count()
        assertApplied(buy("Warrior"))
        assertEquals(100, us.gold)
        assertEquals(unitsBefore + 1, us.units.getCivUnits().count())

        us.addGold(1000)
        assertRejected("no_space_for_unit", buy("Warrior")) // the bought Warrior stands on the city center
        assertRejected("not_purchasable", buy("Worker", "Faith"))
        assertRejected("unknown_stat", buy("Worker", "Culture"))
        assertRejected("unknown_construction", buy("Batiment imaginaire"))
        assertRejected("not_purchasable", buy("Gold"))
        assertRejected("unknown_city", apply1(order("purchase", "city_id" to "ville-inexistante-0000", "construction" to "Warrior", "stat" to "Gold")))
    }

    @Test
    fun `buying a queued item removes its entry`() {
        assertApplied(apply1(queueOrder("replace", "Monument", "Warrior")))
        us.addGold(1000)
        assertApplied(apply1(order("purchase", "city_id" to city.id, "construction" to "Monument", "stat" to "Gold")))
        assertTrue(city.cityConstructions.isBuilt("Monument"))
        assertEquals(listOf("Warrior"), queue())
    }

    @Test
    fun `disable and enable construction set the game's own flags`() {
        assertApplied(apply1(order("disable_construction", "name" to "Scout")))
        assertTrue("Scout" in us.disabledCityConstructions)
        assertTrue("Scout" in city.disabledConstructions)
        assertRejected("construction_disabled", apply1(queueOrder("append", "Scout")))
        assertFalse("a disabled construction is not a legal option", "Scout" in LegalOptions.build(us).perCityConstructions[city.id]!!)
        assertRejected("unknown_construction", apply1(order("disable_construction", "name" to "Gold")))
        assertRejected("missing_field", apply1(order("disable_construction")))
        assertApplied(apply1(order("enable_construction", "name" to "Scout")))
        assertFalse("Scout" in us.disabledCityConstructions)
        assertFalse("Scout" in city.disabledConstructions)
        assertTrue("Scout" in LegalOptions.build(us).perCityConstructions[city.id]!!)
    }

    @Test
    fun `the bridge removes disabled constructions from the queues but never empties one`() {
        assertApplied(apply1(queueOrder("replace", "Scout", "Warrior")))
        assertApplied(apply1(order("disable_construction", "name" to "Scout")))
        val enforcement = StandingOrders.enforceDisabled(us)
        assertEquals(listOf("Warrior"), queue())
        assertEquals(listOf(city.name), enforcement.citiesWhereRemoved("Scout"))

        city.cityConstructions.editQueue { clear(); add("Scout") } // the native AI queues it again, alone
        val alone = StandingOrders.enforceDisabled(us)
        assertEquals(listOf("Scout"), queue())
        assertEquals(listOf(city.name), alone.citiesWhereKept("Scout"))
    }

    @Test
    fun `legal options never offer Nothing`() {
        assertFalse("Nothing" in LegalOptions.build(us).perCityConstructions[city.id]!!)
    }

    // endregion
    // region robustness

    @Test
    fun `unknown, incomplete and later orders never throw`() {
        val noId = buildJsonObject { put("type", "noop") }
        val results = apply(
            order("teleport_capital", "target" to "capital"),
            order(null),
            order("set_posture", "posture" to "defensive"), // M3b
            noId,
            order("set_research_goal", "tech" to "Writing"),
        ).results
        assertRejected("unknown_order_type", results[0])
        assertRejected("missing_field", results[1])
        assertEquals("ignored", results[2].status)
        assertEquals("not_implemented", results[2].reason)
        assertEquals("O-inconnu-3", results[3].orderId)
        assertApplied(results[3])
        assertApplied(results[4])
    }

    @Test
    fun `results go on the wire in snake_case with item results`() {
        val result = apply1(queueOrder("replace", "Warrior", "Batiment imaginaire"))
        val json = iaCivJson.encodeToString(OrderResultDto.serializer(), result)
        assertTrue(json.contains("\"order_id\""))
        assertTrue(json.contains("\"item_results\""))
        assertTrue(json.contains("\"unknown_construction\""))
        val plain = iaCivJson.encodeToString(OrderResultDto.serializer(), apply1(order("noop")))
        assertTrue(plain.contains("\"item_results\":null"))
        assertNull(apply1(order("noop")).itemResults)
    }

    // endregion
}
