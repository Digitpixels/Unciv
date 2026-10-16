package com.unciv.iaciv

import com.unciv.logic.automation.civilization.NextTurnAutomation
import com.unciv.logic.civilization.AlertType
import com.unciv.logic.civilization.diplomacy.DiplomacyFlags
import com.unciv.testing.BaseTestRunner
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files

/** H2: the native AI's diplomatic initiatives are cut for LLM civs only (contract M3 §1) */
@RunWith(BaseTestRunner::class)
class DiplomacyGuardTest {
    private lateinit var world: DiplomacyWorld
    private lateinit var dir: File

    @Before
    fun setUp() {
        IaCivBridge.resetForTests()
        world = DiplomacyWorld()
        dir = Files.createTempDirectory("iaciv-h2").toFile()
    }

    @After
    fun tearDown() {
        IaCivBridge.resetForTests()
        dir.deleteRecursively()
    }

    /** America is an LLM civ; the orchestrator URL is unreachable (the bridge falls back at once, without waiting) */
    private fun configure(disabled: Boolean = false) {
        val file = File(dir, "bridge.json")
        file.writeText("{\"orchestrator_url\": \"http://127.0.0.1:1\", \"factions\": {\"America\": \"claude\"}, \"disabled\": $disabled}")
        IaCivBridge.init(file.path)
        IaCivBridge.savesInHook = false
    }

    @Test
    fun `no bridge config - native diplomacy kept`() {
        assertFalse(IaCivHooks.ownsDiplomacy(world.us))
        assertFalse(IaCivHooks.ownsDiplomacy(world.them))
    }

    @Test
    fun `LLM civ owns its diplomacy, native civ does not`() {
        configure()
        assertTrue(IaCivHooks.ownsDiplomacy(world.us))
        assertFalse(IaCivHooks.ownsDiplomacy(world.them))
    }

    @Test
    fun `disabled bridge - native diplomacy kept`() {
        configure(disabled = true)
        assertFalse(IaCivHooks.ownsDiplomacy(world.us))
    }

    @Test
    fun `native pause gives diplomacy back to the native AI`() {
        configure()
        val turn = world.testGame.gameInfo.turns
        IaCivBridge.startNativePauseForTests("America", turn + 3)
        assertFalse(IaCivHooks.ownsDiplomacy(world.us))
        IaCivBridge.startNativePauseForTests("America", turn)
        assertTrue(IaCivHooks.ownsDiplomacy(world.us))
    }

    @Test
    fun `native diplomacy allowed by the orchestrator for this turn only`() {
        configure()
        val turn = world.testGame.gameInfo.turns
        IaCivBridge.allowNativeDiplomacyForTests("America", turn)
        assertFalse(IaCivHooks.ownsDiplomacy(world.us))
        IaCivBridge.allowNativeDiplomacyForTests("America", turn - 1)
        assertTrue("an allowance of an earlier turn has no effect", IaCivHooks.ownsDiplomacy(world.us))
    }

    /**
     * The native AI demands "do not settle near us" after a violation (`issueRequests`, inside the H2 block):
     * it happens for a native civ, never for an LLM civ.
     */
    private fun violationLeadsToDemand(): Boolean {
        val us = world.us
        val them = world.them
        us.getDiplomacyManager(them)!!.setFlag(DiplomacyFlags.SettledCitiesNearUs, 10)
        them.popupAlerts.clear()
        NextTurnAutomation.automateCivMoves(us)
        return them.popupAlerts.any { it.type == AlertType.DemandToStopSettlingCitiesNear && it.value == "America" }
    }

    @Test
    fun `global events are watched for native civs and kept locally when the orchestrator is down`() {
        configure()
        IaCivHooks.onAiTurn(world.them, true) // H1 of a native civ: base snapshot
        world.them.getDiplomacyManager(world.third)!!.declareWar()
        IaCivHooks.onAiTurnEnd(world.them, true) // H4: the war is detected, /v1/events fails
        val local = File(dir, "journaux/bridge_events.jsonl").readLines(Charsets.UTF_8)
        val war = local.single { it.contains("\"war_declared\"") }
        assertTrue(war.contains("\"event_key\":\"war:France>Germany:${world.testGame.gameInfo.turns}\""))
        assertTrue(war.contains("\"scope\":\"global\""))
        assertTrue(war.contains("\"llm_involved\":false"))
    }

    @Test
    fun `automateCivMoves issues native demands for a native civ`() {
        assertTrue("control: without the bridge the native AI makes the demand", violationLeadsToDemand())
    }

    @Test
    fun `automateCivMoves makes no native demand, war or denunciation for an LLM civ`() {
        configure()
        // a very low opinion invites the native AI to denounce or attack France (DiplomacyAutomation.denounce / declareWar)
        val dm = world.us.getDiplomacyManager(world.them)!!
        dm.setModifier(com.unciv.logic.civilization.diplomacy.DiplomaticModifiers.DeclaredWarOnUs, -100f)
        assertFalse("H2 cuts the native demand", violationLeadsToDemand())
        assertFalse("no native denunciation", dm.hasFlag(DiplomacyFlags.Denunciation))
        assertFalse("no native war", world.us.isAtWarWith(world.them))
        assertEquals("the violation flag was not consumed by the native AI", true, dm.hasFlag(DiplomacyFlags.SettledCitiesNearUs))
    }
}
