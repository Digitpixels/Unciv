package com.unciv.iaciv.observe

import com.unciv.iaciv.dto.LegalOptionsDto
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.diplomacy.DiplomaticStatus
import com.unciv.models.ruleset.PerpetualConstruction

/**
 * What the civ is allowed to order this turn (contract §4.1 `legal_options`), with the same checks the game uses
 * (points-d-ancrage-unciv.md §3.2). The orchestrator validates decrees against this list.
 */
object LegalOptions {

    fun build(civ: Civilization): LegalOptionsDto {
        val ruleset = civ.gameInfo.ruleset
        val majors = civ.getKnownCivs()
            .filter { it.isMajorCiv() && !it.isSpectator() && !it.isBarbarian && it.isAlive() }
            .sortedBy { it.civID }
            .toList()
        fun statusWith(other: Civilization) = civ.getDiplomacyManager(other)?.diplomaticStatus

        return LegalOptionsDto(
            techs = ruleset.technologies.values.filter { civ.tech.canBeResearched(it.name) }.map { it.name }.sorted(),
            policiesAdoptable = ruleset.policies.values.filter { civ.policies.isAdoptable(it) }.map { it.name }.sorted(),
            perCityConstructions = civ.cities.associate { city ->
                val constructions = city.cityConstructions
                val nonPerpetual = (constructions.getConstructableUnits() + constructions.getBuildableBuildings())
                    .filter { constructions.canAddToQueue(it) }
                    .map { it.name }
                    .sorted()
                    .toList()
                val perpetual = PerpetualConstruction.perpetualConstructionsMap.values
                    .filter { it.isBuildable(constructions) }
                    .map { it.name }
                city.id to nonPerpetual + perpetual
            },
            canDeclareWarOn = majors.filter { civ.getDiplomacyManager(it)?.canDeclareWar() == true }.map { it.civID },
            canOfferFriendshipTo = majors.filter { civ.diplomacyFunctions.canSignDeclarationOfFriendshipWith(it) }.map { it.civID },
            canProposePeaceTo = majors.filter { statusWith(it) == DiplomaticStatus.War }.map { it.civID },
        )
    }
}
