package me.rerere.rikkahub.jev

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.common.http.await
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class JevException(message: String, val httpCode: Int? = null) : Exception(message)

/**
 * Thin HTTP client for `POST {baseUrl}/systemone`. Stateless apart from the shared OkHttp
 * pool; callers pass the [JevConfig] so a key/model change applies on the next call.
 */
class JevClient(
    private val okHttpClient: OkHttpClient,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun ask(
        config: JevConfig,
        state: JsonElement,
        questions: Map<String, JevQuestion>,
    ): JevResult = withContext(Dispatchers.IO) {
        if (config.apiKey.isBlank()) throw JevException("Jev API key is not set")
        require(questions.isNotEmpty()) { "at least one question is required" }
        val body = buildJsonObject {
            put("model", config.model.ifBlank { "jev-latest" })
            put("state", state)
            put("questions", buildJsonObject { questions.forEach { (id, q) -> put(id, q.toJson()) } })
        }
        val client = okHttpClient.newBuilder()
            .callTimeout(config.timeoutMs.coerceIn(500, 30_000), TimeUnit.MILLISECONDS)
            .build()
        val request = Request.Builder()
            .url(config.baseUrl.trimEnd('/') + "/systemone")
            .header("Authorization", "Bearer ${config.apiKey.trim()}")
            .header("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        val started = System.currentTimeMillis()
        client.newCall(request).await().use { response ->
            val text = response.body.string()
            val latency = System.currentTimeMillis() - started
            if (!response.isSuccessful) {
                val reason = when (response.code) {
                    401 -> "invalid or missing Jev API key"
                    422 -> "request rejected by Jev (validation)"
                    429 -> "Jev rate limit reached"
                    529 -> "Jev is overloaded"
                    else -> "HTTP ${response.code}"
                }
                throw JevException("$reason: ${text.take(300)}", response.code)
            }
            JevResponseParser.parse(json.parseToJsonElement(text).jsonObject, latency)
        }
    }

    companion object {
        /** Jev accepts a string or an object as state; clamp strings to stay inside its window. */
        fun textState(text: String, maxChars: Int = 60_000): JsonElement =
            JsonPrimitive(if (text.length > maxChars) text.take(maxChars) + "…" else text)

        fun objectState(obj: JsonObject): JsonElement = obj
    }
}
