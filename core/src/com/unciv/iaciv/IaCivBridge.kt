package com.unciv.iaciv

import com.unciv.UncivGame
import com.unciv.iaciv.config.BridgeConfig
import com.unciv.iaciv.diplomacy.DiplomacyContext
import com.unciv.iaciv.diplomacy.DiplomacyWatch
import com.unciv.iaciv.diplomacy.PendingHold
import com.unciv.iaciv.display.InGameMessages
import com.unciv.iaciv.dto.DisplayResultDto
import com.unciv.iaciv.dto.EventsRequest
import com.unciv.iaciv.dto.FallbackDto
import com.unciv.iaciv.dto.MapDto
import com.unciv.iaciv.dto.OrderResultDto
import com.unciv.iaciv.dto.OrdersResponse
import com.unciv.iaciv.dto.PROTOCOL_VERSION
import com.unciv.iaciv.dto.PlayerDto
import com.unciv.iaciv.dto.SessionRequest
import com.unciv.iaciv.dto.TurnEndRequest
import com.unciv.iaciv.dto.TurnStartRequest
import com.unciv.iaciv.dto.iaCivJson
import com.unciv.iaciv.net.OrchestratorClient
import com.unciv.iaciv.net.OrchestratorClient.Outcome
import com.unciv.iaciv.observe.CivSnapshot
import com.unciv.iaciv.observe.EventCollector
import com.unciv.iaciv.observe.LegalOptions
import com.unciv.iaciv.observe.ObservationBuilder
import com.unciv.iaciv.orders.AppliedOrder
import com.unciv.iaciv.orders.OrderCheck
import com.unciv.iaciv.orders.OrderChecker
import com.unciv.iaciv.orders.OrdersApplier
import com.unciv.iaciv.orders.PendingCheck
import com.unciv.iaciv.orders.StandingOrders
import com.unciv.iaciv.save.TurnSaver
import com.unciv.logic.GameInfo
import com.unciv.logic.civilization.Civilization
import com.unciv.utils.Log
import com.unciv.view.GameView
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.time.Instant

/**
 * Bridge state, shared by the hooks (JVM singleton — never serialized into GameInfo, see CLAUDE.md §5).
 * Keeps only identifiers between turns: in UI mode each turn runs on a clone of GameInfo.
 *
 * M1: send the observation of each LLM-controlled civ, fall back to the native AI on any failure, one save per turn.
 * M2: apply the orders at H1 (OrdersApplier), re-apply the standing orders (focus, disabled constructions), and at H4
 * check whether each applied order is still in place (`order_check` events).
 * M3: H2 (`ownsDiplomacy`), the 9 diplomatic orders, the hold of unanswered offers ([PendingHold]), the messages shown
 * in game ([InGameMessages]) and the global diplomatic events of every AI civ ([DiplomacyWatch], `POST /v1/events`).
 */
object IaCivBridge {
    const val BRIDGE_VERSION = "0.3.0-m3"
    val BRIDGE_CAPABILITIES = listOf("diplomacy_watch", "display", "pending_hold")
    /** Global events kept for a later retry when `/v1/events` fails (oldest dropped beyond this) */
    private const val MAX_QUEUED_EVENTS = 2000

    private var initialized = false
    var config: BridgeConfig? = null
        private set
    var partieDir: File? = null
        private set
    private var log = BridgeLog(null)
    private var client: OrchestratorClient? = null

    /** The headless launcher writes the saves itself after each round; in UI mode the bridge does it at the first H1 of a turn */
    @Volatile
    var savesInHook = true

    /** Set by the headless launcher when the game was resumed from a save (sent in `/v1/session`) */
    var resumedFromSave: String? = null

    private val civStates = HashMap<String, CivState>()
    private val standingOrders = StandingOrders()
    private val hold = PendingHold()
    private val watch = DiplomacyWatch()
    /** Global events not accepted by the orchestrator yet (retried at the next global send) */
    private val queuedEvents = ArrayList<JsonObject>()
    private var lastQueueRetry: String? = null
    private var sessionGameId: String? = null
    private var lastSessionAttempt: String? = null
    private var lastSavedTurn: String? = null
    private var eventsFailing = false

