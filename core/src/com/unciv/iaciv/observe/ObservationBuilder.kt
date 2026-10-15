package com.unciv.iaciv.observe

import com.unciv.iaciv.dto.AlertDto
import com.unciv.iaciv.dto.CityDto
import com.unciv.iaciv.dto.CityStateDto
import com.unciv.iaciv.dto.DiplomacyDto
import com.unciv.iaciv.dto.KnownCityDto
import com.unciv.iaciv.dto.ObservationDto
import com.unciv.iaciv.dto.PoliciesDto
import com.unciv.iaciv.dto.QueueItemDto
import com.unciv.iaciv.dto.ResearchDto
import com.unciv.iaciv.dto.ResourcesDto
import com.unciv.iaciv.dto.SummaryDto
import com.unciv.iaciv.dto.UnitsDto
import com.unciv.iaciv.dto.WorldDto
import com.unciv.iaciv.dto.YieldsDto
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.diplomacy.DiplomacyFlags
import com.unciv.logic.civilization.diplomacy.DiplomaticModifiers
import com.unciv.logic.civilization.diplomacy.DiplomaticStatus
import com.unciv.models.ruleset.PerpetualConstruction
import com.unciv.models.ruleset.tile.ResourceType
import com.unciv.models.stats.Stats
import com.unciv.ui.screens.victoryscreen.RankingType
import com.unciv.view.CityView
import com.unciv.view.ForeignCivView
import com.unciv.view.GameView
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Builds the observation of one civ (contract §4.1) through [GameView] so that fog of war is respected:
 * foreign cities only if their center tile is explored ([ForeignCivView.cities]), foreign units only if visible
 * ([com.unciv.view.TileView.getVisibleUnits]). Own-civ data may read the game objects directly (the civ knows its own
 * state); for other civs we only read what the in-game screens show (diplomacy screen, rankings, wonders overview).
 * Never keeps references to game objects: the result is plain DTOs.
 */
class ObservationBuilder(private val gameView: GameView) {
    private val civView = gameView.civView
    private val civ: Civilization = civView.getCiv()
    private val ruleset = civ.gameInfo.ruleset

    /** Major civs we have met (no spectator, no barbarians, no city-states), alive or not */
    private val knownMajors: List<ForeignCivView> = civView.getKnownCivs()
        .filter { it.getNation().isMajorCiv && !it.getNation().isSpectator && !it.isBarbarian() }
        .sortedBy { it.civID }

    fun build(): ObservationDto = ObservationDto(
        summary = summary(),
        alerts = alerts(),
        cities = civView.cities().map { city(it) },
        units = units(),
        research = research(),
        policies = policies(),
        resources = resources(),
        diplomacy = knownMajors.map { diplomacy(it) },
        cityStates = cityStates(),
        world = world(),
    )

    private fun summary(): SummaryDto {
        val next = civ.stats.statsForNextTurn
        val rivals = knownMajors.map { it.getCiv() }.filter { it.isAlive() }
        fun rankOf(value: (Civilization) -> Int): Int = 1 + rivals.count { value(it) > value(civ) }
        return SummaryDto(
            era = civView.getEraName(),
            eraNumber = civView.getEraNumber(),
            gold = civView.gold,
            goldPerTurn = next.gold.roundToInt(),
            sciencePerTurn = next.science.roundToInt(),
            culturePerTurn = next.culture.roundToInt(),
            faithPerTurn = next.faith.roundToInt(),
            happiness = civView.getHappiness(),
            goldenAgeTurns = civ.goldenAges.turnsLeftForCurrentGoldenAge,
            score = civ.calculateTotalScore().roundToInt(),
            militaryStrength = civ.getStatForRanking(RankingType.Force),
            rank = mapOf(
                "score" to rankOf { it.calculateTotalScore().roundToInt() },
                "force" to rankOf { it.getStatForRanking(RankingType.Force) },
                "population" to rankOf { it.getStatForRanking(RankingType.Population) },
            ),
            victoriesAvailable = civView.getAvailableVictories().map { it.name },
        )
    }

