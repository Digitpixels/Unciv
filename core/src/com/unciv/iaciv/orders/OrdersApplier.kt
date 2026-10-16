package com.unciv.iaciv.orders

import com.unciv.iaciv.diplomacy.DiplomacyContext
import com.unciv.iaciv.dto.ItemResultDto
import com.unciv.iaciv.dto.OrderResultDto
import com.unciv.logic.city.City
import com.unciv.logic.city.CityConstructions
import com.unciv.logic.city.CityFocus
import com.unciv.logic.civilization.Civilization
import com.unciv.models.ruleset.Building
import com.unciv.models.ruleset.IConstruction
import com.unciv.models.ruleset.INonPerpetualConstruction
import com.unciv.models.ruleset.PerpetualConstruction
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.ruleset.unit.BaseUnit
import com.unciv.models.stats.Stat
import com.unciv.utils.Log
import kotlinx.serialization.json.JsonObject

/**
 * Applies the M2 orders (contract §5.3) through Unciv's managers, never through the views' `try*` methods (they
 * require `isCurrentPlayer()`, always false for an AI). Most game functions validate nothing and throw on bad input,
 * so every order is fully validated first; each order runs in its own try/catch and a failure only rejects that order.
 * Never throws. Results come back in the order received; orders are applied by phase (see [PHASES]).
 * M3: the diplomatic orders are applied by [DiplomacyOrders].
 */
object OrdersApplier {

    /** [nativeIds] = request and alert ids the orchestrator left to the native AI (`decision: "native"`, contract M3 §2) */
    class Outcome(val results: List<OrderResultDto>, val applied: List<AppliedOrder>, val nativeIds: Set<String> = setOf())

    /** Application order: a free tech may unlock a prerequisite, a policy may unlock buildings, the queue before purchases… */
    private val PHASES = mapOf(
        "choose_free_tech" to 0, "adopt_policy" to 1, "set_research_goal" to 2, "set_research_queue" to 2,
        "disable_construction" to 3, "enable_construction" to 3, "set_production_queue" to 4,
        "purchase" to 5, "set_city_focus" to 6, "noop" to 7,
        // M3: answers first (a war declaration clears the target's requests), then denunciations and wars, then offers
        "respond_trade" to 8, "respond_friendship" to 8, "respond_demand" to 8,
        "denounce" to 9, "declare_war" to 9,
        "propose_peace" to 10, "offer_friendship" to 10, "propose_trade" to 10, "make_demand" to 10,
    )
    private const val UNKNOWN_PHASE = 99

    /** Order types of later milestones (contract §5.3): reported as `ignored` */
    private val LATER_TYPES = setOf("set_posture")
    private val QUEUE_MODES = setOf("replace", "prepend", "append")
    /** = CityConstructions.queueMaxSize (private upstream) */
    private const val QUEUE_MAX_SIZE = 10
    private val IDLE = PerpetualConstruction.Idle.name

    internal class Context(val civ: Civilization, val diplomacy: DiplomacyContext = DiplomacyContext()) {
        val applied = ArrayList<AppliedOrder>()
        val nativeIds = LinkedHashSet<String>()
        /** Cities whose queue changed: their stats are recomputed once at the end (production bonus depends on it) */
        val touched = LinkedHashSet<City>()

        fun record(order: AppliedOrder) {
            for (earlier in applied)
                if (earlier.supersededBy == null && earlier.isSupersededBy(order)) earlier.supersededBy = order.orderId
            applied += order
        }
    }

    fun apply(civ: Civilization, orders: List<JsonObject>, diplomacy: DiplomacyContext = DiplomacyContext()): Outcome {
        val results = arrayOfNulls<OrderResultDto>(orders.size)
        val context = Context(civ, diplomacy)
        val sorted = orders.withIndex().sortedBy { PHASES[it.value.str("type")] ?: UNKNOWN_PHASE } // stable sort
        for ((index, order) in sorted) {
            val orderId = order.str("order_id") ?: "O-inconnu-$index"
            results[index] = try {
                applyOne(context, orderId, order)
            } catch (reject: OrderReject) {
                OrderResultDto(orderId, "rejected", reject.reason, reject.detail)
            } catch (e: Exception) {
                Log.error("[IA-Civ] order $orderId failed", e)
                OrderResultDto(orderId, "rejected", "exception", e.toString().take(200))
            }
        }
        for (city in context.touched) {
            try {
                city.cityStats.update()
            } catch (e: Exception) {
                Log.error("[IA-Civ] city stats update failed for ${city.name}", e)
            }
        }
        return Outcome(results.map { it!! }, context.applied.toList(), context.nativeIds.toSet())
    }