    private class CivState {
        var consecutiveFallbacks = 0
        var nativeUntilTurn = -1
        /** Snapshot of the last turn-start the orchestrator accepted: events are computed since then */
        var lastSnapshot: CivSnapshot? = null
        /** Popup-derived events not delivered yet (the native AI clears the popups right after H1) */
        val carriedEvents = ArrayList<JsonObject>()
        /** Orders applied at H1, compared with the game at H4 of the same turn (identifiers and names only) */
        var pendingCheck: PendingCheck? = null
        /** `{order_id, status, detail}` of the last H4 checks, sent back in the next `active_orders_status` */
        var lastChecks: List<JsonObject> = listOf()
        /** Turn for which the orchestrator answered `native.diplomacy = "allowed"` (H2 inactive that turn) */
        var nativeDiplomacyAllowedTurn = -1
    }

    // region lifecycle

    /** Reads the config once (explicit path from the headless launcher, else `IACIV_CONFIG` / `-Diaciv.config`). */
    @Synchronized
    fun init(explicitConfigPath: String? = null): BridgeConfig? {
        if (initialized) return config
        initialized = true
        val file = BridgeConfig.findConfigFile(explicitConfigPath) ?: return null
        return try {
            val loaded = BridgeConfig.load(file)
            val dir = loaded.resolvePartieDir(file)
            config = loaded
            partieDir = dir
            log = BridgeLog(File(dir, "journaux/bridge.log"))
            client = OrchestratorClient(loaded.orchestratorUrl, BRIDGE_VERSION)
            log.info("Pont $BRIDGE_VERSION (protocole $PROTOCOL_VERSION) : config ${file.path}, partie $dir, " +
                "orchestrateur ${loaded.orchestratorUrl}, factions ${loaded.factions}, désactivé=${loaded.disabled}")
            loaded
        } catch (e: Exception) {
            Log.error("[IA-Civ] Cannot read bridge config ${file.path}: bridge disabled", e)
            null
        }
    }

    fun isActive(): Boolean = config?.disabled == false

    fun isControlled(civ: Civilization): Boolean = isActive() && civ.civID in config!!.factions

    /**
     * H5: expansion belongs to the LLM courts — the native AI must not put a Settler at the head of their queues
     * (decision of 16/10/2026). During a native pause after repeated fallbacks, the native AI is fully in charge again.
     */
    @Synchronized
    fun ownsExpansion(civ: Civilization): Boolean {
        init()
        if (!isControlled(civ)) return false
        val state = civStates[civ.civID] ?: return true
        return civ.gameInfo.turns >= state.nativeUntilTurn
    }

    /**
     * H2 (contract M3 §1): the native AI's diplomatic initiatives are cut for an LLM civ, except during a native pause
     * and on a turn where the orchestrator answered `native.diplomacy = "allowed"`.
     */
    @Synchronized
    fun ownsDiplomacy(civ: Civilization): Boolean {
        init()
        if (!isControlled(civ)) return false
        val state = civStates[civ.civID] ?: return true // H1 always creates the state before H2
        val turn = civ.gameInfo.turns
        return turn >= state.nativeUntilTurn && state.nativeDiplomacyAllowedTurn != turn
    }

    private fun diplomacyContext(cfg: BridgeConfig) = DiplomacyContext(cfg.peaceRule, cfg.pendingHoldTurns, watch, hold)

    @Synchronized
    fun shutdown() {
        client?.close()
        client = null
    }

    // endregion
    // region H1

