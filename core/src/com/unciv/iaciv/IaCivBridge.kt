package com.unciv.iaciv

import com.unciv.UncivGame
import com.unciv.iaciv.config.BridgeConfig
import com.unciv.iaciv.dto.EventsRequest
import com.unciv.iaciv.dto.FallbackDto
import com.unciv.iaciv.dto.MapDto
import com.unciv.iaciv.dto.OrderResultDto
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
 * M1 scope: send the observation of each LLM-controlled civ, receive (but not yet apply) orders, fall back to the
 * native AI on any failure, write one save per turn.
 */
object IaCivBridge {
    const val BRIDGE_VERSION = "0.1.0-m1"

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
        if (faction == null) return
        val orchestrator = client ?: return
        val turn = gameInfo.turns
        val state = civStates.getOrPut(civ.civID) { CivState() }
        if (turn < state.nativeUntilTurn) {
            log.info("T$turn ${civ.civID} : natif (pause après des replis, jusqu'au tour ${state.nativeUntilTurn})")
            return
        }

        val observeStart = System.currentTimeMillis()
        val snapshot = CivSnapshot.of(civ)
        state.carriedEvents += EventCollector.eventsFromPopupAlerts(civ)
        val request = TurnStartRequest(
            gameId = gameInfo.gameId,
            turn = turn,
            civId = civ.civID,
            faction = faction,
            attempt = 1,
            deadlineS = cfg.turnStartTimeoutS,
            observation = ObservationBuilder(GameView(gameInfo, civ)).build(),
            pending = EventCollector.pending(civ),
            eventsSinceLast = state.carriedEvents + EventCollector.eventsSince(state.lastSnapshot, snapshot, civ),
            legalOptions = LegalOptions.build(civ),
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
                // M1: orders are received but not applied (M2) — each one is reported as ignored
                val results = response.orders.mapIndexed { index, order ->
                    val id = (order["order_id"] as? JsonPrimitive)?.contentOrNull ?: "O-inconnu-$index"
                    OrderResultDto(id, "ignored", "not_implemented", "application des ordres : jalon M2")
                }
                log.info("T$turn ${civ.civID} : observation ${body.length / 1024} Ko (${observeMs} ms), " +
                    "réponse « ${response.mode} » avec ${response.orders.size} ordre(s) en $waitMs ms")
                val turnEnd = TurnEndRequest(gameId = gameInfo.gameId, turn = turn, civId = civ.civID, decreeId = response.decreeId,
                    mode = response.mode, results = results, fallback = null, timingMs = timing)
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

    /** Fire-and-forget `/v1/events` (a failure is only logged) */
    fun postEvents(gameInfo: GameInfo, events: List<JsonObject>) {
        val cfg = config ?: return
        if (cfg.disabled || events.isEmpty()) return
        val orchestrator = client ?: return
        val result = orchestrator.events(EventsRequest(gameId = gameInfo.gameId, turn = gameInfo.turns, events = events), cfg.eventsTimeoutS)
        if (result is Outcome.Failed) {
            // logged once per outage, not on every save
            if (!eventsFailing) log.warn("Événements non transmis (${result.reason}) à partir du tour ${gameInfo.turns} : ${events.map { it["type"] }}")
            eventsFailing = true
        } else if (eventsFailing) {
            log.info("Événements de nouveau transmis à partir du tour ${gameInfo.turns}")
            eventsFailing = false
        }
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

    /** Test helper: forget everything (config, session, per-civ state) */
    @Synchronized
    internal fun resetForTests() {
        shutdown()
        initialized = false
        config = null
        partieDir = null
        log = BridgeLog(null)
        civStates.clear()
        sessionGameId = null
        lastSessionAttempt = null
        lastSavedTurn = null
        resumedFromSave = null
        savesInHook = true
        eventsFailing = false
    }
}
