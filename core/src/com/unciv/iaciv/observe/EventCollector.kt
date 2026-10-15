package com.unciv.iaciv.observe

import com.unciv.iaciv.dto.PendingDto
import com.unciv.iaciv.dto.PopupAlertDto
import com.unciv.iaciv.dto.TradeOfferDto
import com.unciv.iaciv.dto.TradeRequestDto
import com.unciv.iaciv.dto.iaCivJson
import com.unciv.logic.civilization.AlertType
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.trade.TradeEvaluation
import com.unciv.logic.trade.TradeOffersList
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.security.MessageDigest

/**
 * What a civ remembers between two turn-starts: identifiers and names only, never game objects
 * (in UI mode every turn runs on a clone of GameInfo, see architecture.md §4).
 */
data class CivSnapshot(
    val turn: Int,
    val cities: Map<String, String>,
    val eraNumber: Int,
    val eraName: String,
    val techs: Set<String>,
    val policies: Set<String>,
    val atWarWith: Set<String>,
    val knownCivs: Set<String>,
    val goldenAge: Boolean,
) {
    companion object {
        fun of(civ: Civilization) = CivSnapshot(
            turn = civ.gameInfo.turns,
            cities = civ.cities.associate { it.id to it.name },
            eraNumber = civ.getEraNumber(),
            eraName = civ.getEra().name,
            techs = civ.tech.techsResearched.toSet(),
            policies = civ.policies.getAdoptedPolicies().toSet(),
            atWarWith = civ.getKnownCivs().filter { civ.isAtWarWith(it) && !it.isBarbarian }.map { it.civID }.toSet(),
            knownCivs = civ.getKnownCivs().filter { !it.isSpectator() && !it.isBarbarian }.map { it.civID }.toSet(),
            goldenAge = civ.goldenAges.isGoldenAge(),
        )
    }
}

object EventCollector {

    /** Offers and alerts waiting for an answer from [civ] (read BEFORE NextTurnAutomation answers and clears them) */
    fun pending(civ: Civilization): PendingDto {
        val trades = civ.tradeRequests.map { request ->
            val from = civ.gameInfo.getCivilization(request.requestingCiv)
            val theyGive = offers(request.trade.theirOffers)
            val weGive = offers(request.trade.ourOffers)
            TradeRequestDto(
                requestId = tradeRequestId(request.requestingCiv, theyGive, weGive),
                from = request.requestingCiv,
                theyGive = theyGive,
                weGive = weGive,
                isPeaceTreaty = request.trade.isPeaceTreaty(),
                valid = TradeEvaluation().isTradeValid(request.trade, civ, from),
            )
        }
        val turn = civ.gameInfo.turns
        val alerts = civ.popupAlerts.mapIndexed { index, alert -> PopupAlertDto("PA-$turn-$index", alert.type.name, alert.value) }
        return PendingDto(trades, alerts)
    }

    /**
     * `TradeRequest` has no id in Unciv: `TR-<requestingCiv>-<8 hex of SHA-1 of the offers' JSON>` (contract §1),
     * stable as long as the offer does not change.
     */
    fun tradeRequestId(from: String, theyGive: List<TradeOfferDto>, weGive: List<TradeOfferDto>): String {
        val serializer = ListSerializer(TradeOfferDto.serializer())
        val canonical = iaCivJson.encodeToString(serializer, theyGive) + "|" + iaCivJson.encodeToString(serializer, weGive)
        val digest = MessageDigest.getInstance("SHA-1").digest(canonical.toByteArray(Charsets.UTF_8))
        return "TR-$from-" + digest.take(4).joinToString("") { "%02x".format(it) }
    }

    private fun offers(list: TradeOffersList) = list.map { TradeOfferDto(it.type.name, it.name, it.amount, it.duration) }

    /** Alerts already covered by the snapshot diff ([eventsSince]): not repeated as events */
    private val alertsCoveredByDiff = setOf(AlertType.FirstContact, AlertType.TechResearched, AlertType.GoldenAge)

    /** Events derived from the popup alerts addressed to [civ] (they are consumed by the native AI right after H1) */
    fun eventsFromPopupAlerts(civ: Civilization): List<JsonObject> {
        val turn = civ.gameInfo.turns
        return civ.popupAlerts.filter { it.type !in alertsCoveredByDiff }.map { alert ->
            val type = when (alert.type) {
                AlertType.WarDeclaration -> "war_declared_on_us"
                AlertType.DeclarationOfFriendship -> "friendship_offered"
                AlertType.Denounced -> "denounced_by"
                AlertType.CityConquered -> "city_conquered"
                AlertType.WonderBuilt -> "wonder_built"
                AlertType.Defeated -> "civ_defeated"
                else -> "alert_" + alert.type.name
            }
            buildJsonObject {
                put("turn", turn)
                put("type", type)
                put("alert_type", alert.type.name)
                put("value", alert.value)
            }
        }
    }

    /** Diff between the last successfully sent snapshot and now (contract §4.1 `events_since_last`) */
    fun eventsSince(previous: CivSnapshot?, current: CivSnapshot, civ: Civilization): List<JsonObject> {
        if (previous == null) return emptyList()
        val turn = current.turn
        val events = ArrayList<JsonObject>()
        fun event(type: String, vararg fields: Pair<String, String?>) {
            events += buildJsonObject {
                put("turn", turn)
                put("type", type)
                for ((key, value) in fields) put(key, value)
            }
        }

        for ((id, name) in current.cities - previous.cities.keys) {
            val city = civ.cities.firstOrNull { it.id == id }
            val founder = city?.foundingCivObject?.civID
            if (founder == null || founder == civ.civID) event("city_founded", "city_id" to id, "city_name" to name)
            else event("city_taken", "city_id" to id, "city_name" to name, "from" to founder)
        }
        for ((id, name) in previous.cities - current.cities.keys) {
            val newOwner = civ.gameInfo.civilizations.firstOrNull { other -> other.cities.any { it.id == id } }
            if (newOwner == null) event("city_destroyed", "city_id" to id, "city_name" to name)
            else event("city_lost", "city_id" to id, "city_name" to name, "by" to newOwner.civID)
        }
        if (current.eraNumber > previous.eraNumber) event("era_entered", "era" to current.eraName)
        for (tech in (current.techs - previous.techs).sorted()) event("tech_researched", "tech" to tech)
        for (policy in (current.policies - previous.policies).sorted()) event("policy_adopted", "policy" to policy)
        for (other in (current.atWarWith - previous.atWarWith).sorted()) event("war_started", "with" to other)
        for (other in (previous.atWarWith - current.atWarWith).sorted()) event("peace_signed", "with" to other)
        for (other in (current.knownCivs - previous.knownCivs).sorted()) event("first_contact", "with" to other)
        if (current.goldenAge && !previous.goldenAge) event("golden_age_started")
        if (!current.goldenAge && previous.goldenAge) event("golden_age_ended")
        return events
    }
}
