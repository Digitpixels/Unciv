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
    /** M3 §6 (compatible addition): `diplomacy_watch`, `display`, `pending_hold` */
    val bridgeCapabilities: List<String> = listOf(),
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
    /** M3: turns since the war started (null if not at war; a lower bound if it started before the bridge did) */
    val atWarSinceTurns: Int? = null,
    /** M3: 0 = peace may be proposed now, N = in N turns (`peace_rule`), null if not at war */
    val peacePossibleIn: Int? = null,
    val weDenounced: Boolean = false,
    val theyDenounced: Boolean = false,
    val friendship: Boolean = false,
    val defensivePact: Boolean = false,
)

@Serializable
data class CityStateDto(
    val civId: String,
    val influence: Int,
    val ally: String?,
    val type: String,
    /** M3 */
    val atWar: Boolean = false,
)

@Serializable
data class WorldDto(val wondersBuiltKnown: List<String>, val civsAlive: Int)

@Serializable
data class PendingDto(
    val tradeRequests: List<TradeRequestDto>,
    val popupAlerts: List<PopupAlertDto>,
    /** M3: our offers still waiting at the other civs */
    val outgoing: List<OutgoingOfferDto> = listOf(),
)

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
    /** M3: turns since our first turn-start that showed it */
    val ageTurns: Int = 0,
    /** M3: turns before the bridge refuses it (`pending_hold_turns`) */
    val expiresIn: Int? = null,
    /** M3: request_id of our own offer if this is a counter-proposal to it */
    val counterTo: String? = null,
)

/** `alert_id` = `PA-<AlertType>-<value>` (M3), stable from one turn to the next */
@Serializable
data class PopupAlertDto(
    val alertId: String,
    val type: String,
    val value: String,
    val ageTurns: Int = 0,
    /** null if not actionable */
    val expiresIn: Int? = null,
    /** friendship offer or demand: needs an answer (`respond_friendship` / `respond_demand`) */
    val actionable: Boolean = false,
    /** name of the `Demand` enum if this is a demand */
    val demand: String? = null,
)

/** M3: an offer of ours waiting at [to]; [requestId] is the id the target sees (`TR-…` or `PA-…`) */
@Serializable
data class OutgoingOfferDto(val requestId: String, val to: String, val kind: String, val ageTurns: Int)

/** M3 `legal_options.tradable`: one tradable offer (amount > 0); for a city `name` = city id and `label` = "name (pop N)" */
@Serializable
data class OfferOptionDto(val type: String, val name: String, val amountMax: Int, val duration: Int, val label: String)

@Serializable
data class TradableDto(val ours: List<OfferOptionDto>, val theirs: List<OfferOptionDto>)

@Serializable
data class LegalOptionsDto(
    val techs: List<String>,
    val policiesAdoptable: List<String>,
    val perCityConstructions: Map<String, List<String>>,
    /** M3: known major civs and city-states */
    val canDeclareWarOn: List<String>,
    val canOfferFriendshipTo: List<String>,
    /** M3: at war with us and past the `peace_rule` delay */
    val canProposePeaceTo: List<String>,
    /** M3: what the trade screen would show, per known living major civ */
    val tradable: Map<String, TradableDto> = mapOf(),
    /** M3 */
    val canDenounce: List<String> = listOf(),
    /** M3: `{civ_id: ["DoNotSettleNearUs", …]}` = demands we can make now */
    val demandsAvailable: Map<String, List<String>> = mapOf(),
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
    /** M3: id of the offer as its target sees it (`propose_peace`, `propose_trade`, `respond_trade` counter) */
    val requestId: String? = null,
)

@Serializable
data class FallbackDto(val reason: String, val detail: String? = null)

/** M3 §5: result of one `display[]` item. status `shown` | `rejected`; reason `unknown_kind` | `missing_field` |
 *  `unknown_civ` | `empty_text` | `no_viewer` | `exception`; [shownText] = exact text stored in the notification */
@Serializable
data class DisplayResultDto(
    val index: Int,
    val kind: String,
    val messageId: String? = null,
    val status: String,
    val reason: String? = null,
    val shownText: String? = null,
    val truncated: Boolean = false,
)

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
    /** M3 §5 */
    val displayResults: List<DisplayResultDto> = listOf(),
)

@Serializable
data class EventsRequest(
    val protocolVersion: Int = PROTOCOL_VERSION,
    val gameId: String,
    val turn: Int,
    val events: List<JsonObject>,
)

// endregion
