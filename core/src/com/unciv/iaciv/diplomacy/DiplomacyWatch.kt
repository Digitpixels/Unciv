package com.unciv.iaciv.diplomacy

import com.unciv.iaciv.dto.TradeOfferDto
import com.unciv.iaciv.dto.iaCivJson
import com.unciv.logic.GameInfo
import com.unciv.logic.civilization.AlertType
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.diplomacy.Demand
import com.unciv.logic.civilization.diplomacy.DiplomacyFlags
import com.unciv.logic.civilization.diplomacy.DiplomaticStatus
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Contract M3 §6 — the single global observer of diplomacy. Called at H1 and H4 of **every** AI civ (LLM or not,
 * city-states included): it compares a global snapshot (pairs of living player civs that know each other: status,
 * friendship, defensive pact, directed denunciations, friendship refusals and demand flags; city-state allies; the
 * multiset of popup alerts; the trade requests by id) with the previous one and reports each change **once**, with an
 * `event_key` the orchestrator deduplicates on. The first snapshot after a (re)start is a base, without events.
 *
 * Windows: changes seen at H4 of X happened during X's turn (its LLM orders, applied at H1, and its native AI);
 * changes seen at H1 of X happened between two turns (end of the previous civ's turn, X's turn start).
 * The bridge tells the watcher what its own actions mean (LLM answers to trades, wars declared by an order, refusals
 * on expiry), so that the detection gives them the same key and never a second event.
 *
 * Only strings and numbers are kept (in UI mode each round runs on a clone of GameInfo).
 */
class DiplomacyWatch {

    companion object {
        const val TRADE_ACCEPTED = "accepted"
        const val TRADE_REFUSED = "refused"
        const val TRADE_COUNTERED = "countered"
        const val TRADE_EXPIRED = "expired"
        const val TRADE_EXPIRED_REFUSED = "expired_refused"

        const val BY_LLM = "llm"
        const val BY_NATIVE = "native"
        const val BY_EXPIRY = "expiry"

        const val NATIVE_MAJOR_ONLY = "major_only"
        const val NATIVE_ALL = "all"
        const val NATIVE_NONE = "none"

        const val HOOK_H1 = "H1"
        const val HOOK_H4 = "H4"

        private const val WAR = "War"
        private const val MAX_KEYS = 5000

        /** Native-only events kept with `native_pairs = "major_only"` (city-states: wars and ally changes only) */
        private val MAJOR_ONLY_TYPES = setOf(
            "war_declared", "peace_signed", "friendship_offered", "friendship_declared", "friendship_refused",
            "friendship_ended", "denounced", "denunciation_expired", "defensive_pact_signed", "defensive_pact_ended",
            "city_state_ally_changed",
        )
        private val CITY_STATE_TYPES = setOf("war_declared", "city_state_ally_changed")

        private val WATCHED_FLAGS: List<DiplomacyFlags> = listOf(DiplomacyFlags.Denunciation, DiplomacyFlags.DeclinedDeclarationOfFriendship) +
            Demand.entries.flatMap { listOf(it.agreedToDemand, it.willIgnoreViolation) }
    }

    private class RequestInfo(val from: String, val theyGive: List<TradeOfferDto>, val weGive: List<TradeOfferDto>, val isPeace: Boolean) {
        /** Fingerprint of the trade as the target stores it after accepting it (target = "ours") */
        val fingerprint = DiplomacyIds.fingerprint(weGive, theyGive)
    }

    private class PairInfo(val status: String, val friendship: Boolean, val pact: Boolean)

    private class Snapshot(
        val gameId: String,
        val turn: Int,
        val majors: Set<String>,
        val cityStates: Set<String>,
        /** "A|B" (sorted) -> state */
        val pairs: Map<String, PairInfo>,
        /** "A>B:Flag" -> countdown, read on A's manager towards B */
        val flags: Map<String, Int>,
        /** city-state -> ally */
        val allies: Map<String, String?>,
        /** civ -> "Type|value" -> count (held alerts included) */
        val alerts: Map<String, Map<String, Int>>,
        /** civ -> request id -> request (held requests included) */
        val requests: Map<String, Map<String, RequestInfo>>,
        /** pairs with a running peace treaty */
        val peaceTreaties: Set<String>,
    ) {
        val alive get() = majors + cityStates
    }

