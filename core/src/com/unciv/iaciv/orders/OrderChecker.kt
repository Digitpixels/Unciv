package com.unciv.iaciv.orders

import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.ui.screens.victoryscreen.RankingType
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** One `order_check` event (POST /v1/events): is an order applied at H1 still in place at the end of the civ's turn? */
data class OrderCheck(
    val orderId: String,
    val orderType: String,
    /** in_place | completed | overridden | not_checked */
    val status: String,
    val overriddenBy: String? = null,
    val detail: String? = null,
) {
    /** [turn] = the turn the order was applied */
    fun toEvent(civId: String, turn: Int): JsonObject = buildJsonObject {
        put("type", "order_check")
        put("civ_id", civId)
        put("turn", turn)
        put("order_id", orderId)
        put("order_type", orderType)
        put("status", status)
        if (overriddenBy != null) put("overridden_by", overriddenBy)
        if (detail != null) put("detail", detail)
    }

    /** Entry of the next observation's `active_orders_status` */
    fun toActiveStatus(): JsonObject = buildJsonObject {
        put("order_id", orderId)
        put("status", status)
        put("detail", listOfNotNull(overriddenBy?.let { "défait par $it" }, detail).joinToString(" : ").ifEmpty { null })
    }
}

/**
 * The state right after the orders were applied at H1 (identifiers and names only), compared at H4: every difference
 * then comes from the native AI's window (`automateCivMoves` after H1), and what took the place tells which path it was.
 */
class PendingCheck(
    val gameId: String,
    val turn: Int,
    val civId: String,
    val orders: List<AppliedOrder>,
    /** cityId -> production queue right after application */
    val queues: Map<String, List<String>>,
    val unitIds: Set<Int>,
    /** orderId -> (item index -> result) for production items already gone from the queue right after application */
    val goneItems: Map<String, Map<Int, OrderChecker.ItemCheck>>,
)

/** H4 measure of the M2 orders (option B of the research note: last line of `automateCivMoves`) */
object OrderChecker {
    const val IN_PLACE = "in_place"
    const val COMPLETED = "completed"
    const val OVERRIDDEN = "overridden"
    const val NOT_CHECKED = "not_checked"

    data class ItemCheck(val item: String, val status: String, val overriddenBy: String? = null, val detail: String? = null)

    class Verification(val checks: List<OrderCheck>, val enforcement: StandingOrders.DisabledEnforcement)

    /** Captures the state to compare at H4; null if there is nothing to check (no order, or only `noop`) */
    fun capture(civ: Civilization, applied: List<AppliedOrder>): PendingCheck? {
        val orders = applied.filter { it !is AppliedOrder.Noop }
        if (orders.isEmpty()) return null
        val queues = civ.cities.associate { it.id to it.cityConstructions.constructionQueue.toList() }
        val purchased = orders.filterIsInstance<AppliedOrder.Purchase>().map { it.cityId to it.construction }
        val gone = HashMap<String, Map<Int, ItemCheck>>()
        for (order in orders.filterIsInstance<AppliedOrder.Queue>()) {
            val left = (queues[order.cityId] ?: listOf()).toMutableList()
            val city = civ.cities.firstOrNull { it.id == order.cityId }
            val goneHere = HashMap<Int, ItemCheck>()
            for ((index, name) in order.items.withIndex()) {
                if (left.remove(name)) continue
                goneHere[index] = when {
                    (order.cityId to name) in purchased -> ItemCheck(name, COMPLETED, detail = "$name acheté par un ordre du même décret")
                    city != null && city.cityConstructions.isBuilt(name) -> ItemCheck(name, COMPLETED, detail = "$name construit pendant l'application")
                    else -> ItemCheck(name, NOT_CHECKED, detail = "$name absent de la file juste après l'application")
                }
            }
            if (goneHere.isNotEmpty()) gone[order.orderId] = goneHere
        }
        return PendingCheck(civ.gameInfo.gameId, civ.gameInfo.turns, civ.civID, orders, queues,
            civ.units.getCivUnits().map { it.id }.toSet(), gone)
    }

