package com.unciv.iaciv.config

import com.unciv.iaciv.dto.iaCivJson
import kotlinx.serialization.Serializable
import java.io.File

/**
 * Content of a game's `bridge.json` (snake_case on disk, like the protocol).
 * Lookup order: explicit path (headless launcher `--config`), env `IACIV_CONFIG`, system property `iaciv.config`.
 * No config found = bridge inert, Unciv plays 100 % natively.
 */
@Serializable
data class BridgeConfig(
    /** Game folder (`parties/AAAA-MM-JJ_slug`); relative paths are resolved against the config file's folder */
    val partieDir: String = ".",
    val orchestratorUrl: String = "http://127.0.0.1:8765",
    /** civID -> faction (e.g. "America" -> "claude"); only these civs are LLM-controlled */
    val factions: Map<String, String> = mapOf(),
    val turnStartTimeoutS: Int = 30,
    val councilTimeoutS: Int = 900,
    val pollWaitS: Int = 25,
    val maxConsecutiveFallbacks: Int = 3,
    val cooldownTurns: Int = 10,
    val healthTimeoutS: Int = 2,
    val sessionTimeoutS: Int = 10,
    val turnEndTimeoutS: Int = 10,
    val eventsTimeoutS: Int = 5,
    val disabled: Boolean = false,
    /** New-game setup, read only by the headless launcher */
    val partie: PartieSetup = PartieSetup(),
) {
    companion object {
        const val ENV_CONFIG = "IACIV_CONFIG"
        const val ENV_ORCH_URL = "IACIV_ORCH_URL"
        const val ENV_DISABLED = "IACIV_DISABLED"
        const val PROPERTY_CONFIG = "iaciv.config"

        fun findConfigFile(explicitPath: String?): File? {
            val path = explicitPath
                ?: System.getenv(ENV_CONFIG)?.takeIf { it.isNotBlank() }
                ?: System.getProperty(PROPERTY_CONFIG)?.takeIf { it.isNotBlank() }
                ?: return null
            return File(path).absoluteFile
        }

        /** Reads [file] and applies the environment overrides (`IACIV_ORCH_URL`, `IACIV_DISABLED=1`) */
        fun load(file: File): BridgeConfig {
            // Notepad and Windows PowerShell 5.1 write UTF-8 with a BOM, which the JSON parser rejects
            val text = file.readText(Charsets.UTF_8).removePrefix("﻿")
            var config = iaCivJson.decodeFromString(serializer(), text)
            System.getenv(ENV_ORCH_URL)?.takeIf { it.isNotBlank() }?.let { config = config.copy(orchestratorUrl = it) }
            if (System.getenv(ENV_DISABLED) == "1") config = config.copy(disabled = true)
            return config
        }
    }

    fun resolvePartieDir(configFile: File): File {
        val dir = File(partieDir)
        return (if (dir.isAbsolute) dir else File(configFile.parentFile, partieDir)).canonicalFile
    }
}

@Serializable
data class PartieSetup(
    /** Major civs, in the order given (all PlayerType.AI); the spectator is added as the only human */
    val nations: List<String> = listOf("America", "France"),
    val mapSize: String = "Small",
    val mapType: String = "Pangaea",
    val mapSeed: Long? = null,
    val difficulty: String = "Prince",
    val speed: String = "Quick",
    val numberOfCityStates: Int = 0,
    val noBarbarians: Boolean = true,
    val maxTurns: Int = 100,
)