    /** One detected or emitted change, before the common fields */
    private class Change(val type: String, val key: String, val involved: List<String?>, val fields: Map<String, JsonElement>)

    private var last: Snapshot? = null
    private val emittedKeys = LinkedHashSet<String>()

    /** Hints from the bridge, consumed by the next observation: request id -> (issue, decided_by) */
    private val tradeDecisions = HashMap<String, Pair<String, String>>()
    /** "A>B" wars declared by an LLM order since the last observation */
    private val declaredByOrder = HashSet<String>()
    /** request id -> id of the request it counters (contract §3 `counter_to`) */
    private val counterTo = HashMap<String, String>()
    /** "civ|id" -> turn the request or alert was first seen (age of `pending.outgoing`) */
    private val firstSeen = HashMap<String, Int>()
    /** pair -> turn the war was seen starting (`at_war_since_turns`) */
    private val warStart = HashMap<String, Int>()

    /** LLM-controlled civs (`llm_involved`) */
    var llmCivs: Set<String> = setOf()
    /** `diplomacy_watch.native_pairs` */
    var nativePairs: String = NATIVE_MAJOR_ONLY

    // region hints from the bridge

    /** The bridge decided the fate of a trade request (LLM answer, refusal on expiry, invalid copy dropped) */
    fun noteTradeDecision(requestId: String, issue: String, decidedBy: String) {
        tradeDecisions[requestId] = issue to decidedBy
    }

    /** A war declared by a `declare_war` order (cause `declared`) */
    fun noteWarDeclared(aggressor: String, target: String) {
        declaredByOrder += "$aggressor>$target"
    }

    fun noteCounter(newRequestId: String, counteredRequestId: String) {
        counterTo[newRequestId] = counteredRequestId
    }

    fun counterTo(requestId: String): String? = counterTo[requestId]

    /** Turns since the request or alert [id] was first seen in [civId]'s lists (0 if unknown) */
    fun ageOf(civId: String, id: String, turn: Int): Int = firstSeen["$civId|$id"]?.let { (turn - it).coerceAtLeast(0) } ?: 0

    /** Turn the war between [a] and [b] was seen starting, if the watcher saw it */
    fun warStartTurn(a: String, b: String): Int? = warStart[DiplomacyIds.pairKey(a, b)]

    // endregion
    // region direct emissions (refusals on expiry, decided by the bridge at H1 of the LLM civ)

    fun emitTradeExpiredRefused(civ: Civilization, from: String, requestId: String, isPeace: Boolean): List<JsonObject> {
        noteTradeDecision(requestId, TRADE_EXPIRED_REFUSED, BY_EXPIRY)
        return emitDirect(civ, tradeChange(TRADE_EXPIRED_REFUSED, requestId, civ.gameInfo.turns, from, civ.civID, isPeace, BY_EXPIRY))
    }

    fun emitFriendshipExpiredRefused(civ: Civilization, proposer: String): List<JsonObject> {
        val turn = civ.gameInfo.turns
        cover("friendship:$proposer>${civ.civID}:$turn:friendship_refused")
        return emitDirect(civ, Change("friendship_expired_refused", "friendship:$proposer>${civ.civID}:$turn:friendship_expired_refused",
            listOf(proposer, civ.civID), mapOf("from" to JsonPrimitive(proposer), "to" to JsonPrimitive(civ.civID))))
    }

    fun emitDemandExpiredRefused(civ: Civilization, demander: String, demand: String): List<JsonObject> {
        val turn = civ.gameInfo.turns
        cover("demand:$demander>${civ.civID}:$demand:$turn:demand_refused")
        return emitDirect(civ, Change("demand_expired_refused", "demand:$demander>${civ.civID}:$demand:$turn:demand_expired_refused",
            listOf(demander, civ.civID), demandFields(demander, civ.civID, demand)))
    }

