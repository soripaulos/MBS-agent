package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.browser.BrowserController
import me.rerere.rikkahub.browser.BrowserControllerHandle
import me.rerere.rikkahub.browser.actJs
import me.rerere.rikkahub.browser.awaitReadyState
import me.rerere.rikkahub.browser.evaluateJavascriptAsync
import me.rerere.rikkahub.browser.pageSnapshot
import me.rerere.rikkahub.browser.parseJsJson
import me.rerere.rikkahub.jev.JevQuestion
import me.rerere.rikkahub.jev.JevService

private fun out(obj: JsonObject) = listOf(UIMessagePart.Text(obj.toString()))

private fun JsonElement.arg(key: String): String? =
    (this as? JsonObject)?.get(key)?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }

private fun jevService(): JevService? = runCatching {
    org.koin.core.context.GlobalContext.get().get<JevService>()
}.getOrNull()

fun browserSnapshotTool(): Tool = Tool(
    name = "browser_snapshot",
    description = "Compact text view of the current in-app browser page: readable text plus numbered interactive " +
        "elements ([n] kind \"label\" → link). Far cheaper and faster than browser_screenshot or browser_get_dom; " +
        "act on an element with browser_act(index=n). Numbers change on every snapshot - use the latest.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("max_elements", buildJsonObject { put("type", "integer"); put("description", "Default 80, max 250") })
                put("max_text", buildJsonObject { put("type", "integer"); put("description", "Characters of page text, default 1500") })
            },
        )
    },
    execute = { input ->
        val maxEl = (input as? JsonObject)?.get("max_elements")?.jsonPrimitive?.intOrNull ?: 80
        val maxText = (input as? JsonObject)?.get("max_text")?.jsonPrimitive?.intOrNull ?: 1_500
        out(
            withTimeoutOrNull(BrowserController.perToolTimeoutMs) {
                BrowserControllerHandle.withController { webView.pageSnapshot(maxEl, maxText) }
            } ?: buildJsonObject { put("error", "tool_timeout") }
        )
    },
)

/**
 * One-call browser step: act on a snapshot element (by number, or by plain-language intent
 * resolved with Jev) and get the NEW page snapshot back - so a browsing step is one round trip
 * instead of act → wait → read → screenshot.
 */
fun browserActTool(allowClick: Boolean, allowType: Boolean): Tool = Tool(
    name = "browser_act",
    description = buildString {
        append("Act on the in-app browser page and get the updated page back in the same call. Target an element by ")
        append("`index` from the latest snapshot (browser_open/browser_snapshot/browser_act results), or describe it in ")
        append("`intent` (\"the Sign in button\") and the fast Jev model picks it. Actions: ")
        append(listOfNotNull("click, submit, select (text = option)".takeIf { allowClick }, "type (text)".takeIf { allowType }, "focus").joinToString(", "))
        append(". Returns {done, url, page}.")
    },
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("action", buildJsonObject {
                    put("type", "string")
                    putJsonArray("enum") {
                        if (allowClick) { add("click"); add("submit"); add("select") }
                        if (allowType) add("type")
                        add("focus")
                    }
                })
                put("index", buildJsonObject { put("type", "integer"); put("description", "Element number from the latest snapshot") })
                put("intent", buildJsonObject { put("type", "string"); put("description", "Describe the element instead of giving an index") })
                put("text", buildJsonObject { put("type", "string"); put("description", "For type: the text (replaces the field). For select: the option text.") })
            },
            required = listOf("action"),
        )
    },
    needsApproval = { true },
    execute = { input -> out(runBrowserAct(input, allowClick, allowType)) },
)

