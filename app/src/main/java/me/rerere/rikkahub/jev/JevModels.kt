package me.rerere.rikkahub.jev

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Typed questions for TypeSafe's Jev "System One" model (POST /v1/systemone).
 *
 * Jev is a decision model, not a chat model: it reads one piece of state and answers a set
 * of typed questions — yes/no ("noul"), pick-one ("choice", up to 255 options) or an ordered
 * scale ("score", 2–10 levels) — returning calibrated probabilities in ~100 ms for a fraction
 * of an LLM call's cost. Everything in the app that wants a fast judgement builds these.
 */
sealed interface JevQuestion {
    val instructions: String

    fun toJson(): JsonObject

    /** Yes/no. [trueMeans]/[falseMeans] describe each outcome for the model. */
    data class YesNo(
        override val instructions: String,
        val trueMeans: String = "yes",
        val falseMeans: String = "no",
    ) : JevQuestion {
        override fun toJson() = buildJsonObject {
            put("type", "noul")
            put("instructions", instructions)
            put("criteria", buildJsonObject {
                put("true", trueMeans)
                put("false", falseMeans)
            })
        }
    }

    /** Pick exactly one of [options] (key -> description). Keys come back verbatim. */
    data class Choice(
        override val instructions: String,
        val options: Map<String, String>,
    ) : JevQuestion {
        init {
            require(options.size in 2..MAX_CHOICE_OPTIONS) {
                "Jev choice questions need 2..$MAX_CHOICE_OPTIONS options (got ${options.size})"
            }
        }

        override fun toJson() = buildJsonObject {
            put("type", "choice")
            put("instructions", instructions)
            put("criteria", buildJsonObject { options.forEach { (k, v) -> put(k, v) } })
        }
    }

    /** Ordered scale; [levels][0] is the lowest. */
    data class Score(
        override val instructions: String,
        val levels: List<String>,
    ) : JevQuestion {
        init {
            require(levels.size in 2..MAX_SCORE_LEVELS) {
                "Jev score questions need 2..$MAX_SCORE_LEVELS levels (got ${levels.size})"
            }
        }

        override fun toJson() = buildJsonObject {
            put("type", "score")
            put("instructions", instructions)
            put("criteria", buildJsonArray { levels.forEach { add(it) } })
        }
    }

    companion object {
        const val MAX_CHOICE_OPTIONS = 255
        const val MAX_SCORE_LEVELS = 10
    }
}

/** One answer from a Jev response. Unknown answer types are surfaced as [Unknown]. */
sealed interface JevAnswer {
    /** Best-effort confidence in 0..1 (noul answers carry none; derived from the margin). */
    val confidence: Double

    data class YesNo(val probability: Double) : JevAnswer {
        val isTrue: Boolean get() = probability >= 0.5
        override val confidence: Double get() = kotlin.math.abs(probability - 0.5) * 2
    }

    data class Choice(
        val choice: String,
        val probabilities: Map<String, Double>,
        override val confidence: Double,
    ) : JevAnswer {
        val probability: Double get() = probabilities[choice] ?: 0.0

        /** Options sorted by probability, highest first. */
        fun ranked(): List<Pair<String, Double>> = probabilities.entries
            .sortedByDescending { it.value }
            .map { it.key to it.value }
    }

    data class Score(
        val score: Double,
        val probabilities: Map<Int, Double>,
        override val confidence: Double,
    ) : JevAnswer

    data class Unknown(val raw: JsonObject) : JevAnswer {
        override val confidence: Double get() = 0.0
    }
}

data class JevUsage(val inputTokens: Int, val outputTokens: Int)

data class JevResult(
    val model: String,
    val answers: Map<String, JevAnswer>,
    val usage: JevUsage,
    val latencyMs: Long,
) {
    fun yesNo(id: String): JevAnswer.YesNo? = answers[id] as? JevAnswer.YesNo
    fun choice(id: String): JevAnswer.Choice? = answers[id] as? JevAnswer.Choice
    fun score(id: String): JevAnswer.Score? = answers[id] as? JevAnswer.Score
}

internal object JevResponseParser {
    fun parse(body: JsonObject, latencyMs: Long): JevResult {
        val answers = (body["answers"] as? JsonObject).orEmpty().mapValues { (_, v) -> parseAnswer(v) }
        val usage = body["usage"] as? JsonObject
        return JevResult(
            model = (body["model"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
            answers = answers,
            usage = JevUsage(
                inputTokens = usage?.get("input_tokens")?.jsonPrimitive?.intOrNull ?: 0,
                outputTokens = usage?.get("output_tokens")?.jsonPrimitive?.intOrNull ?: 0,
            ),
            latencyMs = latencyMs,
        )
    }

    private fun parseAnswer(element: JsonElement): JevAnswer {
        val obj = element as? JsonObject ?: return JevAnswer.Unknown(JsonObject(emptyMap()))
        fun num(key: String) = (obj[key] as? JsonPrimitive)?.doubleOrNull
        fun probs(): Map<String, Double> = (obj["probabilities"] as? JsonObject).orEmpty()
            .mapNotNull { (k, v) -> (v as? JsonPrimitive)?.doubleOrNull?.let { k to it } }
            .toMap()
        return when ((obj["type"] as? JsonPrimitive)?.contentOrNull) {
            "noul" -> JevAnswer.YesNo(num("noul") ?: return JevAnswer.Unknown(obj))
            "choice" -> {
                val choice = (obj["choice"] as? JsonPrimitive)?.contentOrNull ?: return JevAnswer.Unknown(obj)
                val p = probs()
                JevAnswer.Choice(choice, p, num("confidence") ?: (p[choice] ?: 0.0))
            }

            "score" -> JevAnswer.Score(
                score = num("score") ?: return JevAnswer.Unknown(obj),
                probabilities = probs().mapNotNull { (k, v) -> k.toIntOrNull()?.let { it to v } }.toMap(),
                confidence = num("confidence") ?: 0.0,
            )

            else -> JevAnswer.Unknown(obj)
        }
    }
}

/** Turns an answer back into a compact JSON object for tool results / logs. */
fun JevAnswer.toJson(): JsonObject = when (val a = this) {
    is JevAnswer.YesNo -> buildJsonObject {
        put("answer", a.isTrue)
        put("probability", round3(a.probability))
    }

    is JevAnswer.Choice -> buildJsonObject {
        put("choice", a.choice)
        put("probability", round3(a.probability))
        put("confidence", round3(a.confidence))
        put("ranking", buildJsonArray {
            a.ranked().take(5).forEach { (k, p) ->
                add(buildJsonObject { put("option", k); put("probability", round3(p)) })
            }
        })
    }

    is JevAnswer.Score -> buildJsonObject {
        put("score", round3(a.score))
        put("confidence", round3(a.confidence))
    }

    is JevAnswer.Unknown -> buildJsonObject { put("raw", a.raw) }
}

internal fun round3(v: Double): Double = kotlin.math.round(v * 1000) / 1000