    private fun applyOne(context: Context, id: String, order: JsonObject): OrderResultDto {
        fun applied(detail: String?) = OrderResultDto(id, "applied", null, detail)
        return when (val type = order.reqStr("type")) {
            "noop" -> {
                context.record(AppliedOrder.Noop(id))
                applied("statu quo")
            }
            "set_research_goal" -> applied(setResearchGoal(context, id, order.reqStr("tech")))
            "set_research_queue" -> applied(setResearchQueue(context, id, order.reqStrList("techs")))
            "choose_free_tech" -> applied(chooseFreeTech(context, id, order.reqStr("tech")))
            "adopt_policy" -> applied(adoptPolicy(context, id, order.reqStr("policy")))
            "set_production_queue" -> setProductionQueue(context, id, order)
            "set_city_focus" -> applied(setCityFocus(context, id, order))
            "purchase" -> applied(purchase(context, id, order))
            "disable_construction" -> applied(toggleConstruction(context, id, order.reqStr("name"), disable = true))
            "enable_construction" -> applied(toggleConstruction(context, id, order.reqStr("name"), disable = false))
            in DiplomacyOrders.TYPES -> DiplomacyOrders.apply(context, id, type, order)
            in LATER_TYPES -> OrderResultDto(id, "ignored", "not_implemented", "ordre prévu pour un jalon ultérieur (M3b)")
            else -> throw OrderReject("unknown_order_type", type)
        }
    }

    // region science

    private fun setResearchGoal(context: Context, id: String, name: String): String {
        val tm = context.civ.tech
        val tech = context.civ.gameInfo.ruleset.technologies[name] ?: throw OrderReject("unknown_tech", name)
        if (tm.isResearched(name) && !tech.isContinuallyResearchable()) throw OrderReject("tech_already_researched", name)
        if (tm.isUnresearchable(tech)) throw OrderReject("tech_not_researchable", name)
        val path = tm.getRequiredTechsToDestination(tech).map { it.name } // prerequisites first, already researched excluded
        if (path.isEmpty()) throw OrderReject("tech_not_researchable", "aucun chemin vers $name")
        tm.techsToResearch = ArrayList(path)
        tm.updateResearchProgress() // may complete the new current tech at once with the overflow science
        context.record(AppliedOrder.Research(id, "set_research_goal", path))
        return "file de recherche : ${path.joinToString(", ")}"
    }

    /** Exact queue, atomic: `addScience` puts science on `techsToResearch[0]` without checking prerequisites */
    private fun setResearchQueue(context: Context, id: String, names: List<String>): String {
        if (names.isEmpty()) throw OrderReject("empty_queue")
        val tm = context.civ.tech
        val ruleset = context.civ.gameInfo.ruleset
        val known = HashSet(tm.techsResearched)
        val seen = HashSet<String>()
        for (name in names) {
            val tech = ruleset.technologies[name] ?: throw OrderReject("unknown_tech", name)
            val repeatable = tech.isContinuallyResearchable()
            if (!repeatable && tm.isResearched(name)) throw OrderReject("tech_already_researched", name)
            if (!repeatable && !seen.add(name)) throw OrderReject("duplicate_tech", name)
            if (tm.isUnresearchable(tech)) throw OrderReject("tech_not_researchable", name)
            val missing = tech.prerequisites.filter { it !in known }.sorted()
            if (missing.isNotEmpty()) throw OrderReject("prerequisite_gap", "$name exige ${missing.joinToString(", ")}")
            known += name
        }
        tm.techsToResearch = ArrayList(names)
        tm.updateResearchProgress()
        context.record(AppliedOrder.Research(id, "set_research_queue", names))
        return "file de recherche : ${names.joinToString(", ")}"
    }

