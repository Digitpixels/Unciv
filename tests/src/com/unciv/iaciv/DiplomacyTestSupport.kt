package com.unciv.iaciv

import com.unciv.Constants
import com.unciv.iaciv.dto.OrderResultDto
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.trade.TradeLogic
import com.unciv.logic.trade.TradeOffer
import com.unciv.logic.trade.TradeOfferType
import com.unciv.logic.trade.TradeRequest
import com.unciv.testing.TestGame
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals

/** Shared helpers of the M3 tests: a small world with three majors and a city-state, orders as JSON */
internal class DiplomacyWorld {
    val testGame = TestGame().apply { makeHexagonalMap(8) }
    val us: Civilization = testGame.addCiv(testGame.ruleset.nations["America"]!!)
    val them: Civilization = testGame.addCiv(testGame.ruleset.nations["France"]!!)
    val third: Civilization = testGame.addCiv(testGame.ruleset.nations["Germany"]!!)
    val cityState: Civilization = testGame.addCiv(cityStateType = "Militaristic")
    private var counter = 0

    init {
        testGame.addCity(us, testGame.getTile(-4, -4))
        testGame.addCity(them, testGame.getTile(4, 4))
        testGame.addCity(third, testGame.getTile(-3, 3))
        testGame.addCity(cityState, testGame.getTile(3, -3))
        us.diplomacyFunctions.makeCivilizationsMeet(them)
        us.diplomacyFunctions.makeCivilizationsMeet(cityState)
        them.diplomacyFunctions.makeCivilizationsMeet(third)
        // the first-contact alerts are not part of the tests
        for (civ in listOf(us, them, third, cityState)) civ.popupAlerts.clear()
    }

    /** A civ with no city and no unit: defeated */
    fun addDeadCiv(): Civilization {
        val dead = testGame.addCiv(testGame.ruleset.nations["China"]!!)
        us.diplomacyFunctions.makeCivilizationsMeet(dead)
        us.popupAlerts.clear()
        dead.popupAlerts.clear()
        return dead
    }

    fun order(type: String?, vararg fields: Pair<String, Any?>): JsonObject = buildJsonObject {
        put("order_id", "O-${++counter}")
        if (type != null) put("type", type)
        for ((key, value) in fields) put(key, json(value))
    }

    fun item(type: String, name: String, amount: Int? = null): JsonObject = buildJsonObject {
        put("type", type)
        put("name", name)
        if (amount != null) put("amount", amount)
    }

    private fun json(value: Any?): JsonElement = when (value) {
        null -> JsonNull
        is JsonElement -> value
        is String -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        is List<*> -> JsonArray(value.map { json(it) })
        is Map<*, *> -> JsonObject(value.entries.associate { (k, v) -> k as String to json(v) })
        else -> error("unsupported $value")
    }

    /** [from] sends [to] a trade request (gold from [from] for [goldAsked] of [to]'s gold), as the native AI would */
    fun sendTrade(from: Civilization, to: Civilization, goldGiven: Int = 0, goldAsked: Int = 0, peace: Boolean = false): TradeRequest {
        val logic = TradeLogic(from, to)
        val speed = from.gameInfo.speed
        if (peace) {
            logic.currentTrade.ourOffers.add(TradeOffer(Constants.peaceTreaty, TradeOfferType.Treaty, speed = speed))
            logic.currentTrade.theirOffers.add(TradeOffer(Constants.peaceTreaty, TradeOfferType.Treaty, speed = speed))
        }
        if (goldGiven > 0) logic.currentTrade.ourOffers.add(TradeOffer(Constants.flatGold, TradeOfferType.Gold, goldGiven, speed))
        if (goldAsked > 0) logic.currentTrade.theirOffers.add(TradeOffer(Constants.flatGold, TradeOfferType.Gold, goldAsked, speed))
        val request = TradeRequest(from.civID, logic.currentTrade.reverse())
        to.tradeRequests.add(request)
        return request
    }
}

internal fun assertRejected(reason: String, result: OrderResultDto) {
    assertEquals("status of ${result.orderId} (${result.reason} ${result.detail})", "rejected", result.status)
    assertEquals("reason of ${result.orderId} (${result.detail})", reason, result.reason)
}

internal fun assertApplied(result: OrderResultDto) =
    assertEquals("${result.orderId} : ${result.reason} ${result.detail}", "applied", result.status)

internal fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
