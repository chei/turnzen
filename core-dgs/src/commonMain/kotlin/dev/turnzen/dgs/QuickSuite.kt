package dev.turnzen.dgs

import dev.turnzen.goserver.CredentialProblem
import dev.turnzen.goserver.GoServerException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

/**
 * Envelope handling for `quick_do.php` responses: extract the JSON object, check the
 * `error` field, map DGS error codes to [GoServerException]s.
 */
internal object QuickSuite {
    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

    /** Finds the JSON object in a response body that may carry leading or trailing text. */
    fun extractJson(body: String): JsonObject {
        val start = body.indexOf('{')
        val end = body.lastIndexOf('}')
        if (start < 0 || end <= start) {
            throw GoServerException.Protocol("No JSON object in DGS response: ${body.take(120)}")
        }
        return try {
            json.parseToJsonElement(body.substring(start, end + 1)) as? JsonObject
                ?: throw GoServerException.Protocol("DGS response is not a JSON object")
        } catch (e: IllegalArgumentException) {
            throw GoServerException.Protocol("Malformed JSON from DGS", e)
        }
    }

    /** Parses the envelope and throws if it reports an error. */
    fun parse(body: String): JsonObject {
        val obj = extractJson(body)
        val error = obj.string("error").orEmpty()
        if (error.isNotEmpty()) throw exceptionFor(error, obj.string("error_msg"))
        return obj
    }

    fun exceptionFor(
        code: String,
        detail: String?,
    ): GoServerException {
        val message = if (detail.isNullOrBlank()) "DGS error: $code" else "DGS error: $code ($detail)"
        return when (code) {
            "not_logged_in", "cookies_disabled", "no_uid" -> {
                GoServerException.SessionExpired(message)
            }

            "wrong_userid" -> {
                GoServerException.InvalidCredentials(CredentialProblem.UNKNOWN_USER, message)
            }

            "wrong_password" -> {
                GoServerException.InvalidCredentials(CredentialProblem.WRONG_PASSWORD, message)
            }

            "need_activation" -> {
                GoServerException.InvalidCredentials(CredentialProblem.NOT_ACTIVATED, message)
            }

            "login_denied", "ip_blocked_guest_login" -> {
                GoServerException.InvalidCredentials(CredentialProblem.BLOCKED, message)
            }

            "fever_vault", "quota_exceeded" -> {
                GoServerException.RateLimited(message)
            }

            "already_played", "game_finished", "invalid_game_status", "game_not_started" -> {
                GoServerException.StaleGame(message)
            }

            "not_your_turn", "illegal_position", "suicide", "ko", "invalid_coord", "early_pass",
            "invalid_action", "move_problem", "not_game_player",
            -> {
                GoServerException.MoveRejected(code, message)
            }

            else -> {
                if (code.contains(
                        "quota",
                    )
                ) {
                    GoServerException.RateLimited(message)
                } else {
                    GoServerException.ServerError(code, message)
                }
            }
        }
    }

    // --- tolerant accessors: DGS mixes numbers and numeric strings ---

    fun JsonObject.string(key: String): String? =
        when (val v = this[key]) {
            null, JsonNull -> null
            is JsonPrimitive -> v.content
            else -> v.toString()
        }

    fun JsonObject.int(key: String): Int? = string(key)?.trim()?.toIntOrNull()

    fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

    fun JsonObject.array(key: String): JsonArray? = this[key] as? JsonArray

    fun JsonElement.asStringOrNull(): String? = (this as? JsonPrimitive)?.jsonPrimitive?.content
}