    private fun chooseFreeTech(context: Context, id: String, name: String): String {
        val tm = context.civ.tech
        if (tm.freeTechs <= 0) throw OrderReject("no_free_tech")
        val tech = context.civ.gameInfo.ruleset.technologies[name] ?: throw OrderReject("unknown_tech", name)
        if (!tm.canBeResearched(name)) throw when {
            tm.isResearched(name) && !tech.isContinuallyResearchable() -> OrderReject("tech_already_researched", name)
            tm.isUnresearchable(tech) -> OrderReject("tech_not_researchable", name)
            else -> OrderReject("prerequisite_gap", "$name exige ${tech.prerequisites.filter { !tm.isResearched(it) }.sorted().joinToString(", ")}")
        }
        tm.getFreeTechnology(name) // addTechnology already calls updateResearchProgress
        context.record(AppliedOrder.FreeTech(id, name))
        return "$name obtenue gratuitement, reste ${tm.freeTechs} techno(s) gratuite(s)"
    }

    // endregion
    // region policies

    private fun adoptPolicy(context: Context, id: String, name: String): String {
        val policy = context.civ.gameInfo.ruleset.policies[name] ?: throw OrderReject("unknown_policy", name)
        val pm = context.civ.policies
        if (pm.isAdopted(name)) throw OrderReject("policy_already_adopted", name)
        if (!pm.isAdoptable(policy)) {
            val why = when {
                !pm.getAdoptedPolicies().containsAll(policy.requires ?: emptyList()) -> "exige ${policy.requires?.joinToString(", ")}"
                !pm.isAdoptable(policy, checkEra = false) -> "exclue par une autre branche ou indisponible"
                else -> "ère trop précoce (${policy.branch.era})"
            }
            throw OrderReject("policy_not_adoptable", "$name : $why")
        }
        val needed = pm.getCultureNeededForNextPolicy()
        if (pm.freePolicies == 0 && pm.storedCulture < needed) throw OrderReject("not_enough_culture", "culture ${pm.storedCulture}/$needed")
        if (!pm.canAdoptPolicy()) throw OrderReject("policy_not_adoptable", "$name : aucune politique adoptable maintenant")
        pm.adopt(policy) // also updates the stats of every city
        context.record(AppliedOrder.Policy(id, name))
        return "$name adoptée, culture restante ${pm.storedCulture}, politiques gratuites ${pm.freePolicies}"
    }

    // endregion
    // region cities

    private fun ourCity(civ: Civilization, cityId: String): City {
        civ.cities.firstOrNull { it.id == cityId }?.let { return it }
        if (civ.gameInfo.getCities().any { it.id == cityId }) throw OrderReject("not_our_city", cityId)
        throw OrderReject("unknown_city", cityId)
    }

    /** Same lookup order as `CityConstructions.getConstruction` (buildings, units, perpetual), without its exception */
    private fun resolve(civ: Civilization, name: String): IConstruction? {
        val ruleset = civ.gameInfo.ruleset
        return ruleset.buildings[name] ?: ruleset.units[name] ?: PerpetualConstruction.perpetualConstructionsMap[name]
    }

    private fun rejectionDetail(construction: IConstruction?, cc: CityConstructions): String? =
        (construction as? INonPerpetualConstruction)?.getRejectionReasons(cc)
            ?.map { it.errorMessage }?.distinct()?.take(3)?.toList()?.joinToString("; ")

