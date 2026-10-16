package com.unciv.iaciv.orders

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/** Light control-flow exception for a rejected order (no stack trace): becomes `rejected` + [reason] */
internal class OrderReject(val reason: String, val detail: String? = null) : RuntimeException(reason) {
    override fun fillInStackTrace(): Throwable = this
}

// JSON helpers of the order parsers (JsonNull is a JsonPrimitive: test it first)

internal fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull && it.isString }?.content

internal fun JsonObject.reqStr(key: String): String = when (val value = this[key]) {
    null, JsonNull -> throw OrderReject("missing_field", key)
    is JsonPrimitive -> if (value.isString) value.content else throw OrderReject("invalid_field", key)
    else -> throw OrderReject("invalid_field", key)
}

internal fun JsonObject.reqStrList(key: String): List<String> = when (val value = this[key]) {
    null, JsonNull -> throw OrderReject("missing_field", key)
    is JsonArray -> value.map { element ->
        (element as? JsonPrimitive)?.takeIf { it !is JsonNull && it.isString }?.content ?: throw OrderReject("invalid_field", key)
    }
    else -> throw OrderReject("invalid_field", key)
}

/** Optional integer field (absent or null -> null; anything else that is not an integer -> `invalid_field`) */
internal fun JsonObject.optInt(key: String): Int? = when (val value = this[key]) {
    null, JsonNull -> null
    is JsonPrimitive -> if (value.isString) throw OrderReject("invalid_field", key) else value.intOrNull ?: throw OrderReject("invalid_field", key)
    else -> throw OrderReject("invalid_field", key)
}

/** Optional array of objects (absent or null -> empty list) */
internal fun JsonObject.optObjectList(key: String): List<JsonObject> = when (val value = this[key]) {
    null, JsonNull -> listOf()
    is JsonArray -> value.map { it as? JsonObject ?: throw OrderReject("invalid_field", key) }
    else -> throw OrderReject("invalid_field", key)
}

internal fun JsonObject.optObject(key: String): JsonObject? = when (val value = this[key]) {
    null, JsonNull -> null
    is JsonObject -> value
    else -> throw OrderReject("invalid_field", key)
}