    @Synchronized
    fun onAiTurn(civ: Civilization) {
        init()
        val cfg = config ?: return
        if (cfg.disabled) return
        val gameInfo = civ.gameInfo
        val faction = cfg.factions[civ.civID]
        // Session first: the save_written event of the UI-mode save must reach an orchestrator that knows the game folder
        if (faction != null) ensureSession(gameInfo)
        if (savesInHook) saveTurnOnce(gameInfo)
        val turn = gameInfo.turns
        // H4 missed last turn: the held offers go back into the lists before anything looks at them
        if (faction != null) restoreHeld(civ, turn, "H1", stale = true)
        watchStep(civ, DiplomacyWatch.HOOK_H1) // every AI civ (contract M3 §6)
        if (faction == null) return
        val orchestrator = client ?: return
        val state = civStates.getOrPut(civ.civID) { CivState() }
        state.pendingCheck?.let { stale -> // H4 was never reached for these orders
            state.pendingCheck = null
            sendChecks(gameInfo, civ, stale.turn, OrderChecker.notChecked(stale, "fin du tour ${stale.turn} non atteinte par le pont (H4)"))
        }
        // Standing orders first (also during a pause): the native AI reset the focus at its turn start, before H1
        applyStandingOrders(civ, turn, "H1")
        if (turn < state.nativeUntilTurn) {
            log.info("T$turn ${civ.civID} : natif (pause après des replis, jusqu'au tour ${state.nativeUntilTurn})")
            return
        }
        val diplomacy = diplomacyContext(cfg)
        expireHeld(civ, cfg, turn)

        val observeStart = System.currentTimeMillis()
        val snapshot = CivSnapshot.of(civ)
        state.carriedEvents += EventCollector.eventsFromPopupAlerts(civ, hold = hold)
        val request = TurnStartRequest(
            gameId = gameInfo.gameId,
            turn = turn,
            civId = civ.civID,
            faction = faction,
            attempt = 1,
            deadlineS = cfg.turnStartTimeoutS,
            observation = ObservationBuilder(GameView(gameInfo, civ), diplomacy).build(),
            pending = EventCollector.pending(civ, diplomacy),
            eventsSinceLast = state.carriedEvents + EventCollector.eventsSince(state.lastSnapshot, snapshot, civ),
            legalOptions = LegalOptions.build(civ, diplomacy),
            activeOrdersStatus = standingOrders.statuses(civ) + state.lastChecks,
        )
        val body = iaCivJson.encodeToString(TurnStartRequest.serializer(), request)
        val observeMs = System.currentTimeMillis() - observeStart

        val waitStart = System.currentTimeMillis()
        fun send() = orchestrator.turnStart(body, request, cfg.turnStartTimeoutS, cfg.councilTimeoutS, cfg.pollWaitS)
        var outcome = if (sessionGameId != gameInfo.gameId)
            Outcome.Failed("orchestrator_down", "session non ouverte (orchestrateur injoignable ou session refusée)")
        else send()
        if (outcome is Outcome.Failed && outcome.reason == OrchestratorClient.NO_SESSION) {
            log.warn("T$turn ${civ.civID} : l'orchestrateur ne connaît pas cette partie (redémarré ?), réouverture de la session")
            sessionGameId = null
            lastSessionAttempt = null
            ensureSession(gameInfo)
            outcome = if (sessionGameId == gameInfo.gameId) send()
            else Outcome.Failed("invalid_response", "session refusée après un 409 : ${outcome.detail}")
        }
        val waitMs = System.currentTimeMillis() - waitStart
        val timing = mapOf("observe" to observeMs, "wait" to waitMs, "apply" to 0L)

        when (outcome) {
            is Outcome.Ok -> {
                val response = outcome.value
                state.consecutiveFallbacks = 0
                state.lastSnapshot = snapshot
                state.carriedEvents.clear()
                state.lastChecks = listOf()
                val nativeDiplomacy = (response.native?.get("diplomacy") as? JsonPrimitive)?.contentOrNull
                if (nativeDiplomacy == "allowed") state.nativeDiplomacyAllowedTurn = turn
                log.info("T$turn ${civ.civID} : observation ${body.length / 1024} Ko (${observeMs} ms), " +
                    "réponse « ${response.mode} » avec ${response.orders.size} ordre(s) en $waitMs ms" +
                    (if (nativeDiplomacy == "allowed") ", diplomatie native autorisée ce tour" else "") +
                    (if (response.display.isEmpty()) "" else ", ${response.display.size} élément(s) à afficher"))
                val applyStart = System.currentTimeMillis()
                val (results, nativeIds) = applyOrders(civ, state, response, turn, diplomacy)
                holdUnanswered(civ, turn, nativeIds)
                val displayResults = showMessages(civ, turn, response)
                val applyTiming = timing + ("apply" to System.currentTimeMillis() - applyStart)
                val turnEnd = TurnEndRequest(gameId = gameInfo.gameId, turn = turn, civId = civ.civID, decreeId = response.decreeId,
                    mode = response.mode, results = results, fallback = null, timingMs = applyTiming, displayResults = displayResults)
                (orchestrator.turnEnd(turnEnd, cfg.turnEndTimeoutS) as? Outcome.Failed)?.let {
                    log.warn("T$turn ${civ.civID} : turn-end non transmis (${it.reason} : ${it.detail})")
                }
            }
            is Outcome.Failed -> {
                state.consecutiveFallbacks++
                log.warn("T$turn ${civ.civID} : REPLI NATIF (${outcome.reason} : ${outcome.detail}), " +
                    "${state.consecutiveFallbacks} repli(s) de suite")
                val events = arrayListOf(buildJsonObject {
                    put("type", "bridge_fallback"); put("civ_id", civ.civID)
                    put("reason", outcome.reason); put("detail", outcome.detail)
                })
                if (state.consecutiveFallbacks >= cfg.maxConsecutiveFallbacks) {
                    state.nativeUntilTurn = turn + cfg.cooldownTurns
                    state.consecutiveFallbacks = 0
                    log.warn("T$turn ${civ.civID} : ${cfg.maxConsecutiveFallbacks} replis de suite, natif jusqu'au tour ${state.nativeUntilTurn}")
                    events += buildJsonObject {
                        put("type", "bridge_cooldown"); put("civ_id", civ.civID); put("native_until_turn", state.nativeUntilTurn)
                    }
                }
                for (event in events) localEvent(gameInfo, event)
                // A single fallback still holds the offers (contract M3 §2); a native pause starting now does not
                if (turn >= state.nativeUntilTurn) holdUnanswered(civ, turn, setOf())
                // The orchestrator answered something unusable: it is up, tell it. If it is down or hanging, don't add delays.
                if (outcome.reason == "invalid_response") {
                    postEvents(gameInfo, events)
                    orchestrator.turnEnd(TurnEndRequest(gameId = gameInfo.gameId, turn = turn, civId = civ.civID, decreeId = null,
                        mode = "fallback", results = listOf(), fallback = FallbackDto(outcome.reason, outcome.detail), timingMs = timing),
                        cfg.turnEndTimeoutS)
                }
            }
        }
    }

