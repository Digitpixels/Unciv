package com.unciv.iaciv.save

import com.unciv.logic.GameInfo
import com.unciv.logic.files.UncivFiles
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Locale

/**
 * Writes `sauvegardes/turn_NNN.json` (plain JSON, the game's own format) in the game folder.
 * Does not use UncivGame.Current.files, which does not exist without UI (ConsoleLauncher / headless launcher).
 */
object TurnSaver {
    fun fileFor(partieDir: File, turn: Int) = File(File(partieDir, "sauvegardes"), String.format(Locale.ROOT, "turn_%03d.json", turn))

    fun save(gameInfo: GameInfo, partieDir: File): File {
        val target = fileFor(partieDir, gameInfo.turns)
        target.parentFile.mkdirs()
        val temp = File(target.parentFile, target.name + ".tmp")
        temp.writeText(UncivFiles.gameInfoToString(gameInfo, forceZip = false, updateChecksum = false), Charsets.UTF_8)
        Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        return target
    }
}