    /** The briefing's "red list": simple rules, computed by the bridge (contract §4.1) */
    private fun alerts(): List<AlertDto> {
        val alerts = ArrayList<AlertDto>()
        for (cityView in civView.cities()) {
            val cityId = cityView.city.id
            val starvation = cityView.getNumTurnsToStarvation()
            if (cityView.isStarving() || (starvation != null && starvation <= 5))
                alerts += AlertDto("city_starving", cityId, "${cityView.name} : famine dans ${starvation ?: 0} tour(s)")
            if (cityView.getHealth() < cityView.getMaxHealth())
                alerts += AlertDto("city_damaged", cityId, "${cityView.name} : ${cityView.getHealth()}/${cityView.getMaxHealth()} PV")
            if (cityView.isInResistance())
                alerts += AlertDto("city_in_resistance", cityId, "${cityView.name} : en résistance")
            val hostile = cityView.centerTile().getVisibleTilesInDistance(ENEMY_SCAN_RADIUS)
                .flatMap { it.getVisibleUnits() }
                .filter { it.isMilitary() && (it.civ().isBarbarian() || it.civ().isAtWarWith(civView)) }
                .toList()
            if (hostile.isNotEmpty()) {
                val byCiv = hostile.groupingBy { it.civName }.eachCount().entries.joinToString(", ") { "${it.value} ${it.key}" }
                alerts += AlertDto("enemy_units_near", cityId, "${cityView.name} : unités ennemies à $ENEMY_SCAN_RADIUS cases ou moins ($byCiv)")
            }
        }
        val goldPerTurn = civ.stats.statsForNextTurn.gold.roundToInt()
        if (goldPerTurn < 0 && civView.gold + goldPerTurn * 5 < 0)
            alerts += AlertDto("bankruptcy_risk", null, "Trésor : ${civView.gold} or, $goldPerTurn par tour")
        if (civView.getHappiness() < 0)
            alerts += AlertDto("unhappiness", null, "Bonheur négatif : ${civView.getHappiness()}")
        for (other in knownMajors.filter { civView.isAtWarWith(it) })
            alerts += AlertDto("at_war", null, "En guerre contre ${other.civID}")
        return alerts
    }

    private fun city(cityView: CityView): CityDto {
        val constructions = cityView.constructions
        return CityDto(
            cityId = cityView.city.id,
            name = cityView.name,
            isCapital = cityView.isCapital(),
            population = cityView.getPopulationCount(),
            health = cityView.getHealth(),
            maxHealth = cityView.getMaxHealth(),
            focus = cityView.getCityFocus().name,
            foodStored = cityView.getFoodStored(),
            turnsToGrowth = cityView.getNumTurnsToNewPopulation(),
            turnsToStarvation = cityView.getNumTurnsToStarvation(),
            yields = yields(cityView.getCurrentCityStats()),
            queue = constructions.constructionQueue.map { name ->
                QueueItemDto(name, if (PerpetualConstruction.isNamePerpetual(name)) null else constructions.turnsToConstruction(name))
            },
            buildings = cityView.getBuiltBuildings().map { it.name }.sorted().toList(),
            garrison = cityView.getGarrison()?.name,
            isPuppet = cityView.isPuppet(),
            isBeingRazed = cityView.isBeingRazed(),
            inResistance = cityView.isInResistance(),
        )
    }

    private fun yields(stats: Stats) = YieldsDto(
        food = stats.food.roundToInt(), production = stats.production.roundToInt(), gold = stats.gold.roundToInt(),
        science = stats.science.roundToInt(), culture = stats.culture.roundToInt(), faith = stats.faith.roundToInt(),
    )

    private fun units(): UnitsDto {
        val units = civView.getUnits()
        return UnitsDto(
            military = units.filter { it.isMilitary() }.groupingBy { it.name }.eachCount().toSortedMap(),
            civilian = units.filter { it.isCivilian() }.groupingBy { it.name }.eachCount().toSortedMap(),
            idle = units.count { it.isIdle() },
            total = units.size,
        )
    }