    /**
     * Applies the orders of [response] (M2) and prepares the H4 check. Orders of an earlier turn (a council that missed
     * its deadline) are applied too if they are still legal: each order is validated against the current game.
     */
    private fun applyOrders(civ: Civilization, state: CivState, response: OrdersResponse, turn: Int,
                            diplomacy: DiplomacyContext): Pair<List<OrderResultDto>, Set<String>> {
        if (response.orders.isEmpty()) return listOf<OrderResultDto>() to setOf()
        if (response.civId != null && response.civId != civ.civID) {
            log.warn("T$turn ${civ.civID} : ordres adressés à ${response.civId}, tous rejetés")
            return response.orders.mapIndexed { index, order ->
                OrderResultDto(orderIdOf(order, index), "rejected", "wrong_civ", "ordres adressés à ${response.civId}")
            } to setOf()
        }
        if (response.turn != null && response.turn != turn)
            log.info("T$turn ${civ.civID} : ordres du tour ${response.turn} appliqués au tour $turn s'ils sont encore légaux")
        val alertsBefore = civ.popupAlerts.toList()
        val outcome = OrdersApplier.apply(civ, response.orders, diplomacy) // never throws
        try {
            for (focus in outcome.applied.filterIsInstance<AppliedOrder.Focus>())
                standingOrders.recordFocus(civ.civID, focus.cityId, focus.orderId, focus.focus, turn)
            logEnforcement(civ, turn, "H1", StandingOrders.enforceDisabled(civ))
            // alerts created by our own orders (golden age…) would be cleared by the native AI right after H1
            state.carriedEvents += EventCollector.eventsFromNewAlerts(civ, alertsBefore, hold)
            state.pendingCheck = OrderChecker.capture(civ, outcome.applied)
        } catch (e: Exception) {
            log.error("T$turn ${civ.civID} : suivi des ordres impossible (ordres permanents, contrôle de fin de tour)", e)
        }

        val results = outcome.results
        for (result in results.filter { it.reason == "exception" })
            log.error("T$turn ${civ.civID} : l'ordre ${result.orderId} a levé une exception : ${result.detail}")
        val byStatus = results.groupingBy { it.status }.eachCount()
        val reasons = results.filter { it.status == "rejected" }.groupingBy { it.reason ?: "?" }.eachCount()
        log.info("T$turn ${civ.civID} : ordres appliqués ${byStatus["applied"] ?: 0}, partiels ${byStatus["partial"] ?: 0}, " +
            "rejetés ${byStatus["rejected"] ?: 0}${if (reasons.isEmpty()) "" else " $reasons"}, ignorés ${byStatus["ignored"] ?: 0}")
        return results to outcome.nativeIds
    }

