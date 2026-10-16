package com.unciv.iaciv.dto

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNamingStrategy
import kotlinx.serialization.json.JsonObject

/**
 * Messages of the IA-Civ bridge protocol v1 (game = HTTP client, orchestrator = server).
 * See jeux/unciv/mod/docs/contrat-api-pont.md. Kotlin properties are camelCase, the wire format is snake_case
 * (done once by [iaCivJson]'s naming strategy instead of one @SerialName per field). Map keys (civ ids, city ids,
 * ruleset names) are left untouched.
 */
const val PROTOCOL_VERSION = 1

@OptIn(ExperimentalSerializationApi::class)
val iaCivJson = Json {
    namingStrategy = JsonNamingStrategy.SnakeCase
    ignoreUnknownKeys = true   // adding a field is a compatible change (contract §9)
    encodeDefaults = true      // every field is always on the wire, the orchestrator validates against a schema
    explicitNulls = true
}

// region health / session

@Serializable
data class HealthResponse(
    val protocolVersion: Int? = null,
    val orchestratorVersion: String? = null,
    val accepting: Boolean = true,
    val supportedProtocols: List<Int> = listOf(),
)

@Serializable
data class PlayerDto(
    val civId: String,
    val playerType: String,
    val faction: String?,
    val llmControlled: Boolean,
    val index: Int,
)

@Serializable
data class MapDto(val size: String, val shape: String, val type: String)

@Serializable
data class SessionRequest(
    val protocolVersion: Int = PROTOCOL_VERSION,
    val gameId: String,
    val uncivVersion: String,
    val bridgeVersion: String,
    val ruleset: String,
    val mods: List<String>,
    val map: MapDto,
    val speed: String,
    val turn: Int,
    val players: List<PlayerDto>,
    val partieDir: String,
    val resumedFromSave: String?,
)

@Serializable
data class SessionResponse(
    val sessionId: String = "",
    val factionsAck: Map<String, String> = mapOf(),
    val resumeTurn: Int? = null,
)

// endregion
// region turn-start (observation)

@Serializable
data class TurnStartRequest(
    val protocolVersion: Int = PROTOCOL_VERSION,
    val gameId: String,
    val turn: Int,
    val civId: String,
    val faction: String,
    val attempt: Int,
    val deadlineS: Int,
    val observation: ObservationDto,
    val pending: PendingDto,
    /** Heterogeneous events (`turn`, `type` + type-specific fields), see EventCollector */
    val eventsSinceLast: List<JsonObject>,
    val legalOptions: LegalOptionsDto,
    /** Standing city focuses and the `order_check` results of the previous turn: `{order_id, status, detail}` */
    val activeOrdersStatus: List<JsonObject> = listOf(),
)

@Serializable
data class ObservationDto(
    val summary: SummaryDto,
    val alerts: List<AlertDto>,
    val cities: List<CityDto>,
    val units: UnitsDto,
    val research: ResearchDto,
    val policies: PoliciesDto,
    val resources: ResourcesDto,
    val diplomacy: List<DiplomacyDto>,
    val cityStates: List<CityStateDto>,
    val world: WorldDto,
)

@Serializable
data class SummaryDto(
    val era: String,
    val eraNumber: Int,
    val gold: Int,
    val goldPerTurn: Int,
    val sciencePerTurn: Int,
    val culturePerTurn: Int,
    val faithPerTurn: Int,
    val happiness: Int,
    val goldenAgeTurns: Int,
    val score: Int,
    val militaryStrength: Int,
    /** Rank among us + the major civs we know (1 = best): keys `score`, `force`, `population` */
    val rank: Map<String, Int>,
    /** Victory types available to us (progress details come later) */
    val victoriesAvailable: List<String>,
)

@Serializable
data class AlertDto(val code: String, val cityId: String? = null, val detail: String)

@Serializable
data class YieldsDto(val food: Int, val production: Int, val gold: Int, val science: Int, val culture: Int, val faith: Int)

@Serializable
data class QueueItemDto(val name: String, val turns: Int?)

@Serializable
data class CityDto(
    val cityId: String,
    val name: String,
    val isCapital: Boolean,
    val population: Int,
    val health: Int,
    val maxHealth: Int,
    val focus: String,
    val foodStored: Int,
    val turnsToGrowth: Int?,
    val turnsToStarvation: Int?,
    val yields: YieldsDto,
    val queue: List<QueueItemDto>,
    val buildings: List<String>,
    val garrison: String?,
    val isPuppet: Boolean,
    val isBeingRazed: Boolean,
    val inResistance: Boolean,
)