    /**
     * Checks every pending order at H4. The production checks run BEFORE [enforceDisabled] (they must see what the
     * native AI put on top), the construction toggles AFTER it (a disabled item re-queued by the AI and removed by the
     * bridge is in place). Results come in the order the orders were applied.
     */
    fun verify(
        civ: Civilization,
        pending: PendingCheck,
        enforceDisabled: (Civilization) -> StandingOrders.DisabledEnforcement = StandingOrders::enforceDisabled,
    ): Verification {
        val newUnits = civ.units.getCivUnits().filter { it.id !in pending.unitIds }.map { it.baseUnit.name }.toMutableList()
        val checks = HashMap<AppliedOrder, OrderCheck>()
        for (order in pending.orders) {
            if (order is AppliedOrder.Toggle) continue
            checks[order] = checkOne(civ, pending, order, newUnits)
        }
        val enforcement = enforceDisabled(civ)
        for (order in pending.orders.filterIsInstance<AppliedOrder.Toggle>())
            checks[order] = superseded(order) ?: checkToggle(civ, order, enforcement)
        return Verification(pending.orders.map { checks.getValue(it) }, enforcement)
    }

    /** H4 never reached for these orders (e.g. the native AI threw, or another game was loaded) */
    fun notChecked(pending: PendingCheck, detail: String): List<OrderCheck> =
        pending.orders.map { OrderCheck(it.orderId, it.orderType, NOT_CHECKED, detail = detail) }

    private fun superseded(order: AppliedOrder): OrderCheck? = order.supersededBy?.let {
        OrderCheck(order.orderId, order.orderType, OVERRIDDEN, "later_order", "remplacé par l'ordre $it du même décret")
    }

    private fun checkOne(civ: Civilization, pending: PendingCheck, order: AppliedOrder, newUnits: MutableList<String>): OrderCheck {
        superseded(order)?.let { return it }
        fun result(status: String, detail: String? = null, by: String? = null) = OrderCheck(order.orderId, order.orderType, status, by, detail)
        val tm = civ.tech
        return when (order) {
            is AppliedOrder.Research -> {
                val left = order.techs.filter { !tm.isResearched(it) }
                when {
                    left.isEmpty() -> result(COMPLETED, "toutes les technos de la file sont recherchées")
                    tm.techsToResearch.take(left.size) == left -> result(IN_PLACE, "en cours : ${left.first()}")
                    else -> result(OVERRIDDEN, "file attendue ${left.joinToString(", ")} ; file actuelle ${tm.techsToResearch.joinToString(", ")}", "native_ai")
                }
            }
            is AppliedOrder.FreeTech ->
                if (tm.isResearched(order.tech)) result(COMPLETED, "${order.tech} recherchée")
                else result(OVERRIDDEN, "${order.tech} n'est plus recherchée", "native_ai")
            is AppliedOrder.Policy ->
                if (civ.policies.isAdopted(order.policy)) result(IN_PLACE, "${order.policy} adoptée")
                else result(OVERRIDDEN, "${order.policy} n'est plus adoptée", "native_ai")
            is AppliedOrder.Purchase -> result(COMPLETED, "${order.construction} acheté (effet immédiat)")
            is AppliedOrder.Focus -> {
                val city = civ.cities.firstOrNull { it.id == order.cityId }
                when {
                    city == null -> result(NOT_CHECKED, "ville perdue pendant le tour")
                    city.getCityFocus().name == order.focus -> result(IN_PLACE, "${city.name} : ${order.focus}")
                    else -> result(OVERRIDDEN, "${city.name} : focus ${city.getCityFocus().name} au lieu de ${order.focus}", "native_ai")
                }
            }
            is AppliedOrder.Queue -> checkQueue(civ, pending, order, newUnits)
            is AppliedOrder.Toggle, is AppliedOrder.Noop -> result(NOT_CHECKED) // not reached: handled by verify / capture
        }
    }

