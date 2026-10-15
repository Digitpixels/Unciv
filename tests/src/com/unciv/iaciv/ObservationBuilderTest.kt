package com.unciv.iaciv

import com.unciv.iaciv.config.BridgeConfig
import com.unciv.iaciv.dto.ObservationDto
import com.unciv.iaciv.dto.TradeOfferDto
import com.unciv.iaciv.dto.iaCivJson
import com.unciv.iaciv.observe.EventCollector
import com.unciv.iaciv.observe.LegalOptions
import com.unciv.iaciv.observe.ObservationBuilder
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.diplomacy.DiplomaticStatus
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import com.unciv.view.GameView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files

@RunWith(BaseTestRunner::class)
class ObservationBuilderTest {
    private val testGame = TestGame().apply { makeHexagonalMap(8) }
    private lateinit var us: Civilization
    private lateinit var them: Civilization

    @Before
    fun setUp() {
        us = testGame.addCiv(testGame.ruleset.nations["America"]!!)
        them = testGame.addCiv(testGame.ruleset.nations["France"]!!)
        testGame.addCity(us, testGame.getTile(-4, -4))
        testGame.addCity(them, testGame.getTile(4, 4))
        us.diplomacyFunctions.makeCivilizationsMeet(them)
    }

    private fun observe(civ: Civilization = us): ObservationDto = ObservationBuilder(GameView(testGame.gameInfo, civ)).build()

    private fun atWar() {
        us.getDiplomacyManager(them)!!.diplomaticStatus = DiplomaticStatus.War
        them.getDiplomacyManager(us)!!.diplomaticStatus = DiplomaticStatus.War
    }

    @Test
    fun `foreign city stays hidden while its center tile is unexplored`() {
        val france = observe().diplomacy.single { it.civId == "France" }
        assertTrue("Paris must not leak through the fog of war", france.theirCitiesKnown.isEmpty())

        them.cities.single().getCenterTile().setExplored(us, true)
        val explored = observe().diplomacy.single { it.civId == "France" }
        assertEquals(listOf(them.cities.single().name), explored.theirCitiesKnown.map { it.name })
        assertTrue(explored.theirCitiesKnown.single().distanceToOurBorder!! > 0)
    }

    @Test
    fun `enemy units near a city are reported only when visible`() {
        atWar()
        val warriorTile = testGame.getTile(-2, -4)
        testGame.addUnit("Warrior", them, warriorTile)

        us.viewableTiles = setOf()
        assertFalse("an unseen unit must not be reported", observe().alerts.any { it.code == "enemy_units_near" })

        us.viewableTiles = setOf(warriorTile)
        warriorTile.setExplored(us, true)
        val alert = observe().alerts.single { it.code == "enemy_units_near" }
        assertEquals(us.cities.single().id, alert.cityId)
        assertTrue(observe().alerts.any { it.code == "at_war" })
    }

    @Test
    fun `own cities, units and research are described`() {
        testGame.addUnit("Warrior", us, testGame.getTile(-4, -3))
        testGame.addUnit("Worker", us, testGame.getTile(-3, -4))
        val observation = observe()
        val city = observation.cities.single()
        assertEquals(us.cities.single().id, city.cityId)
        assertTrue(city.isCapital)
        assertEquals(1, observation.units.military["Warrior"])
        assertEquals(1, observation.units.civilian["Worker"])
        assertEquals(us.tech.researchedTechnologies.size, observation.research.researchedCount)
        assertEquals("only France is a known major civ", listOf("France"), observation.diplomacy.map { it.civId })
    }

    @Test
    fun `legal options follow the game's own rules`() {
        val legal = LegalOptions.build(us)
        assertTrue(legal.techs.isNotEmpty())
        assertTrue(legal.techs.all { us.tech.canBeResearched(it) })
        val cityId = us.cities.single().id
        assertTrue("a capital can always build something", legal.perCityConstructions[cityId]!!.isNotEmpty())
        assertTrue("at peace we can declare war on France", "France" in legal.canDeclareWarOn)
        assertTrue(legal.canProposePeaceTo.isEmpty())

        atWar()
        val atWar = LegalOptions.build(us)
        assertFalse("France" in atWar.canDeclareWarOn)
        assertEquals(listOf("France"), atWar.canProposePeaceTo)
    }

    @Test
    fun `observation goes on the wire in snake_case with untouched map keys`() {
        val json = iaCivJson.encodeToString(ObservationDto.serializer(), observe())
        assertTrue(json.contains("\"gold_per_turn\""))
        assertTrue(json.contains("\"city_states\""))
        assertTrue(json.contains("\"their_cities_known\""))
        assertFalse(json.contains("goldPerTurn"))
        val legal = iaCivJson.encodeToString(com.unciv.iaciv.dto.LegalOptionsDto.serializer(), LegalOptions.build(us))
        assertTrue("city ids are map keys and must stay as they are", legal.contains("\"${us.cities.single().id}\""))
        assertTrue("an early-game observation is a few KB", json.length < 20_000)
    }

    @Test
    fun `trade request id is stable and depends on the offer`() {
        val peace = listOf(TradeOfferDto("Treaty", "Peace Treaty", 1, 10))
        val gold = listOf(TradeOfferDto("Gold", "Gold", 100, -1))
        val id = EventCollector.tradeRequestId("France", peace, peace)
        assertEquals(id, EventCollector.tradeRequestId("France", peace, peace))
        assertTrue(Regex("^TR-France-[0-9a-f]{8}$").matches(id))
        assertNotEquals(id, EventCollector.tradeRequestId("France", peace + gold, peace))
    }

    @Test
    fun `bridge config accepts a BOM and resolves the game folder next to it`() {
        val dir = Files.createTempDirectory("iaciv-config").toFile()
        try {
            val file = File(dir, "bridge.json")
            file.writeText("﻿{\"partie_dir\": \"ma-partie\", \"factions\": {\"America\": \"claude\"}, \"partie\": {\"max_turns\": 12}}", Charsets.UTF_8)
            val config = BridgeConfig.load(file)
            assertEquals(mapOf("America" to "claude"), config.factions)
            assertEquals(12, config.partie.maxTurns)
            assertEquals(30, config.turnStartTimeoutS)
            assertEquals(File(dir, "ma-partie").canonicalFile, config.resolvePartieDir(file))
        } finally {
            dir.deleteRecursively()
        }
    }
}