    private fun orderIdOf(order: JsonObject, index: Int) =
        (order["order_id"] as? JsonPrimitive)?.contentOrNull ?: "O-inconnu-$index"

    // M3: hold of the offers, messages, global events (contract M3 §2, §5, §6)

    /** H4 (or H1 if H4 was missed, [stale]): the held offers and alerts go back into the civ's lists */
    private fun restoreHeld(civ: Civilization, turn: Int, hook: String, stale: Boolean) {
        try {
            val dropped = (if (stale) hold.restoreStale(civ, watch) else hold.restore(civ, watch)) ?: return
            if (stale) log.warn("T$turn ${civ.civID} : offres en attente remises en place au début du tour (fin du tour précédent non atteinte)")
            if (!dropped.isEmpty()) log.info("T$turn ${civ.civID} : offres devenues invalides, non remises ($hook) : $dropped")
        } catch (e: Exception) {
            log.error("T$turn ${civ.civID} : remise des offres en attente impossible ($hook)", e)
        }
    }

    /** H1, before the observation: refusal of the offers too old (`pending_hold_turns`), then one item per id */
    private fun expireHeld(civ: Civilization, cfg: BridgeConfig, turn: Int) {
        try {
            hold.track(civ)
            val events = hold.expire(civ, cfg.pendingHoldTurns, watch)
            hold.track(civ)
            if (events.isEmpty()) return
            log.info("T$turn ${civ.civID} : refus à l'expiration (${cfg.pendingHoldTurns} tours sans réponse) : " +
                events.joinToString(", ") { "${it["type"]?.let { t -> (t as JsonPrimitive).content }} ${it["event_key"]}" })
            sendGlobalEvents(civ.gameInfo, events)
        } catch (e: Exception) {
            log.error("T$turn ${civ.civID} : expiration des offres en attente impossible", e)
        }
    }

    /** H1, after the orders: the unanswered offers and actionable alerts are taken out of reach of the native AI until H4 */
    private fun holdUnanswered(civ: Civilization, turn: Int, nativeIds: Set<String>) {
        try {
            val held = hold.hold(civ, nativeIds)
            if (!held.isEmpty()) log.info("T$turn ${civ.civID} : en attente jusqu'à la fin du tour (sans réponse) : $held")
            if (nativeIds.isNotEmpty()) log.info("T$turn ${civ.civID} : laissés à l'IA native : ${nativeIds.joinToString(", ")}")
        } catch (e: Exception) {
            log.error("T$turn ${civ.civID} : mise en attente des offres impossible", e)
        }
    }

    /** `display[]` -> notifications to the spectators (contract M3 §5); results for `turn-end.display_results` */
    private fun showMessages(civ: Civilization, turn: Int, response: OrdersResponse): List<DisplayResultDto> {
        if (response.display.isEmpty()) return listOf()
        return try {
            val results = InGameMessages.show(civ.gameInfo, response.display)
            val shown = results.count { it.status == "shown" }
            val rejected = results.filter { it.status == "rejected" }.map { "${it.index}:${it.reason}" }
            log.info("T$turn ${civ.civID} : affichage en jeu $shown/${results.size} montré(s)" +
                (if (rejected.isEmpty()) "" else ", rejetés $rejected"))
            results
        } catch (e: Exception) {
            log.error("T$turn ${civ.civID} : affichage des messages impossible", e)
            response.display.indices.map { DisplayResultDto(it, "", null, "rejected", "exception") }
        }
    }

