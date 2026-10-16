package com.unciv.iaciv.orders

import com.unciv.logic.city.CityFocus
import com.unciv.logic.civilization.Civilization
import com.unciv.models.ruleset.PerpetualConstruction
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * What the bridge keeps enforcing turn after turn for an LLM civ, because the native AI undoes it:
 * - the city focus: `CityTurnManager.startTurn` resets it for every AI city at each turn start (before H1), so the last
 *   ordered focus is re-applied at each H1 until a new `set_city_focus` for that city. JVM memory only (identifiers,
 *   never game objects): lost if the game process restarts (the orchestrator may send it again);
 * - the disabled constructions: `ConstructionAutomation` honours `disabledConstructions` for humans only, so the bridge
 *   removes them from the queues at H1 and H4 ([enforceDisabled]). The flag itself is saved with the game.
 */
class StandingOrders {

    data class StandingFocus(val orderId: String, val focus: String, val sinceTurn: Int)

    /** key `civID|cityId` */
    private val focusByCity = LinkedHashMap<String, StandingFocus>()

    private fun key(civId: String, cityId: String) = "$civId|$cityId"

    fun recordFocus(civId: String, cityId: String, orderId: String, focus: String, turn: Int) {
        focusByCity[key(civId, cityId)] = StandingFocus(orderId, focus, turn)
    }

    fun standingFocus(civId: String, cityId: String): StandingFocus? = focusByCity[key(civId, cityId)]

    /** Re-applies the standing focus of each city of [civ]; returns `"<city> : <focus>"` for each city changed */
    fun reapplyFocus(civ: Civilization): List<String> {
        val prefix = "${civ.civID}|"
        val ours = civ.cities.associateBy { it.id }
        val changed = ArrayList<String>()
        val entries = focusByCity.entries.iterator()
        while (entries.hasNext()) {
            val (cityKey, standing) = entries.next()
            if (!cityKey.startsWith(prefix)) continue
            val city = ours[cityKey.removePrefix(prefix)]
            if (city == null) { // city lost or razed: the order is moot
                entries.remove()
                continue
            }
            if (city.isPuppet) continue // the game forces Gold focus on puppets
            val focus = CityFocus.entries.firstOrNull { it.name == standing.focus } ?: continue
            if (city.getCityFocus() == focus) continue
            city.setCityFocus(focus)
            city.reassignPopulation()
            changed += "${city.name} : ${focus.name}"
        }
        return changed
    }

    /** For `active_orders_status`: one entry per standing focus of [civ] */
    fun statuses(civ: Civilization): List<JsonObject> {
        val names = civ.cities.associate { it.id to it.name }
        val prefix = "${civ.civID}|"
        return focusByCity.filterKeys { it.startsWith(prefix) }.map { (cityKey, standing) ->
            val cityId = cityKey.removePrefix(prefix)
            buildJsonObject {
                put("order_id", standing.orderId)
                put("status", "standing")
                put("detail", "focus ${standing.focus} de ${names[cityId] ?: cityId} (ordonné au tour ${standing.sinceTurn}), " +
                    "réappliqué par le pont à chaque tour jusqu'à un nouvel ordre")
            }
        }
    }

    fun clear() = focusByCity.clear()

    /** Result of [enforceDisabled], keyed by city name */
    class DisabledEnforcement(
        /** Disabled constructions removed from each city's queue */
        val removed: Map<String, List<String>>,
        /** Left in place because removing them would have emptied the queue */
        val kept: Map<String, List<String>>,
    ) {
        fun isEmpty() = removed.isEmpty() && kept.isEmpty()
        fun citiesWhereRemoved(name: String) = removed.filterValues { name in it }.keys.toList()
        fun citiesWhereKept(name: String) = kept.filterValues { name in it }.keys.toList()
    }

    companion object {
        private val IDLE = PerpetualConstruction.Idle.name

        /**
         * Removes the civ's disabled constructions from its cities' queues, never leaving a queue empty (then the
         * entries stay and are reported in [DisabledEnforcement.kept]). Also copies the civ list into cities founded
         * since (`City.resetDisabledConstructions` copies it for humans only).
         */
        fun enforceDisabled(civ: Civilization): DisabledEnforcement {
            val disabled = civ.disabledCityConstructions
            if (disabled.isEmpty()) return DisabledEnforcement(mapOf(), mapOf())
            val removed = LinkedHashMap<String, List<String>>()
            val kept = LinkedHashMap<String, List<String>>()
            for (city in civ.cities) {
                city.disabledConstructions.addAll(disabled)
                val cc = city.cityConstructions
                val offending = cc.constructionQueue.filter { it in disabled }.distinct()
                if (offending.isEmpty()) continue
                if (cc.constructionQueue.all { it in disabled || it == IDLE }) {
                    kept[city.name] = offending
                    continue
                }
                val headBefore = cc.currentConstructionName()
                val userSet = cc.currentConstructionIsUserSet // removeFromQueue sets it: keep the AI's own flag
                for (name in offending) {
                    while (true) {
                        val index = cc.constructionQueue.indexOf(name)
                        if (index < 0) break
                        cc.removeFromQueue(index, automatic = false)
                    }
                }
                cc.currentConstructionIsUserSet = userSet
                if (cc.currentConstructionName() != headBefore) city.cityStats.update() // production bonus depends on it
                removed[city.name] = offending
            }
            return DisabledEnforcement(removed, kept)
        }
    }
}
