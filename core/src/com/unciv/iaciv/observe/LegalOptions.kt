package com.unciv.iaciv.observe

import com.unciv.iaciv.diplomacy.DiplomacyContext
import com.unciv.iaciv.dto.LegalOptionsDto
import com.unciv.iaciv.dto.OfferOptionDto
import com.unciv.iaciv.dto.TradableDto
import com.unciv.logic.civilization.AlertType
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.diplomacy.Demand
import com.unciv.logic.civilization.diplomacy.DiplomacyFlags
import com.unciv.logic.civilization.diplomacy.DiplomaticStatus
import com.unciv.logic.trade.TradeLogic
import com.unciv.logic.trade.TradeOffer
import com.unciv.logic.trade.TradeOfferType
import com.unciv.models.ruleset.PerpetualConstruction
import com.unciv.models.ruleset.unique.UniqueType

/**
 * What the civ is allowed to order this turn (contract §4.1 `legal_options`, M3 §3), with the same checks the game uses
 * (points-d-ancrage-unciv.md §3.2). The orchestrator validates decrees against this list.
 */
object LegalOptions {

    fun build(civ: Civilization, ctx: DiplomacyContext = DiplomacyContext()): LegalOptionsDto {
        val ruleset = civ.gameInfo.ruleset
        val known = civ.getKnownCivs().filter { !it.isSpectator() && !it.isBarbarian && it.isAlive() }.sortedBy { it.civID }.toList()
        val majors = known.filter { it.isMajorCiv() }
        val cityStates = known.filter { it.isCityState }
        val locked = ruleset.modOptions.hasUnique(UniqueType.DiplomaticRelationshipsCannotChange)
        fun dm(other: Civilization) = civ.getDiplomacyManager(other)

        return LegalOptionsDto(
            techs = ruleset.technologies.values.filter { civ.tech.canBeResearched(it.name) }.map { it.name }.sorted(),
            policiesAdoptable = ruleset.policies.values.filter { civ.policies.isAdoptable(it) }.map { it.name }.sorted(),
            // Internal ruleset names. Not listed: the constructions the civ disabled (the bridge rejects them, OrdersApplier)
            // and "Nothing" (always buildable, but an idle city is never a sensible order)
            perCityConstructions = civ.cities.associate { city ->
                val constructions = city.cityConstructions
                val nonPerpetual = (constructions.getConstructableUnits() + constructions.getBuildableBuildings())
                    .filter { constructions.canAddToQueue(it) && it.name !in civ.disabledCityConstructions }
                    .map { it.name }
                    .sorted()
                    .toList()
                val perpetual = PerpetualConstruction.perpetualConstructionsMap.values
                    .filter { it != PerpetualConstruction.Idle && it.isBuildable(constructions) }
                    .map { it.name }
                city.id to nonPerpetual + perpetual
            },
            // majors and city-states (contract M3 §3, provisional default)
            canDeclareWarOn = if (locked) listOf() else (majors + cityStates).filter { dm(it)?.canDeclareWar() == true }.map { it.civID },
            canOfferFriendshipTo = majors.filter { canOfferFriendship(civ, it, ctx) }.map { it.civID },
            canProposePeaceTo = if (locked) listOf() else (majors + cityStates).filter { canProposePeace(civ, it, ctx) }.map { it.civID },
            tradable = majors.associate { it.civID to tradable(civ, it) },
            canDenounce = majors.filter { canDenounce(civ, it) }.map { it.civID },
            demandsAvailable = majors.associate { it.civID to demandsAvailable(civ, it, ctx) }.filterValues { it.isNotEmpty() },
        )
    }

    /** `canSignDeclarationOfFriendshipWith`, not already offered, not refused recently (contract M3 §3) */
    fun canOfferFriendship(civ: Civilization, other: Civilization, ctx: DiplomacyContext): Boolean {
        val dm = civ.getDiplomacyManager(other) ?: return false
        return civ.diplomacyFunctions.canSignDeclarationOfFriendshipWith(other)
            && !ctx.hasAlertFrom(other, AlertType.DeclarationOfFriendship, civ.civID)
            && !dm.hasFlag(DiplomacyFlags.DeclinedDeclarationOfFriendship)
    }

    /** At war with us and past the `peace_rule` delay; city-state: not at war with its ally (game's peace button) */
    fun canProposePeace(civ: Civilization, other: Civilization, ctx: DiplomacyContext): Boolean {
        if (ctx.peacePossibleIn(civ, other) != 0) return false
        if (other.isCityState && ctx.uiPeaceRule) {
            val ally = other.allyCiv
            if (ally != null && ally != civ && civ.isAtWarWith(ally)) return false
        }
        return true
    }

    /** The game's denounce button: known major, not at war, not already denounced, not friends */
    fun canDenounce(civ: Civilization, other: Civilization): Boolean {
        val dm = civ.getDiplomacyManager(other) ?: return false
        return other.isMajorCiv() && dm.diplomaticStatus != DiplomaticStatus.War
            && !dm.hasFlag(DiplomacyFlags.Denunciation) && !dm.hasFlag(DiplomacyFlags.DeclarationOfFriendship)
    }

    /** The game's demands table: shown demands, not already demanded, not already promised; not at war */
    fun demandsAvailable(civ: Civilization, other: Civilization, ctx: DiplomacyContext): List<String> {
        val dm = civ.getDiplomacyManager(other) ?: return listOf()
        if (!other.isMajorCiv() || dm.diplomaticStatus == DiplomaticStatus.War) return listOf()
        return Demand.entries.filter { demand ->
            demand.show(civ) && !dm.hasFlag(demand.agreedToDemand) && !ctx.hasAlertFrom(other, demand.demandAlert, civ.civID)
        }.map { it.name }
    }

    /** What the trade screen offers on both sides (only tradable offers, i.e. amount > 0) */
    fun tradable(civ: Civilization, other: Civilization): TradableDto {
        val logic = TradeLogic(civ, other)
        return TradableDto(
            ours = logic.ourAvailableOffers.filter { it.isTradable() }.map { option(civ, it) },
            theirs = logic.theirAvailableOffers.filter { it.isTradable() }.map { option(civ, it) },
        )
    }

    private fun option(civ: Civilization, offer: TradeOffer): OfferOptionDto {
        val label = when (offer.type) {
            TradeOfferType.City -> civ.gameInfo.getCities().firstOrNull { it.id == offer.name }
                ?.let { "${it.name} (pop ${it.population.population})" } ?: offer.name
            TradeOfferType.WarDeclaration -> "déclarer la guerre à ${offer.name}"
            TradeOfferType.PeaceProposal -> "faire la paix avec ${offer.name}"
            TradeOfferType.Introduction -> "présentation à ${offer.name}"
            else -> offer.name
        }
        return OfferOptionDto(offer.type.name, offer.name, offer.amount, offer.duration, label)
    }
}
