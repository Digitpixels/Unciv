package com.unciv.iaciv

import com.unciv.utils.Log
import java.io.File
import java.time.Instant
import java.time.temporal.ChronoUnit

/** Technical log of the bridge: `journaux/bridge.log` of the game folder, plus Unciv's own log (prefix `[IA-Civ]`). */
internal class BridgeLog(private val file: File?) {
    init {
        file?.parentFile?.mkdirs()
    }

    @Synchronized
    fun info(message: String) = write("INFO ", message)

    @Synchronized
    fun warn(message: String) = write("WARN ", message)

    @Synchronized
    fun error(message: String, throwable: Throwable? = null) {
        write("ERROR", message + (throwable?.let { " | ${it::class.simpleName}: ${it.message}" } ?: ""))
        if (throwable != null) Log.error("[IA-Civ] $message", throwable)
    }

    private fun write(level: String, message: String) {
        Log.debug("[IA-Civ] %s", message)
        if (file == null) return
        try {
            file.appendText("${Instant.now().truncatedTo(ChronoUnit.MILLIS)} $level $message\n", Charsets.UTF_8)
        } catch (_: Exception) {
            // a log line is never worth breaking a turn
        }
    }
}