    /** Global diplomatic snapshot at [hook] of [civ]'s turn; new events go to `/v1/events` */
    private fun watchStep(civ: Civilization, hook: String) {
        val cfg = config ?: return
        try {
            watch.llmCivs = cfg.factions.keys
            watch.nativePairs = cfg.diplomacyWatch.nativePairs
            val events = watch.observe(civ.gameInfo, hook, civ, hold)
            if (events.isNotEmpty())
                log.info("T${civ.gameInfo.turns} ${civ.civID} $hook : diplomatie ${events.joinToString(", ") { (it["event_key"] as JsonPrimitive).content }}")
            sendGlobalEvents(civ.gameInfo, events)
        } catch (e: Exception) {
            log.error("T${civ.gameInfo.turns} ${civ.civID} : observation diplomatique impossible ($hook)", e)
        }
    }

    /**
     * Global events (contract M3 §6): sent with the events still queued from earlier failures. On failure they stay
     * queued (retried with the next global events, or once per turn) and the new ones are copied to
     * `journaux/bridge_events.jsonl`.
     */
    private fun sendGlobalEvents(gameInfo: GameInfo, events: List<JsonObject>) {
        val retryKey = "${gameInfo.gameId}:${gameInfo.turns}"
        if (events.isEmpty() && (queuedEvents.isEmpty() || lastQueueRetry == retryKey)) return
        lastQueueRetry = retryKey
        val batch = queuedEvents + events
        if (postEvents(gameInfo, batch)) {
            queuedEvents.clear()
            return
        }
        for (event in events) localEvent(gameInfo, event)
        queuedEvents += events
        if (queuedEvents.size > MAX_QUEUED_EVENTS) {
            log.warn("File d'événements diplomatiques pleine : ${queuedEvents.size - MAX_QUEUED_EVENTS} événement(s) les plus anciens " +
                "abandonnés (copie dans bridge_events.jsonl)")
            queuedEvents.subList(0, queuedEvents.size - MAX_QUEUED_EVENTS).clear()
        }
    }

    /** Standing focus + disabled constructions (see StandingOrders) */
    private fun applyStandingOrders(civ: Civilization, turn: Int, hook: String) {
        try {
            val refocused = standingOrders.reapplyFocus(civ)
            if (refocused.isNotEmpty()) log.info("T$turn ${civ.civID} : focus permanents réappliqués ($hook) : ${refocused.joinToString(", ")}")
            logEnforcement(civ, turn, hook, StandingOrders.enforceDisabled(civ))
        } catch (e: Exception) {
            log.error("T$turn ${civ.civID} : ordres permanents non réappliqués ($hook)", e)
        }
    }

    private fun logEnforcement(civ: Civilization, turn: Int, hook: String, enforcement: StandingOrders.DisabledEnforcement) {
        if (enforcement.removed.isNotEmpty())
            log.info("T$turn ${civ.civID} : constructions désactivées retirées des files ($hook) : ${enforcement.removed}")
        if (enforcement.kept.isNotEmpty())
            log.warn("T$turn ${civ.civID} : constructions désactivées laissées en file faute d'autre choix ($hook) : ${enforcement.kept}")
    }

    // endregion
    // region H4