    private fun research(): ResearchDto {
        val current = civView.currentTechnologyName()
        val science = civ.stats.statsForNextTurn.science
        val turns = current?.let {
            val remaining = civ.tech.remainingScienceToTech(it)
            if (science <= 0f) null else maxOf(1, ceil(remaining / science).toInt())
        }
        return ResearchDto(
            current = current,
            queue = civ.tech.techsToResearch.toList(),
            turnsToCurrent = turns,
            researchedCount = civ.tech.researchedTechnologies.size,
            freeTechs = civ.tech.freeTechs,
        )
    }

    private fun policies() = PoliciesDto(
        adopted = civ.policies.getAdoptedPolicies().sorted(),
        storedCulture = civ.policies.storedCulture,
        cultureNeeded = civ.policies.getCultureNeededForNextPolicy(),
        canAdoptNow = civView.canAdoptPolicy(),
    )

    private fun resources(): ResourcesDto {
        val byName = civView.getCivResourcesByName().filterValues { it != 0 }
        fun ofType(type: ResourceType) = byName.filterKeys { ruleset.tileResources[it]?.resourceType == type }.toSortedMap()
        return ResourcesDto(luxury = ofType(ResourceType.Luxury), strategic = ofType(ResourceType.Strategic))
    }

    private fun diplomacy(other: ForeignCivView): DiplomacyDto {
        val otherCiv = other.getCiv()
        val dm = civ.getDiplomacyManager(otherCiv)!!
        val treaties = buildList {
            if (dm.hasOpenBorders) add("Open Borders")
            if (dm.diplomaticStatus == DiplomaticStatus.DefensivePact || dm.hasFlag(DiplomacyFlags.DefensivePact)) add("Defensive Pact")
            if (dm.hasFlag(DiplomacyFlags.ResearchAgreement)) add("Research Agreement")
            if (dm.hasFlag(DiplomacyFlags.DeclarationOfFriendship)) add("Declaration of Friendship")
        }
        return DiplomacyDto(
            civId = other.civID,
            known = true,
            status = dm.diplomaticStatus.name,
            relationship = dm.relationshipLevel().name,
            opinion = dm.opinionOfOtherCiv().roundToInt(),
            treaties = treaties,
            turnsToPeaceTreatyEnd = dm.turnsToPeaceTreaty(),
            hasEmbassy = dm.hasModifier(DiplomaticModifiers.EstablishedEmbassy),
            theirScore = otherCiv.calculateTotalScore().roundToInt(),
            theirMilitaryStrength = otherCiv.getStatForRanking(RankingType.Force),
            theirEra = other.getEraName(),
            theirCitiesKnown = other.cities().map { foreignCity ->
                KnownCityDto(foreignCity.name, foreignCity.getPopulationCount(), distanceToOurBorder(foreignCity.getCenterTile()))
            },
            flags = dm.flagsCountdown.keys.sorted(),
        )
    }

    private fun distanceToOurBorder(tile: com.unciv.view.TileView): Int? {
        val ourTiles = civView.cities().flatMap { it.getTiles().toList() }
        return ourTiles.minOfOrNull { it.aerialDistanceTo(tile) }
    }

    private fun cityStates(): List<CityStateDto> = civView.getKnownCivs()
        .filter { it.isCityState && !it.isDefeated() }
        .sortedBy { it.civID }
        .map { cityState ->
            val cs = cityState.getCiv()
            CityStateDto(
                civId = cityState.civID,
                influence = cs.getDiplomacyManager(civ)?.getInfluence()?.roundToInt() ?: 0,
                ally = cs.allyCiv?.civID?.takeIf { it == civ.civID || civ.knows(it) },
                type = cs.cityStateType.name,
            )
        }

    private fun world(): WorldDto {
        val visibleOwners = listOf(civ) + knownMajors.map { it.getCiv() }
        val wonders = visibleOwners.flatMap { owner ->
            owner.cities.flatMap { city -> city.cityConstructions.getBuiltBuildings().filter { it.isWonder }.map { "${it.name} (${owner.civID})" } }
        }.sorted()
        val alive = civ.gameInfo.civilizations.count { it.isMajorCiv() && !it.isSpectator() && it.isAlive() }
        return WorldDto(wondersBuiltKnown = wonders, civsAlive = alive)
    }

    companion object {
        const val ENEMY_SCAN_RADIUS = 3
    }
}
