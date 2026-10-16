package com.unciv.iaciv.diplomacy

import com.unciv.iaciv.dto.TradeOfferDto
import com.unciv.iaciv.dto.iaCivJson
import com.unciv.logic.civilization.AlertType
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.PopupAlert
import com.unciv.logic.civilization.diplomacy.Demand
import com.unciv.logic.trade.Trade
import com.unciv.logic.trade.TradeOffersList
import com.unciv.logic.trade.TradeRequest
import kotlinx.serialization.builtins.ListSerializer
import java.security.MessageDigest

/**
 * Stable identifiers of the diplomatic objects (Unciv gives them none) and small shared rules. Pure functions on data:
 * nothing here keeps a reference to a game object.
 */
object DiplomacyIds {

    /** Alerts that need an answer from the civ that receives them (contract M3 §2) */
    private val ACTIONABLE: Set<AlertType> = Demand.entries.map { it.demandAlert }.toSet() + AlertType.DeclarationOfFriendship

    /** `PA-<AlertType>-<value>` (contract M3 §3), e.g. `PA-DeclarationOfFriendship-France` */
    fun alertId(type: AlertType, value: String) = "PA-${type.name}-$value"
    fun alertId(alert: PopupAlert) = alertId(alert.type, alert.value)

    fun isActionable(type: AlertType) = type in ACTIONABLE

    /** The demand behind an alert, or null if the alert is not a demand */
    fun demandOf(type: AlertType): Demand? = Demand.entries.firstOrNull { it.demandAlert == type }

    fun offers(list: TradeOffersList): List<TradeOfferDto> = list.map { TradeOfferDto(it.type.name, it.name, it.amount, it.duration) }

    /**
     * `TradeRequest` has no id in Unciv: `TR-<requestingCiv>-<8 hex of SHA-1 of the offers' JSON>` (contract §1),
     * stable as long as the offer does not change. [theyGive] = what the requesting civ gives, [weGive] = what it asks.
     */
    fun tradeRequestId(from: String, theyGive: List<TradeOfferDto>, weGive: List<TradeOfferDto>): String {
        val serializer = ListSerializer(TradeOfferDto.serializer())
        val canonical = iaCivJson.encodeToString(serializer, theyGive) + "|" + iaCivJson.encodeToString(serializer, weGive)
        val digest = MessageDigest.getInstance("SHA-1").digest(canonical.toByteArray(Charsets.UTF_8))
        return "TR-$from-" + digest.take(4).joinToString("") { "%02x".format(it) }
    }

    /** Id of a request as its target (the civ whose `tradeRequests` hold it) sees it */
    fun tradeRequestId(request: TradeRequest) = tradeRequestId(request.requestingCiv, offers(request.trade.theirOffers), offers(request.trade.ourOffers))

    /** Order-independent fingerprint of a trade (offers without duration), to find it again in `DiplomacyManager.trades` */
    fun tradeFingerprint(trade: Trade): String = fingerprint(offers(trade.ourOffers), offers(trade.theirOffers))

    fun fingerprint(ours: List<TradeOfferDto>, theirs: List<TradeOfferDto>): String {
        fun side(list: List<TradeOfferDto>) = list.map { "${it.type}:${it.name}:${it.amount}" }.sorted().joinToString(",")
        return side(ours) + "|" + side(theirs)
    }

    /** A copy of a request that shares nothing with the game (`Trade.reverse().reverse()`, contract §2) */
    fun detachedCopy(request: TradeRequest) = TradeRequest(request.requestingCiv, request.trade.reverse().reverse())

    /** Civs that take part in diplomacy: no spectator, no barbarians */
    fun isPlayerCiv(civ: Civilization) = !civ.isSpectator() && !civ.isBarbarian

    /** Civ by id, or by name (war-declaration alerts carry `civName`, equal to `civID` for the nations of the base ruleset) */
    fun resolveCivId(civ: Civilization, idOrName: String): String =
        civ.gameInfo.getCivilizationOrNull(idOrName)?.civID
            ?: civ.gameInfo.civilizations.firstOrNull { it.civName == idOrName }?.civID
            ?: idOrName

    /** Same key as the contract's pair keys: the two ids sorted, joined by `|` */
    fun pairKey(a: String, b: String) = if (a <= b) "$a|$b" else "$b|$a"
}