    /** End of the civ's turn: compare with the state captured at H1, send one `order_check` per applied order */
    @Synchronized
    fun onAiTurnEnd(civ: Civilization) {
        val cfg = config ?: return
        if (cfg.disabled) return
        val gameInfo = civ.gameInfo
        val turn = gameInfo.turns
        val controlled = civ.civID in cfg.factions
        if (controlled) restoreHeld(civ, turn, "H4", stale = false)
        watchStep(civ, DiplomacyWatch.HOOK_H4) // every AI civ (contract M3 §6)
        if (!controlled) return
        val state = civStates[civ.civID]
        val pending = state?.pendingCheck
        state?.pendingCheck = null
        if (state == null || pending == null) { // fallback, pause, or nothing applied: only the standing orders
            logEnforcement(civ, turn, "H4", StandingOrders.enforceDisabled(civ))
            return
        }
        if (pending.gameId != gameInfo.gameId || pending.turn != turn) {
            sendChecks(gameInfo, civ, pending.turn, OrderChecker.notChecked(pending, "fin de tour d'une autre partie ou d'un autre tour"))
            logEnforcement(civ, turn, "H4", StandingOrders.enforceDisabled(civ))
            return
        }
        val verification = OrderChecker.verify(civ, pending, diplomacy = diplomacyContext(cfg))
        logEnforcement(civ, turn, "H4", verification.enforcement)
        val checks = verification.checks
        state.lastChecks = checks.map { it.toActiveStatus() }
        sendChecks(gameInfo, civ, pending.turn, checks)
        val byStatus = checks.groupingBy { it.status }.eachCount()
        val overriddenBy = checks.mapNotNull { it.overriddenBy }.groupingBy { it }.eachCount()
        log.info("T$turn ${civ.civID} : fin de tour, ordres en place ${byStatus[OrderChecker.IN_PLACE] ?: 0}, " +
            "terminés ${byStatus[OrderChecker.COMPLETED] ?: 0}, défaits ${byStatus[OrderChecker.OVERRIDDEN] ?: 0}" +
            (if (overriddenBy.isEmpty()) "" else " $overriddenBy") + ", non vérifiés ${byStatus[OrderChecker.NOT_CHECKED] ?: 0}")
    }

    /** `order_check` events: to the orchestrator, or to `journaux/bridge_events.jsonl` if it cannot be reached */
    private fun sendChecks(gameInfo: GameInfo, civ: Civilization, appliedTurn: Int, checks: List<OrderCheck>) {
        if (checks.isEmpty()) return
        val events = checks.map { it.toEvent(civ.civID, appliedTurn) }
        if (!postEvents(gameInfo, events)) for (event in events) localEvent(gameInfo, event)
    }

    // endregion
    // region session, saves, events

    /** Opens the session for this game once (`/v1/session`), at most one attempt per turn if the orchestrator is down. */
    @Synchronized
    fun ensureSession(gameInfo: GameInfo) {
        if (sessionGameId == gameInfo.gameId) return
        val cfg = config ?: return
        val orchestrator = client ?: return
        val attemptKey = "${gameInfo.gameId}:${gameInfo.turns}"
        if (lastSessionAttempt == attemptKey) return
        lastSessionAttempt = attemptKey

        when (val health = orchestrator.health(cfg.healthTimeoutS, gameInfo.gameId)) {
            is Outcome.Failed -> {
                log.warn("Orchestrateur injoignable (${health.reason} : ${health.detail}) : les civs LLM jouent en natif pour l'instant")
                localEvent(gameInfo, buildJsonObject { put("type", "bridge_offline"); put("reason", health.reason) })
                return
            }
            is Outcome.Ok -> {
                val supported = health.value.supportedProtocols
                if (supported.isNotEmpty() && PROTOCOL_VERSION !in supported)
                    log.error("L'orchestrateur ne gère pas le protocole $PROTOCOL_VERSION (il annonce $supported)")
            }
        }
        val players = gameInfo.civilizations.filter { !it.isBarbarian }.mapIndexed { index, civ ->
            val faction = cfg.factions[civ.civID]
            PlayerDto(civ.civID, civ.playerType.name, faction, faction != null, index)
        }
        val mapParameters = gameInfo.tileMap.mapParameters
        val request = SessionRequest(
            gameId = gameInfo.gameId,
            uncivVersion = UncivGame.VERSION.text,
            bridgeVersion = BRIDGE_VERSION,
            ruleset = gameInfo.gameParameters.baseRuleset,
            mods = gameInfo.gameParameters.mods.toList(),
            map = MapDto(mapParameters.mapSize.name, mapParameters.shape, mapParameters.type),
            speed = gameInfo.gameParameters.speed,
            turn = gameInfo.turns,
            players = players,
            partieDir = partieDir?.path ?: "",
            resumedFromSave = resumedFromSave,
            bridgeCapabilities = BRIDGE_CAPABILITIES,
        )
        when (val result = orchestrator.session(request, cfg.sessionTimeoutS)) {
            is Outcome.Ok -> {
                sessionGameId = gameInfo.gameId
                log.info("Session ${result.value.sessionId} ouverte pour la partie ${gameInfo.gameId} (tour ${gameInfo.turns}), " +
                    "factions reconnues : ${result.value.factionsAck}")
            }
            is Outcome.Failed -> log.warn("Session non ouverte (${result.reason} : ${result.detail})")
        }
    }

