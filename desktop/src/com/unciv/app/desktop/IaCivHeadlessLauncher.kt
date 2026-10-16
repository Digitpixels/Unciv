package com.unciv.app.desktop

import com.unciv.Constants
import com.unciv.UncivGame
import com.unciv.iaciv.IaCivBridge
import com.unciv.iaciv.config.BridgeConfig
import com.unciv.iaciv.save.TurnSaver
import com.unciv.logic.GameInfo
import com.unciv.logic.GameStarter
import com.unciv.logic.civilization.PlayerType
import com.unciv.logic.files.UncivFiles
import com.unciv.logic.map.MapParameters
import com.unciv.logic.map.MapSize
import com.unciv.models.metadata.GameParameters
import com.unciv.models.metadata.GameSettings
import com.unciv.models.metadata.GameSetupInfo
import com.unciv.models.metadata.Player
import com.unciv.models.ruleset.RulesetCache
import com.unciv.models.skins.SkinCache
import com.unciv.models.tilesets.TileSetCache
import com.unciv.utils.Log
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import kotlin.system.exitProcess

/**
 * IA-Civ game without UI (model: [ConsoleLauncher]). Working directory MUST be `android/assets`.
 *
 *     java -cp Unciv.jar com.unciv.app.desktop.IaCivHeadlessLauncher --config <bridge.json> [--resume <turn_NNN.json>] [--max-turns N]
 *
 * No simulation mode: the spectator is the only human, so each `nextTurn()` is exactly one round of every AI
 * (checked in M0), after which the launcher writes `sauvegardes/turn_NNN.json`.
 */
internal object IaCivHeadlessLauncher {

    @JvmStatic
    fun main(args: Array<String>) {
        val options = parseArgs(args)
        val configPath = options["--config"] ?: fail("--config <bridge.json> est obligatoire")

        Log.backend = DesktopLogBackend()
        UncivGame.Current = UncivGame(true)
        UncivGame.Current.settings = GameSettings().apply {
            showTutorials = false
            turnsBetweenAutosaves = 10000
        }
        RulesetCache.loadRulesets(true)
        TileSetCache.loadTileSetConfigs(true)
        SkinCache.loadSkinConfigs(true)

        val config = IaCivBridge.init(configPath) ?: fail("configuration illisible : $configPath")
        val partieDir = IaCivBridge.partieDir!!
        IaCivBridge.savesInHook = false
        val maxTurns = options["--max-turns"]?.toInt() ?: config.partie.maxTurns

        val resume = options["--resume"]
        val gameInfo = if (resume != null) {
            IaCivBridge.resumedFromSave = resume
            UncivFiles.gameInfoFromString(File(resume).readText(Charsets.UTF_8))
        } else newGame(config)
        UncivGame.Current.gameInfo = gameInfo

        println("[IA-Civ] Partie ${gameInfo.gameId} : ${gameInfo.civilizations.joinToString { it.civID }} ; " +
            "tour ${gameInfo.turns} -> $maxTurns ; dossier $partieDir")
        IaCivBridge.ensureSession(gameInfo)
        if (resume == null) IaCivBridge.onTurnSaved(gameInfo, TurnSaver.save(gameInfo, partieDir))

        try {
            while (gameInfo.victoryData == null && gameInfo.turns < maxTurns) {
                val start = System.currentTimeMillis()
                gameInfo.nextTurn()
                val file = TurnSaver.save(gameInfo, partieDir)
                IaCivBridge.onTurnSaved(gameInfo, file)
                println("[IA-Civ] Tour ${gameInfo.turns} joué en ${System.currentTimeMillis() - start} ms -> ${file.name}")
            }
            val victory = gameInfo.victoryData
            if (victory != null) {
                println("[IA-Civ] Victoire ${victory.victoryType} de ${victory.winningCiv} au tour ${victory.victoryTurn}")
                IaCivBridge.postEvents(gameInfo, listOf(buildJsonObject {
                    put("type", "victory"); put("civ_id", victory.winningCiv); put("victory_type", victory.victoryType)
                }))
            } else println("[IA-Civ] Limite de $maxTurns tours atteinte")
        } finally {
            IaCivBridge.shutdown()
        }
        exitProcess(0)
    }

    private fun newGame(config: BridgeConfig): GameInfo {
        val setup = config.partie
        val parameters = GameParameters().apply {
            difficulty = setup.difficulty
            speed = setup.speed
            numberOfCityStates = setup.numberOfCityStates
            noBarbarians = setup.noBarbarians
            players = ArrayList(setup.nations.map { Player(it, PlayerType.AI) } + Player(Constants.spectator, PlayerType.Human))
        }
        val ruleset = RulesetCache[parameters.baseRuleset]!!
        parameters.players.forEach { it.setNationTransient(ruleset) }
        parameters.victoryTypes = ArrayList(ruleset.victories.keys)
        val mapParameters = MapParameters().apply {
            mapSize = MapSize(setup.mapSize)
            type = setup.mapType
            setup.mapSeed?.let { seed = it }
        }
        val gameInfo = GameStarter.startNewGame(GameSetupInfo(parameters, mapParameters))
        if (setup.rencontreInitiale) meetAllMajors(gameInfo)
        return gameInfo
    }

    /** `partie.rencontre_initiale`: every pair of major civs meets before the first turn (test scenarios, contract M3 §8) */
    private fun meetAllMajors(gameInfo: GameInfo) {
        val majors = gameInfo.civilizations.filter { it.isMajorCiv() && !it.isSpectator() && !it.isBarbarian }
        val met = ArrayList<String>()
        for ((index, civ) in majors.withIndex()) {
            for (other in majors.drop(index + 1)) {
                if (civ.knows(other)) continue
                civ.diplomacyFunctions.makeCivilizationsMeet(other)
                met += "${civ.civID}-${other.civID}"
            }
        }
        println("[IA-Civ] Rencontre initiale : ${met.ifEmpty { listOf("aucune paire nouvelle") }.joinToString(", ")}")
    }

    private fun parseArgs(args: Array<String>): Map<String, String> {
        val options = HashMap<String, String>()
        var index = 0
        while (index < args.size) {
            val key = args[index]
            if (!key.startsWith("--") || index + 1 >= args.size) fail("argument inattendu : $key")
            options[key] = args[index + 1]
            index += 2
        }
        return options
    }

    private fun fail(message: String): Nothing {
        System.err.println("[IA-Civ] $message")
        System.err.println("Usage : java -cp Unciv.jar com.unciv.app.desktop.IaCivHeadlessLauncher --config <bridge.json> [--resume <turn_NNN.json>] [--max-turns N]")
        exitProcess(2)
    }
}