private suspend fun runBrowserAct(input: JsonElement, allowClick: Boolean, allowType: Boolean): JsonObject {
    val action = input.arg("action") ?: return buildJsonObject { put("error", "missing_action") }
    val allowed = buildSet {
        if (allowClick) { add("click"); add("submit"); add("select") }
        if (allowType) add("type")
        add("focus")
    }
    if (action !in allowed) {
        return buildJsonObject {
            put("error", "action_not_allowed")
            put("detail", "Enable browser_click / browser_type in Settings → Browser for this action.")
        }
    }
    val text = input.arg("text")
    if ((action == "type" || action == "select") && text == null) return buildJsonObject { put("error", "missing_text") }
    val explicitIndex = (input as? JsonObject)?.get("index")?.jsonPrimitive?.intOrNull
    val intent = input.arg("intent")
    if (explicitIndex == null && intent == null) return buildJsonObject { put("error", "give index or intent") }

    return withTimeoutOrNull(BrowserController.perToolTimeoutMs) {
        BrowserControllerHandle.withController {
            val index: Int = explicitIndex ?: run {
                // Resolve the intent against a fresh snapshot.
                val snap = webView.pageSnapshot(maxElements = 200, maxTextChars = 300)
                val elements = snap["elements"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
                if (elements.isEmpty()) return@withController buildJsonObject { put("error", "no_interactive_elements") }
                when (val picked = pickElement(intent!!, elements, snap["title"]?.jsonPrimitive?.contentOrNull)) {
                    null -> return@withController buildJsonObject {
                        put("error", "no_confident_match")
                        put("intent", intent)
                        put("page", snap)
                    }

                    else -> picked
                }
            }
            val raw = if (action == "type") {
                webView.evaluateJavascriptAsync(buildTypeScript("[data-jev-id=\"$index\"]", text.orEmpty(), clear = true))
            } else {
                webView.evaluateJavascriptAsync(actJs(index, action, text))
            }
            val res = parseJsJson(raw) as? JsonObject ?: buildJsonObject { put("error", "js_failed") }
            if (res.containsKey("error")) return@withController res
            if (action == "click" || action == "submit") webView.awaitReadyState(8_000L)
            val page = webView.pageSnapshot()
            buildJsonObject {
                put("done", true)
                put("acted_on", index)
                put("url", webView.url.orEmpty())
                put("page", page)
            }
        }
    }?.also { if (it["done"]?.jsonPrimitive?.contentOrNull == "true") BrowserController.streamScreenshotIfHeadless("$action #${it["acted_on"]}") }
        ?: buildJsonObject { put("error", "tool_timeout") }
}

/** Element number for [intent]: Jev when available, else a word-overlap match; null when unsure. */
private suspend fun pickElement(intent: String, elements: List<String>, title: String?): Int? {
    val jev = jevService()
    if (jev != null && jev.config.value.isUsable) {
        val options = linkedMapOf("none" to "No element matches")
        elements.take(JevQuestion.MAX_CHOICE_OPTIONS - 1).forEachIndexed { i, line -> options["e$i"] = line }
        val result = jev.ask(
            "browser_act",
            buildJsonObject { put("page_title", title.orEmpty()); put("looking_for", intent) },
            mapOf("pick" to JevQuestion.Choice("Which page element best matches looking_for?", options)),
        )
        val choice = result?.choice("pick")
        if (choice != null && choice.choice != "none" && choice.probability >= jev.config.value.browser.actThreshold) {
            jev.log("browser_act", "\"${intent.take(40)}\" -> ${options[choice.choice]?.take(60)}", result?.latencyMs ?: 0)
            return choice.choice.removePrefix("e").toIntOrNull()
        }
        if (choice != null) return null
    }
    val words = intent.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length > 1 }
    if (words.isEmpty()) return null
    val scored = elements.mapIndexed { i, line ->
        val l = line.lowercase()
        i to words.count { it in l }
    }.filter { it.second > 0 }.sortedByDescending { it.second }
    val best = scored.firstOrNull() ?: return null
    // Require a clear winner covering most of the words.
    if (best.second * 2 < words.size) return null
    if (scored.size > 1 && scored[1].second == best.second) return null
    return best.first
}
