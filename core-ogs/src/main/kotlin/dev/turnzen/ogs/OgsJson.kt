package dev.turnzen.ogs

import dev.turnzen.goserver.GoServerException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/** Lenient accessors: OGS omits fields freely, and sends numbers as strings in places. */
internal object OgsJson {
    val json = Json { ignoreUnknownKeys = true }

    fun parseObject(
        body: String,
        what: String,
    ): JsonObject =
        try {
            json.parseToJsonElement(body) as? JsonObject
        } catch (e: IllegalArgumentException) {
            null
        } ?: throw GoServerException.Protocol("Online Go Server sent $what that is not a JSON object")

    /**
     * Some fields (`time_control_parameters` in game lists) hold JSON encoded as a string.
     * Accepts either form.
     */
    fun objectOrEncoded(element: JsonElement?): JsonObject? =
        when (element) {
            is JsonObject -> {
                element
            }

            is JsonPrimitive -> {
                if (element.isString) {
                    runCatching { json.parseToJsonElement(element.content) as? JsonObject }.getOrNull()
                } else {
                    null
                }
            }

            else -> {
                null
            }
        }
}

internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

internal fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray

internal fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content

internal fun JsonObject.long(key: String): Long? {
    val p = this[key] as? JsonPrimitive ?: return null
    if (p is JsonNull) return null
    return p.longOrNull ?: p.doubleOrNull?.toLong() ?: p.content.toLongOrNull()
}

internal fun JsonObject.int(key: String): Int? = long(key)?.toInt()

internal fun JsonObject.double(key: String): Double? {
    val p = this[key] as? JsonPrimitive ?: return null
    if (p is JsonNull) return null
    return p.doubleOrNull ?: p.content.toDoubleOrNull()
}

internal fun JsonObject.bool(key: String): Boolean? {
    val p = this[key] as? JsonPrimitive ?: return null
    if (p is JsonNull) return null
    return p.booleanOrNull ?: p.longOrNull?.let { it != 0L }
}