    private fun emitDirect(civ: Civilization, change: Change): List<JsonObject> {
        if (change.key in emittedKeys) return listOf()
        remember(change.key)
        return listOf(toEvent(change, civ.gameInfo.turns, civ.civID, HOOK_H1))
    }

    private fun cover(key: String) = remember(key)

    private fun remember(key: String) {
        emittedKeys += key
        if (emittedKeys.size > MAX_KEYS) {
            val iterator = emittedKeys.iterator()
            repeat(emittedKeys.size - MAX_KEYS) { iterator.next(); iterator.remove() }
        }
    }

    // endregion
    // region observation

    /**
     * Takes a snapshot at [hook] of [windowCiv]'s turn and returns the global events not reported yet.
     * [hold] gives the requests and alerts the bridge is holding (they count as still present).
     */
    fun observe(gameInfo: GameInfo, hook: String, windowCiv: Civilization, hold: PendingHold?): List<JsonObject> {
        val current = snapshot(gameInfo, hold)
        val previous = last
        last = current
        val turn = current.turn
        trackFirstSeen(current)
        if (previous == null || previous.gameId != current.gameId) {
            clearHints()
            return listOf()
        }
        val window = if (hook == HOOK_H4) windowCiv.civID else null
        val changes = ArrayList<Change>()
        val newAlerts = newAlerts(previous, current)
        detectPairs(previous, current, turn, window, newAlerts, changes)
        val brokenPromises = detectAlerts(newAlerts, turn, changes)
        detectFlags(previous, current, turn, brokenPromises, changes)
        detectRequests(gameInfo, previous, current, turn, window, changes)
        detectAllies(previous, current, turn, changes)
        clearHints()
        val events = ArrayList<JsonObject>()
        for (change in changes) {
            if (change.key in emittedKeys) continue
            remember(change.key)
            if (!wanted(change, current)) continue
            events += toEvent(change, turn, window, hook)
        }
        return events
    }

    private fun clearHints() {
        tradeDecisions.clear()
        declaredByOrder.clear()
    }

    private fun snapshot(gameInfo: GameInfo, hold: PendingHold?): Snapshot {
        val civs = gameInfo.civilizations.filter { DiplomacyIds.isPlayerCiv(it) && !it.isDefeated() }
        val ids = civs.map { it.civID }.toSet()
        val nameToId = gameInfo.civilizations.associate { it.civName to it.civID }
        val pairs = HashMap<String, PairInfo>()
        val flags = HashMap<String, Int>()
        val allies = HashMap<String, String?>()
        val alerts = HashMap<String, Map<String, Int>>()
        val requests = HashMap<String, Map<String, RequestInfo>>()
        val peace = HashSet<String>()
        for (civ in civs) {
            for (dm in civ.diplomacy.values) {
                val otherId = dm.otherCivName
                if (otherId !in ids) continue
                for (flag in WATCHED_FLAGS) {
                    val value = dm.getFlag(flag)
                    if (value != 0) flags["${civ.civID}>$otherId:${flag.name}"] = value
                }
                if (civ.civID >= otherId) continue
                val back = dm.otherCiv.getDiplomacyManager(civ)
                val war = dm.diplomaticStatus == DiplomaticStatus.War || back?.diplomaticStatus == DiplomaticStatus.War
                val pact = dm.diplomaticStatus == DiplomaticStatus.DefensivePact || back?.diplomaticStatus == DiplomaticStatus.DefensivePact ||
                    dm.hasFlag(DiplomacyFlags.DefensivePact) || back?.hasFlag(DiplomacyFlags.DefensivePact) == true
                val friendship = dm.hasFlag(DiplomacyFlags.DeclarationOfFriendship) || back?.hasFlag(DiplomacyFlags.DeclarationOfFriendship) == true
                val key = "${civ.civID}|$otherId"
                pairs[key] = PairInfo(if (war) WAR else dm.diplomaticStatus.name, friendship, pact)
                if (dm.turnsToPeaceTreaty() > 0 || (back?.turnsToPeaceTreaty() ?: 0) > 0) peace += key
            }
            if (civ.isCityState) allies[civ.civID] = civ.allyCiv?.civID
            val alertCounts = HashMap<String, Int>()
            // war declarations carry civName (= civID for the nations of the base ruleset)
            fun count(type: AlertType, value: String) {
                val normalized = if (type == AlertType.WarDeclaration) nameToId[value] ?: value else value
                alertCounts.merge("${type.name}|$normalized", 1, Int::plus)
            }
            for (alert in civ.popupAlerts) count(alert.type, alert.value)
            for (alert in hold?.heldAlerts(civ.civID) ?: listOf()) count(alert.type, alert.value)
            alerts[civ.civID] = alertCounts
            val civRequests = LinkedHashMap<String, RequestInfo>()
            val allRequests = civ.tradeRequests + (hold?.heldTrades(civ.civID)?.map { it.request } ?: listOf())
            for (request in allRequests) {
                val theyGive = DiplomacyIds.offers(request.trade.theirOffers)
                val weGive = DiplomacyIds.offers(request.trade.ourOffers)
                val id = DiplomacyIds.tradeRequestId(request.requestingCiv, theyGive, weGive)
                civRequests.putIfAbsent(id, RequestInfo(request.requestingCiv, theyGive, weGive, request.trade.isPeaceTreaty()))
            }
            requests[civ.civID] = civRequests
        }
        return Snapshot(
            gameId = gameInfo.gameId,
            turn = gameInfo.turns,
            majors = civs.filter { it.isMajorCiv() }.map { it.civID }.toSet(),
            cityStates = civs.filter { it.isCityState }.map { it.civID }.toSet(),
            pairs = pairs, flags = flags, allies = allies, alerts = alerts, requests = requests, peaceTreaties = peace,
        )
    }