    private fun checkQueue(civ: Civilization, pending: PendingCheck, order: AppliedOrder.Queue, newUnits: MutableList<String>): OrderCheck {
        val city = civ.cities.firstOrNull { it.id == order.cityId }
            ?: return OrderCheck(order.orderId, order.orderType, NOT_CHECKED, detail = "ville perdue pendant le tour")
        val cc = city.cityConstructions
        val before = pending.queues[order.cityId] ?: listOf()
        val after = cc.constructionQueue
        val leftAfter = after.toMutableList() // consumed one by one: several units of the same kind are counted
        val gone = pending.goneItems[order.orderId] ?: mapOf()
        val items = order.items.mapIndexed { index, name ->
            gone[index]?.let { return@mapIndexed it }
            when {
                leftAfter.remove(name) -> ItemCheck(name, IN_PLACE) // even if shifted (wonder inserted on top by a Great Engineer)
                civ.gameInfo.ruleset.buildings.containsKey(name) && cc.isBuilt(name) ->
                    ItemCheck(name, COMPLETED, detail = "$name construit pendant le tour (achat de l'IA native)")
                newUnits.remove(name) -> ItemCheck(name, COMPLETED, detail = "$name acheté pendant le tour par l'IA native")
                before.firstOrNull() == name && after.firstOrNull() != name -> headReplaced(civ, city, name, after.firstOrNull())
                else -> ItemCheck(name, OVERRIDDEN, "native_ai", "$name a disparu de la file de ${city.name}")
            }
        }
        val overridden = items.firstOrNull { it.status == OVERRIDDEN }
        val status = when {
            overridden != null -> OVERRIDDEN
            items.all { it.status == COMPLETED } -> COMPLETED
            items.all { it.status == NOT_CHECKED } -> NOT_CHECKED
            else -> IN_PLACE
        }
        val detail = items.joinToString(" ; ") { it.detail ?: "${it.item} : ${it.status}" } + " (file de ${city.name} : ${after.joinToString(", ")})"
        return OrderCheck(order.orderId, order.orderType, status, overridden?.overriddenBy, detail)
    }

    /** Our item was on top and something else took its place: the kind of the new head names the native path */
    private fun headReplaced(civ: Civilization, city: City, name: String, head: String?): ItemCheck {
        val unit = head?.let { civ.gameInfo.ruleset.units[it] }
        return when {
            unit != null && unit.isCityFounder() ->
                ItemCheck(name, OVERRIDDEN, "native_settler", "$name remplacé en tête par $head (colon imposé par l'IA native, trainSettler)")
            unit != null && unit.isMilitary -> {
                val enemies = civ.gameInfo.civilizations.filter { it != civ && !it.isBarbarian && civ.isAtWarWith(it) }
                    .sumOf { it.getStatForRanking(RankingType.Force) }
                ItemCheck(name, OVERRIDDEN, "native_defense", "$name remplacé en tête par $head (réflexe défensif ; ville " +
                    "${city.health}/${city.getMaxHealth()} PV, force ${civ.getStatForRanking(RankingType.Force)} contre $enemies en guerre)")
            }
            else -> ItemCheck(name, OVERRIDDEN, "native_ai", "$name remplacé en tête par ${head ?: "rien"}")
        }
    }

    private fun checkToggle(civ: Civilization, order: AppliedOrder.Toggle, enforcement: StandingOrders.DisabledEnforcement): OrderCheck {
        fun result(status: String, detail: String, by: String? = null) = OrderCheck(order.orderId, order.orderType, status, by, detail)
        val flagged = order.name in civ.disabledCityConstructions
        if (!order.disable)
            return if (!flagged) result(IN_PLACE, "${order.name} autorisée") else result(OVERRIDDEN, "${order.name} de nouveau désactivée", "native_ai")
        if (!flagged) return result(OVERRIDDEN, "drapeau de désactivation de ${order.name} levé", "native_ai")
        val kept = enforcement.citiesWhereKept(order.name)
        if (kept.isNotEmpty())
            return result(OVERRIDDEN, "${order.name} en file à ${kept.joinToString(", ")} (seule construction, laissée par le pont)", "native_ai")
        val removed = enforcement.citiesWhereRemoved(order.name)
        return if (removed.isEmpty()) result(IN_PLACE, "${order.name} absente de toutes les files")
        else result(IN_PLACE, "${order.name} remise en file par l'IA native à ${removed.joinToString(", ")}, retirée par le pont")
    }
}
