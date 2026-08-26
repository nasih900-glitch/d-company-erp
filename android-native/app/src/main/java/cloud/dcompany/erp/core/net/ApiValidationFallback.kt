package cloud.dcompany.erp.core.net

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Defensive compatibility parser for an older FastAPI server that still
 * returns `{"detail": [...]}` instead of D Company's normal error envelope.
 *
 * Pydantic includes the rejected `input` value in that payload. Never include
 * it in UI text or logs: it may be a password, customer phone, or payment
 * reference. Only the field path and validator message are read here.
 */
internal fun fastApiValidationMessage(json: Json, body: String): String? = runCatching {
    val detail = json.parseToJsonElement(body).jsonObject["detail"] ?: return@runCatching null
    if (detail is kotlinx.serialization.json.JsonPrimitive) {
        return@runCatching detail.contentOrNull
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?.take(240)
    }

    val first = detail.jsonArray.firstOrNull()?.jsonObject ?: return@runCatching null
    val path = first["loc"]?.jsonArray
        ?.mapNotNull { part -> part.jsonPrimitive.contentOrNull }
        ?.filterNot { it in setOf("body", "query", "path", "header") }
        ?.joinToString(".")
        .orEmpty()
    val raw = first["msg"]?.jsonPrimitive?.contentOrNull
        ?.trim()
        ?.removePrefix("Value error, ")
        ?.removePrefix("value error, ")
        ?.trim()
        ?.takeIf(String::isNotEmpty)
        ?: return@runCatching null
    val label = path
        .replace('_', ' ')
        .replace(Regex("\\.(\\d+)")) { match -> " item ${match.groupValues[1]}" }
        .replaceFirstChar { it.uppercase() }
        .takeIf(String::isNotEmpty)
    val message = raw.replaceFirstChar { it.uppercase() }.trimEnd('.')
    (if (label == null) "$message." else "$label: $message.").take(240)
}.getOrNull()