    private fun setProductionQueue(context: Context, id: String, order: JsonObject): OrderResultDto {
        val cityId = order.reqStr("city_id")
        val mode = order.reqStr("mode")
        val items = order.reqStrList("items")
        val civ = context.civ
        val city = ourCity(civ, cityId)
        if (city.isPuppet) throw OrderReject("city_is_puppet", city.name)
        if (mode !in QUEUE_MODES) throw OrderReject("invalid_mode", mode)
        if (items.isEmpty()) throw OrderReject("empty_items")
        val cc = city.cityConstructions
        val slots = arrayOfNulls<ItemResultDto>(items.size)

        // 1. Validation of each item, independent of the current queue
        val valid = ArrayList<Pair<Int, IConstruction>>()
        val seenBuildings = HashSet<String>()
        var seenPerpetual = false
        for ((i, name) in items.withIndex()) {
            val construction = resolve(civ, name)
            val reason = when {
                construction == null -> "unknown_construction"
                construction === PerpetualConstruction.Idle -> "perpetual_not_allowed"
                // a perpetual construction on top would block the queue forever
                construction is PerpetualConstruction && (mode == "prepend" || seenPerpetual) -> "perpetual_not_allowed"
                name in civ.disabledCityConstructions -> "construction_disabled"
                construction is Building && construction.hasCreateOneImprovementUnique() -> "needs_tile"
                !construction.isBuildable(cc) -> "cannot_build"
                construction is Building && !seenBuildings.add(name) -> "already_in_queue"
                else -> null
            }
            if (reason == null) {
                valid += i to construction!!
                if (construction is PerpetualConstruction) seenPerpetual = true
            } else slots[i] = ItemResultDto(name, "rejected", reason, if (reason == "cannot_build") rejectionDetail(construction, cc) else null)
        }

        // 2. Application of the valid items (addToQueue fails silently: always pre-test to give the reason)
        fun addOne(i: Int, construction: IConstruction, toTop: Boolean) {
            val reason = when {
                construction is Building && cc.isBeingConstructedOrEnqueued(construction.name) -> "already_in_queue"
                cc.isQueueFull() -> "queue_full"
                !cc.canAddToQueue(construction) -> "cannot_build"
                else -> null
            }
            if (reason == null) cc.addToQueue(construction, addToTop = toTop) // sets currentConstructionIsUserSet
            slots[i] = ItemResultDto(items[i], if (reason == null) "applied" else "rejected", reason)
        }
        if (valid.isNotEmpty()) when (mode) {
            "replace" -> {
                // Not removeAll(): it calls chooseNextConstruction() and the native AI would put its choice on top
                while (cc.constructionQueue.isNotEmpty() && cc.constructionQueue != listOf(IDLE))
                    cc.removeFromQueue(0, automatic = false) // ends on ["Nothing"], replaced by the first item
                for ((i, construction) in valid) addOne(i, construction, toTop = false)
            }
            "append" -> for ((i, construction) in valid) addOne(i, construction, toTop = false)
            "prepend" -> {
                // capacity computed first, to keep the FIRST items and reject the last ones as queue_full
                var capacity = QUEUE_MAX_SIZE - cc.constructionQueue.count { it != IDLE }
                val kept = ArrayList<Pair<Int, IConstruction>>()
                for ((i, construction) in valid) {
                    val alreadyQueued = construction is Building && cc.isBeingConstructedOrEnqueued(construction.name)
                    when {
                        alreadyQueued -> kept += i to construction
                        capacity > 0 -> {
                            kept += i to construction
                            capacity--
                        }
                        else -> slots[i] = ItemResultDto(items[i], "rejected", "queue_full")
                    }
                }
                for ((i, construction) in kept.asReversed()) {
                    val index = cc.constructionQueue.indexOf(construction.name)
                    if (construction is Building && index >= 0) {
                        cc.moveEntryToTop(index)
                        slots[i] = ItemResultDto(items[i], "applied", null, if (index == 0) "déjà en tête" else "déplacé en tête")
                    } else addOne(i, construction, toTop = true)
                }
            }
        }

        val itemResults = slots.map { it!! }
        val appliedItems = itemResults.filter { it.status == "applied" }.map { it.item }
        if (appliedItems.isNotEmpty()) {
            context.touched += city
            context.record(AppliedOrder.Queue(id, city.id, mode, appliedItems))
        }
        val queue = "file de ${city.name} : ${cc.constructionQueue.joinToString(", ")}"
        return when (appliedItems.size) {
            itemResults.size -> OrderResultDto(id, "applied", null, queue, itemResults)
            0 -> OrderResultDto(id, "rejected", itemResults.first().reason, itemResults.first().detail, itemResults)
            else -> OrderResultDto(id, "partial", null, queue, itemResults)
        }
    }

    private fun setCityFocus(context: Context, id: String, order: JsonObject): String {
        val cityId = order.reqStr("city_id")
        val name = order.reqStr("focus")
        val city = ourCity(context.civ, cityId)
        val focus = CityFocus.entries.firstOrNull { it.name == name } ?: throw OrderReject("unknown_focus", name)
        // Manual only freezes the worked tiles of an AI city, and is replaced at the next turn start
        if (focus == CityFocus.Manual) throw OrderReject("focus_not_supported", name)
        if (city.isPuppet) throw OrderReject("city_is_puppet", "${city.name} : focus Or imposé aux villes fantoches")
        city.setCityFocus(focus)
        city.reassignPopulation() // = CityView.trySetCityFocus; includes cityStats.update()
        context.record(AppliedOrder.Focus(id, city.id, focus.name))
        return "${city.name} : ${focus.name} (permanent : réappliqué par le pont à chaque tour)"
    }

