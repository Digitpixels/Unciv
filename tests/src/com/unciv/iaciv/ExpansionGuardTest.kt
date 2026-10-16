package com.unciv.iaciv

import com.unciv.logic.civilization.Civilization
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files

/** H5: the native settler logic (`trainSettler`) is skipped for LLM civs only, and comes back during a native pause */
@RunWith(BaseTestRunner::class)
class ExpansionGuardTest {
    private val testGame = TestGame().apply { makeHexagonalMap(8) }
    private lateinit var llmCiv: Civilization
    private lateinit var nativeCiv: Civilization
    private lateinit var dir: File

    @Before
    fun setUp() {
        llmCiv = testGame.addCiv(testGame.ruleset.nations["America"]!!)
        nativeCiv = testGame.addCiv(testGame.ruleset.nations["France"]!!)
        dir = Files.createTempDirectory("iaciv-h5").toFile()
        IaCivBridge.resetForTests()
    }

    @After
    fun tearDown() {
        IaCivBridge.resetForTests()
        dir.deleteRecursively()
    }

    private fun configure(disabled: Boolean = false) {
        val file = File(dir, "bridge.json")
        file.writeText("{\"orchestrator_url\": \"http://127.0.0.1:1\", \"factions\": {\"America\": \"claude\"}, \"disabled\": $disabled}")
        IaCivBridge.init(file.path)
    }

    @Test
    fun `no bridge config - native settler logic kept`() {
        assertFalse(IaCivHooks.ownsExpansion(llmCiv))
        assertFalse(IaCivHooks.ownsExpansion(nativeCiv))
    }

    @Test
    fun `LLM civ owns its expansion, native civ does not`() {
        configure()
        assertTrue(IaCivHooks.ownsExpansion(llmCiv))
        assertFalse(IaCivHooks.ownsExpansion(nativeCiv))
    }

    @Test
    fun `disabled bridge - native settler logic kept`() {
        configure(disabled = true)
        assertFalse(IaCivHooks.ownsExpansion(llmCiv))
    }

    @Test
    fun `native pause after repeated fallbacks gives expansion back to the native AI`() {
        configure()
        val turn = testGame.gameInfo.turns
        IaCivBridge.startNativePauseForTests("America", turn + 3)
        assertFalse(IaCivHooks.ownsExpansion(llmCiv))
        IaCivBridge.startNativePauseForTests("America", turn)
        assertTrue(IaCivHooks.ownsExpansion(llmCiv))
    }
}