    private fun trackFirstSeen(current: Snapshot) {
        val present = HashSet<String>()
        for ((civ, byId) in current.requests) for (id in byId.keys) present += "$civ|$id"
        for ((civ, counts) in current.alerts) for (key in counts.keys) {
            val (type, value) = key.split("|", limit = 2)
            present += "$civ|PA-$type-$value"
        }
        firstSeen.keys.retainAll(present)
        for (key in present) firstSeen.putIfAbsent(key, current.turn)
        val requestIds = current.requests.values.flatMap { it.keys }.toSet()
        counterTo.keys.retainAll(requestIds)
    }

    /** civ -> list of (type, value) added since the previous snapshot (multiset difference) */
    private fun newAlerts(previous: Snapshot, current: Snapshot): Map<String, List<Pair<AlertType, String>>> {
        val result = HashMap<String, List<Pair<AlertType, String>>>()
        for ((civ, counts) in current.alerts) {
            val before = previous.alerts[civ] ?: mapOf()
            val added = ArrayList<Pair<AlertType, String>>()
            for ((key, count) in counts) {
                val extra = count - (before[key] ?: 0)
                if (extra <= 0) continue
                val (typeName, value) = key.split("|", limit = 2)
                val type = AlertType.entries.firstOrNull { it.name == typeName } ?: continue
                repeat(extra) { added += type to value }
            }
            if (added.isNotEmpty()) result[civ] = added
        }
        return result
    }