    private fun purchase(context: Context, id: String, order: JsonObject): String {
        val cityId = order.reqStr("city_id")
        val name = order.reqStr("construction")
        val statName = order.reqStr("stat")
        val civ = context.civ
        val city = ourCity(civ, cityId)
        val stat = when (statName) {
            "Gold" -> Stat.Gold
            "Faith" -> Stat.Faith
            else -> throw OrderReject("unknown_stat", statName)
        }
        val construction = resolve(civ, name) ?: throw OrderReject("unknown_construction", name)
        if (construction !is INonPerpetualConstruction) throw OrderReject("not_purchasable", "$name est une construction perpétuelle")
        if (name in civ.disabledCityConstructions) throw OrderReject("construction_disabled", name)
        val cc = city.cityConstructions
        val cost = construction.getStatBuyCost(city, stat) ?: throw OrderReject("not_purchasable", "$name n'a pas de prix en ${stat.name}")
        // purchaseConstruction checks neither the reserve nor the eligibility: this is the game's "one true test"
        if (!cc.isConstructionPurchaseAllowed(construction, stat, cost)) throw purchaseRejection(city, construction, stat, cost)
        val reserveBefore = city.getStatReserve(stat)
        val queueIndex = cc.constructionQueue.indexOf(name) // buying a queued item removes its entry, like the UI
        if (!cc.purchaseConstruction(construction, queueIndex, automatic = true, stat = stat)) throw OrderReject("purchase_failed", name)
        context.touched += city
        context.record(AppliedOrder.Purchase(id, city.id, name, stat.name))
        return "$name acheté à ${city.name} pour $cost ${stat.name} (réserve $reserveBefore -> ${city.getStatReserve(stat)})"
    }

    /** Same order as `CityConstructions.isConstructionPurchaseAllowed`, to give the reason */
    @Suppress("DEPRECATION") // City.getMatchingUniques: the form used by isConstructionPurchaseAllowed itself
    private fun purchaseRejection(city: City, construction: INonPerpetualConstruction, stat: Stat, cost: Int): OrderReject {
        val name = construction.name
        return when {
            city.isPuppet && !city.getMatchingUniques(UniqueType.MayBuyConstructionsInPuppets).any() -> OrderReject("city_is_puppet", city.name)
            city.isInResistance() -> OrderReject("city_in_resistance", city.name)
            !construction.isPurchasable(city.cityConstructions) -> OrderReject("cannot_build", rejectionDetail(construction, city.cityConstructions))
            construction is BaseUnit && !city.canPlaceNewUnit(construction) -> OrderReject("no_space_for_unit", "case centrale de ${city.name} occupée")
            !construction.canBePurchasedWithStat(city, stat) -> OrderReject("not_purchasable", "$name ne s'achète pas en ${stat.name}")
            else -> OrderReject(if (stat == Stat.Gold) "not_enough_gold" else "not_enough_faith",
                "$name coûte $cost ${stat.name}, réserve ${city.getStatReserve(stat)}")
        }
    }

    /** Same two lines as CivView.tryDisableCivConstruction / tryEnableCivConstruction (no view method, CLAUDE.md §6) */
    private fun toggleConstruction(context: Context, id: String, name: String, disable: Boolean): String {
        if (resolve(context.civ, name) !is INonPerpetualConstruction) throw OrderReject("unknown_construction", name)
        val civ = context.civ
        val changed = if (disable) civ.disabledCityConstructions.add(name) else civ.disabledCityConstructions.remove(name)
        for (city in civ.cities) {
            if (disable) city.disabledConstructions.add(name) else city.disabledConstructions.remove(name)
        }
        context.record(AppliedOrder.Toggle(id, name, disable))
        val state = if (disable) "$name désactivée dans toutes les villes (le pont la retire des files : l'IA native ignore ce drapeau)"
            else "$name réactivée"
        return if (changed) state else "$state ; déjà dans cet état"
    }

    // endregion
}