@Serializable
data class UnitsDto(
    val military: Map<String, Int>,
    val civilian: Map<String, Int>,
    val idle: Int,
    val total: Int,
)

@Serializable
data class ResearchDto(
    val current: String?,
    val queue: List<String>,
    val turnsToCurrent: Int?,
    val researchedCount: Int,
    val freeTechs: Int,
)

@Serializable
data class PoliciesDto(
    val adopted: List<String>,
    val storedCulture: Int,
    val cultureNeeded: Int,
    val canAdoptNow: Boolean,
)

@Serializable
data class ResourcesDto(val luxury: Map<String, Int>, val strategic: Map<String, Int>)

@Serializable
data class KnownCityDto(val name: String, val population: Int, val distanceToOurBorder: Int?)

@Serializable
data class DiplomacyDto(
    val civId: String,
    val known: Boolean,
    val status: String,
    val relationship: String,
    val opinion: Int,
    val treaties: List<String>,
    val turnsToPeaceTreatyEnd: Int,
    val hasEmbassy: Boolean,
    val theirScore: Int,
    val theirMilitaryStrength: Int,
    val theirEra: String,
    val theirCitiesKnown: List<KnownCityDto>,
    val flags: List<String>,
)

@Serializable
data class CityStateDto(val civId: String, val influence: Int, val ally: String?, val type: String)

@Serializable
data class WorldDto(val wondersBuiltKnown: List<String>, val civsAlive: Int)

@Serializable
data class PendingDto(val tradeRequests: List<TradeRequestDto>, val popupAlerts: List<PopupAlertDto>)

@Serializable
data class TradeOfferDto(val type: String, val name: String, val amount: Int, val duration: Int)

@Serializable
data class TradeRequestDto(
    val requestId: String,
    val from: String,
    val theyGive: List<TradeOfferDto>,
    val weGive: List<TradeOfferDto>,
    val isPeaceTreaty: Boolean,
    val valid: Boolean,
)

@Serializable
data class PopupAlertDto(val alertId: String, val type: String, val value: String)

@Serializable
data class LegalOptionsDto(
    val techs: List<String>,
    val policiesAdoptable: List<String>,
    val perCityConstructions: Map<String, List<String>>,
    val canDeclareWarOn: List<String>,
    val canOfferFriendshipTo: List<String>,
    val canProposePeaceTo: List<String>,
    /** Filled in M3 (trades) */
    val tradable: Map<String, JsonObject> = mapOf(),
)

// endregion
// region responses, turn-end, events

@Serializable
data class OrdersResponse(
    val protocolVersion: Int = PROTOCOL_VERSION,
    val turn: Int? = null,
    val civId: String? = null,
    val mode: String = "llm",
    val decreeId: String? = null,
    /** Kept as raw JSON: the bridge validates each order itself and rejects only the bad ones (OrdersApplier) */
    val orders: List<JsonObject> = listOf(),
    val native: JsonObject? = null,
    val display: List<JsonObject> = listOf(),
)

@Serializable
data class PendingResponse(
    val ticket: String,
    val reason: String? = null,
    val trigger: String? = null,
    val councilId: String? = null,
    val expectedWaitS: Int? = null,
)

/** Result of one item of a `set_production_queue` order: status `applied` | `rejected` */
@Serializable
data class ItemResultDto(val item: String, val status: String, val reason: String? = null, val detail: String? = null)

/** Result of one order in `turn-end.results[]`: status `applied` | `partial` | `rejected` | `ignored` */
@Serializable
data class OrderResultDto(
    val orderId: String,
    val status: String,
    val reason: String? = null,
    val detail: String? = null,
    /** Only for `set_production_queue` (one entry per requested item, in the order received) */
    val itemResults: List<ItemResultDto>? = null,
)

@Serializable
data class FallbackDto(val reason: String, val detail: String? = null)

@Serializable
data class TurnEndRequest(
    val protocolVersion: Int = PROTOCOL_VERSION,
    val gameId: String,
    val turn: Int,
    val civId: String,
    val decreeId: String?,
    val mode: String,
    val results: List<OrderResultDto>,
    val fallback: FallbackDto?,
    val timingMs: Map<String, Long>,
)

@Serializable
data class EventsRequest(
    val protocolVersion: Int = PROTOCOL_VERSION,
    val gameId: String,
    val turn: Int,
    val events: List<JsonObject>,
)

// endregion