    private fun detectPairs(previous: Snapshot, current: Snapshot, turn: Int, window: String?,
                            newAlerts: Map<String, List<Pair<AlertType, String>>>, changes: MutableList<Change>) {
        class War(val aggressor: String?, val target: String, val pair: String)
        val wars = ArrayList<War>()
        for ((pair, now) in current.pairs) {
            val (a, b) = pair.split("|", limit = 2)
            val before = previous.pairs[pair]
            if (before == null) {
                if (a in previous.alive && b in previous.alive) // both alive before: a real first contact
                    changes += Change("first_contact", "contact:$pair", listOf(a, b), mapOf("civs" to civsArray(a, b)))
                if (now.status == WAR) warStart.putIfAbsent(pair, turn)
                continue
            }
            if (before.status != WAR && now.status == WAR) {
                warStart[pair] = turn
                val declaredOnB = newAlerts[b]?.any { it.first == AlertType.WarDeclaration && it.second == a } == true
                val declaredOnA = newAlerts[a]?.any { it.first == AlertType.WarDeclaration && it.second == b } == true
                wars += when {
                    declaredOnB -> War(a, b, pair)
                    declaredOnA -> War(b, a, pair)
                    window == a -> War(a, b, pair)
                    window == b -> War(b, a, pair)
                    else -> War(null, b, pair)
                }
            }
            if (before.status == WAR && now.status != WAR) {
                warStart.remove(pair)
                changes += Change("peace_signed", "peace:$pair:$turn", listOf(a, b),
                    mapOf("civs" to civsArray(a, b), "via_trade" to JsonPrimitive(pair in current.peaceTreaties)))
            }
            if (!before.friendship && now.friendship)
                changes += Change("friendship_declared", "friendship:$pair:$turn:friendship_declared", listOf(a, b), mapOf("civs" to civsArray(a, b)))
            if (before.friendship && !now.friendship)
                changes += Change("friendship_ended", "friendship:$pair:$turn:friendship_ended", listOf(a, b),
                    mapOf("civs" to civsArray(a, b), "reason" to JsonPrimitive(if (now.status == WAR) "war" else "expired")))
            if (!before.pact && now.pact)
                changes += Change("defensive_pact_signed", "pact:$pair:$turn:defensive_pact_signed", listOf(a, b), mapOf("civs" to civsArray(a, b)))
            if (before.pact && !now.pact)
                changes += Change("defensive_pact_ended", "pact:$pair:$turn:defensive_pact_ended", listOf(a, b), mapOf("civs" to civsArray(a, b)))
        }
        // Cause of each new war. "trade": a war declaration that was part of a trade request that just vanished.
        // "cascade": a defensive-pact partner of another new war's target (the aggressor declares on it too,
        // DeclareWar.callInDefensivePactAllies) or a city-state joining its ally (callInCityStateAllies).
        val tradeWars = HashSet<String>()
        for ((target, byId) in previous.requests) for ((id, info) in byId) {
            if (current.requests[target]?.containsKey(id) == true) continue
            info.theyGive.filter { it.type == "WarDeclaration" }.forEach { tradeWars += "${info.from}>${it.name}" }
            info.weGive.filter { it.type == "WarDeclaration" }.forEach { tradeWars += "$target>${it.name}" }
        }
        fun civsOf(war: War) = setOfNotNull(war.aggressor, war.target)
        fun pactLinked(war: War, other: War) = war.aggressor != null && other.aggressor == war.aggressor &&
            previous.pairs[DiplomacyIds.pairKey(war.target, other.target)]?.pact == true
        fun isOrder(war: War) = war.aggressor != null && "${war.aggressor}>${war.target}" in declaredByOrder
        for (war in wars) {
            val aggressor = war.aggressor
            val others = wars.filter { it !== war }
            val cause = when {
                isOrder(war) -> "declared"
                aggressor != null && "$aggressor>${war.target}" in tradeWars -> "trade"
                others.any { pactLinked(war, it) && isOrder(it) } -> "cascade"
                aggressor != null && aggressor in previous.cityStates && others.any { previous.allies[aggressor] in civsOf(it) } -> "cascade"
                others.any { pactLinked(war, it) } -> "unknown" // pact partners attacked together: the first target cannot be told
                aggressor != null && aggressor == window -> "declared"
                else -> "unknown"
            }
            changes += Change("war_declared", "war:${aggressor ?: "unknown"}>${war.target}:$turn", listOf(aggressor, war.target),
                mapOf("aggressor" to (aggressor?.let { JsonPrimitive(it) } ?: JsonNull), "target" to JsonPrimitive(war.target), "cause" to JsonPrimitive(cause)))
        }
    }