    /** UI mode: one save per turn, at the first H1 of each new `turns` value. */
    private fun saveTurnOnce(gameInfo: GameInfo) {
        val dir = partieDir ?: return
        val key = "${gameInfo.gameId}:${gameInfo.turns}"
        if (lastSavedTurn == key) return
        lastSavedTurn = key
        try {
            onTurnSaved(gameInfo, TurnSaver.save(gameInfo, dir))
        } catch (e: Exception) {
            log.error("Sauvegarde du tour ${gameInfo.turns} impossible", e)
        }
    }

    /** Reports a save (written by the bridge or by the headless launcher) to the orchestrator */
    fun onTurnSaved(gameInfo: GameInfo, file: File) {
        val dir = partieDir
        val path = if (dir != null) file.relativeTo(dir).invariantSeparatorsPath else file.path
        postEvents(gameInfo, listOf(buildJsonObject { put("type", "save_written"); put("path", path) }))
    }

    /** `/v1/events` (a failure is only logged); returns true if the orchestrator accepted the events */
    fun postEvents(gameInfo: GameInfo, events: List<JsonObject>): Boolean {
        val cfg = config ?: return false
        if (cfg.disabled || events.isEmpty()) return false
        val orchestrator = client ?: return false
        val result = orchestrator.events(EventsRequest(gameId = gameInfo.gameId, turn = gameInfo.turns, events = events), cfg.eventsTimeoutS)
        if (result is Outcome.Failed) {
            // logged once per outage, not on every save
            if (!eventsFailing) log.warn("Événements non transmis (${result.reason}) à partir du tour ${gameInfo.turns} : ${events.map { it["type"] }}")
            eventsFailing = true
            return false
        }
        if (eventsFailing) {
            log.info("Événements de nouveau transmis à partir du tour ${gameInfo.turns}")
            eventsFailing = false
        }
        return true
    }

    /** Local trace of bridge events, kept even when the orchestrator is down: `journaux/bridge_events.jsonl` */
    private fun localEvent(gameInfo: GameInfo, event: JsonObject) {
        val dir = partieDir ?: return
        val line = buildJsonObject {
            put("ts", Instant.now().toString())
            put("game_id", gameInfo.gameId)
            put("turn", gameInfo.turns)
            for ((key, value) in event) put(key, value)
        }
        try {
            File(dir, "journaux").mkdirs()
            File(dir, "journaux/bridge_events.jsonl").appendText(line.toString() + "\n", Charsets.UTF_8)
        } catch (e: Exception) {
            log.error("Écriture de bridge_events.jsonl impossible", e)
        }
    }

    // endregion

    /** Test helper: put [civId] in a native pause until [untilTurn] (as after max_consecutive_fallbacks) */
    @Synchronized
    fun startNativePauseForTests(civId: String, untilTurn: Int) {
        civStates.getOrPut(civId) { CivState() }.nativeUntilTurn = untilTurn
    }

    /** Test helper: as if the orchestrator had answered `native.diplomacy = "allowed"` for [civId] at [turn] */
    @Synchronized
    fun allowNativeDiplomacyForTests(civId: String, turn: Int) {
        civStates.getOrPut(civId) { CivState() }.nativeDiplomacyAllowedTurn = turn
    }

    /** Test helper: forget everything (config, session, per-civ state) */
    @Synchronized
    fun resetForTests() {
        shutdown()
        initialized = false
        config = null
        partieDir = null
        log = BridgeLog(null)
        civStates.clear()
        standingOrders.clear()
        sessionGameId = null
        lastSessionAttempt = null
        lastSavedTurn = null
        resumedFromSave = null
        savesInHook = true
        eventsFailing = false
        hold.clear()
        watch.reset()
        queuedEvents.clear()
        lastQueueRetry = null
    }
}