    /** Offers of friendship, demands and broken promises; returns the broken promises as "D>X:Demand" */
    private fun detectAlerts(newAlerts: Map<String, List<Pair<AlertType, String>>>, turn: Int, changes: MutableList<Change>): Set<String> {
        val broken = HashSet<String>()
        for ((target, added) in newAlerts) {
            for ((type, rawValue) in added) {
                val from = rawValue
                if (type == AlertType.DeclarationOfFriendship) {
                    changes += Change("friendship_offered", "friendship:$from>$target:$turn:friendship_offered", listOf(from, target),
                        mapOf("from" to JsonPrimitive(from), "to" to JsonPrimitive(target)))
                    continue
                }
                Demand.entries.firstOrNull { it.demandAlert == type }?.let { demand ->
                    changes += Change("demand_issued", "demand:$from>$target:${demand.name}:$turn:demand_issued", listOf(from, target),
                        demandFields(from, target, demand.name))
                }
                Demand.entries.firstOrNull { it.violationDiscoveredAlert == type }?.let { demand ->
                    broken += "$from>$target:${demand.name}"
                    changes += Change("promise_broken", "demand:$from>$target:${demand.name}:$turn:promise_broken", listOf(from, target),
                        demandFields(from, target, demand.name))
                }
            }
        }
        return broken
    }

    private fun detectFlags(previous: Snapshot, current: Snapshot, turn: Int, brokenPromises: Set<String>, changes: MutableList<Change>) {
        val alive = current.alive
        for (key in previous.flags.keys + current.flags.keys) {
            val (direction, flagName) = key.split(":", limit = 2)
            val (a, b) = direction.split(">", limit = 2)
            if (a !in alive || b !in alive) continue
            val before = previous.flags[key] ?: 0
            val now = current.flags[key] ?: 0
            val raised = now > before
            when (flagName) {
                DiplomacyFlags.Denunciation.name -> {
                    if (raised) changes += Change("denounced", "denounce:$a>$b:$turn:denounced", listOf(a, b),
                        mapOf("by" to JsonPrimitive(a), "target" to JsonPrimitive(b)))
                    else if (before > 0 && now == 0) changes += Change("denunciation_expired", "denounce:$a>$b:$turn:denunciation_expired", listOf(a, b),
                        mapOf("by" to JsonPrimitive(a), "target" to JsonPrimitive(b)))
                }
                // set on the proposer's manager by the civ that refused
                DiplomacyFlags.DeclinedDeclarationOfFriendship.name -> if (raised)
                    changes += Change("friendship_refused", "friendship:$a>$b:$turn:friendship_refused", listOf(b, a),
                        mapOf("by" to JsonPrimitive(b), "proposer" to JsonPrimitive(a)))
                else -> for (demand in Demand.entries) {
                    // demand flags are set on the demander's manager (a) towards the civ that answered (b)
                    if (!raised) continue
                    if (flagName == demand.agreedToDemand.name)
                        changes += Change("demand_accepted", "demand:$a>$b:${demand.name}:$turn:demand_accepted", listOf(a, b), demandFields(a, b, demand.name))
                    if (flagName == demand.willIgnoreViolation.name && "$a>$b:${demand.name}" !in brokenPromises)
                        changes += Change("demand_refused", "demand:$a>$b:${demand.name}:$turn:demand_refused", listOf(a, b), demandFields(a, b, demand.name))
                }
            }
        }
    }

    private fun detectRequests(gameInfo: GameInfo, previous: Snapshot, current: Snapshot, turn: Int, window: String?, changes: MutableList<Change>) {
        for ((target, byId) in current.requests) {
            val before = previous.requests[target] ?: mapOf()
            for ((id, info) in byId) {
                if (id in before) continue
                changes += Change("trade_proposed", "trade:$id:$turn:proposed", listOf(info.from, target), mapOf(
                    "from" to JsonPrimitive(info.from), "to" to JsonPrimitive(target), "request_id" to JsonPrimitive(id),
                    "they_give" to offersJson(info.theyGive), "we_give" to offersJson(info.weGive),
                    "is_peace_treaty" to JsonPrimitive(info.isPeace),
                ))
            }
        }
        for ((target, byId) in previous.requests) {
            val now = current.requests[target] ?: mapOf()
            for ((id, info) in byId) {
                if (id in now) continue
                val hinted = tradeDecisions[id]
                val (issue, decidedBy) = when {
                    hinted != null -> hinted
                    window == null || window != target || target !in current.alive || info.from !in current.alive -> TRADE_EXPIRED to BY_EXPIRY
                    else -> inferAnswer(gameInfo, previous, current, target, id, info) to BY_NATIVE
                }
                changes += tradeChange(issue, id, turn, info.from, target, info.isPeace, decidedBy)
            }
        }
    }

    /** The target answered during its own turn (native AI): accepted, countered or refused? */
    private fun inferAnswer(gameInfo: GameInfo, previous: Snapshot, current: Snapshot, target: String, id: String, info: RequestInfo): String {
        val pair = DiplomacyIds.pairKey(target, info.from)
        if (info.isPeace && current.pairs[pair]?.status != WAR) return TRADE_ACCEPTED
        val targetCiv = gameInfo.getCivilizationOrNull(target)
        val dm = targetCiv?.let { civ -> gameInfo.getCivilizationOrNull(info.from)?.let { civ.getDiplomacyManager(it) } }
        if (dm != null && dm.trades.any { DiplomacyIds.tradeFingerprint(it) == info.fingerprint }) return TRADE_ACCEPTED
        val before = previous.requests[info.from] ?: mapOf()
        val counter = (current.requests[info.from] ?: mapOf()).entries.firstOrNull { (newId, newInfo) -> newInfo.from == target && newId !in before }
        if (counter != null) {
            counterTo[counter.key] = id
            return TRADE_COUNTERED
        }
        return TRADE_REFUSED
    }

    private fun detectAllies(previous: Snapshot, current: Snapshot, turn: Int, changes: MutableList<Change>) {
        for ((cityState, ally) in current.allies) {
            if (cityState !in previous.allies) continue
            val before = previous.allies[cityState]
            if (before == ally) continue
            changes += Change("city_state_ally_changed", "csally:$cityState:$turn", listOf(cityState, before, ally), mapOf(
                "city_state" to JsonPrimitive(cityState),
                "old_ally" to (before?.let { JsonPrimitive(it) } ?: JsonNull),
                "new_ally" to (ally?.let { JsonPrimitive(it) } ?: JsonNull),
            ))
        }
    }

    // endregion
    // region event building

    private fun tradeChange(issue: String, id: String, turn: Int, from: String, to: String, isPeace: Boolean, decidedBy: String) =
        Change("trade_$issue", "trade:$id:$turn:$issue", listOf(from, to), mapOf(
            "from" to JsonPrimitive(from), "to" to JsonPrimitive(to), "request_id" to JsonPrimitive(id),
            "is_peace_treaty" to JsonPrimitive(isPeace), "decided_by" to JsonPrimitive(decidedBy),
        ))

    private fun demandFields(from: String, to: String, demand: String) =
        mapOf("from" to JsonPrimitive(from), "to" to JsonPrimitive(to), "demand" to JsonPrimitive(demand))

    private fun civsArray(a: String, b: String) = buildJsonArray { listOf(a, b).sorted().forEach { add(JsonPrimitive(it)) } }

    private fun offersJson(offers: List<TradeOfferDto>): JsonElement =
        iaCivJson.encodeToJsonElement(ListSerializer(TradeOfferDto.serializer()), offers)

    private fun isLlm(change: Change) = change.involved.any { it != null && it in llmCivs }

    /** `native_pairs` filter for changes that involve no LLM civ */
    private fun wanted(change: Change, current: Snapshot): Boolean {
        if (isLlm(change)) return true
        return when (nativePairs) {
            NATIVE_ALL -> true
            NATIVE_NONE -> false
            else -> {
                if (change.type !in MAJOR_ONLY_TYPES) return false
                val civs = change.involved.filterNotNull()
                civs.all { it in current.majors } || (change.type in CITY_STATE_TYPES)
            }
        }
    }

    private fun toEvent(change: Change, turn: Int, duringTurnOf: String?, hook: String): JsonObject = buildJsonObject {
        put("type", change.type)
        put("turn", turn)
        put("scope", "global")
        put("event_key", change.key)
        put("during_turn_of", duringTurnOf)
        put("hook", hook)
        put("llm_involved", isLlm(change))
        for ((key, value) in change.fields) put(key, value)
    }

    // endregion

    fun reset() {
        last = null
        emittedKeys.clear()
        clearHints()
        counterTo.clear()
        firstSeen.clear()
        warStart.clear()
    }
}
