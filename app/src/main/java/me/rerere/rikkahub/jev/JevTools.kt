package me.rerere.rikkahub.jev

import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.browser.BrowserControllerHandle
import me.rerere.rikkahub.browser.awaitReadyState
import me.rerere.rikkahub.browser.evaluateJavascriptAsync
import me.rerere.rikkahub.data.ai.AgentTurnTracker
import me.rerere.rikkahub.data.ai.tools.local.AccessibilityServiceHandle
import me.rerere.rikkahub.data.ai.tools.local.buildTypeScript
import me.rerere.rikkahub.data.ai.tools.local.defaultFilter
import me.rerere.rikkahub.data.ai.tools.local.screenStateJson
import me.rerere.rikkahub.data.ai.tools.local.wakeScreenIfNeeded
import me.rerere.rikkahub.data.ai.tools.local.withActionEnvelope
import me.rerere.rikkahub.service.RikkaAccessibilityService

/*
 * LLM-callable tools backed by Jev. The pattern throughout: the slow, expensive chat model
 * decides WHAT it wants ("the Sign in button", "is the order confirmed?"), and Jev does the
 * fast, cheap part of matching that against hundreds of on-screen candidates or a whole
 * page of text in ~100 ms — instead of the chat model reading a 500-node tree or 8k chars
 * of page text into its context every step.
 */

private fun text(obj: JsonObject): List<UIMessagePart> = listOf(UIMessagePart.Text(obj.toString()))

private fun jevError(code: String, detail: String? = null) = buildJsonObject {
    put("error", code)
    detail?.let { put("detail", it) }
}

private val JEV_UNAVAILABLE = jevError(
    "jev_unavailable",
    "Jev did not answer (check Settings → Jev: API key, network). Fall back to the regular tools.",
)

private fun JsonElement.str(key: String): String? =
    (this as? JsonObject)?.get(key)?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }

// ============================================================================ jev_decide

private const val MAX_BATCH_ITEMS = 100
private const val BATCH_CONCURRENCY = 8

fun jevDecideTool(jev: JevService): Tool = Tool(
    name = "jev_decide",
    description = """
        Fast (~0.1 s), near-free typed judgement by the Jev decision model — use it instead of
        reasoning yourself when you need to classify, triage, filter or rank text you already
        have, especially many items at once. Examples: which of 60 emails/notifications need a
        reply; rank search results or files by relevance to a goal; is this message urgent;
        which category/team/folder does each item belong to; rate severity 0-3. Pass `items`
        to judge each one separately (up to 100, answered in parallel) or `state` for a single
        piece of content. type: yes_no | choice (give `options` {key: description}) | score
        (give `levels`, lowest first). Not for generating text, arithmetic, counting, dates or
        images. Returns probabilities; with items, yes_no results come back sorted by
        probability and filtered by min_probability, score results sorted highest first.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("question", buildJsonObject {
                    put("type", "string")
                    put("description", "The judgement to make, phrased for one item, e.g. 'Does this email need a reply from me?'")
                })
                put("type", buildJsonObject {
                    put("type", "string")
                    putJsonArray("enum") { add("yes_no"); add("choice"); add("score") }
                })
                put("options", buildJsonObject {
                    put("type", "object")
                    put("description", "choice only: {option_key: what it means}. 2-255 options; include a catch-all like 'other'.")
                })
                put("levels", buildJsonObject {
                    put("type", "array")
                    put("items", buildJsonObject { put("type", "string") })
                    put("description", "score only: 2-10 level descriptions, lowest first.")
                })
                put("state", buildJsonObject {
                    put("type", "string")
                    put("description", "Single piece of content to judge (use this OR items).")
                })
                put("items", buildJsonObject {
                    put("type", "array")
                    put("items", buildJsonObject { put("type", "string") })
                    put("description", "Many pieces of content, each judged separately against the same question.")
                })
                put("context", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional background shared by every item (the user's goal, their preferences…).")
                })
                put("min_probability", buildJsonObject {
                    put("type", "number")
                    put("description", "yes_no with items: only return items whose 'yes' probability is at least this (default 0 = return all).")
                })
            },
            required = listOf("question", "type"),
        )
    },
    execute = { input -> text(runDecide(jev, input)) },
)

private suspend fun runDecide(jev: JevService, input: JsonElement): JsonObject {
    val obj = input as? JsonObject ?: return jevError("bad_input")
    val questionText = obj.str("question") ?: return jevError("missing_question")
    val question: JevQuestion = try {
        when (obj.str("type")) {
            "yes_no" -> JevQuestion.YesNo(questionText)
            "choice" -> {
                val options = (obj["options"] as? JsonObject).orEmpty()
                    .mapValues { (k, v) -> (v as? JsonPrimitive)?.contentOrNull ?: k }
                JevQuestion.Choice(questionText, options)
            }

            "score" -> {
                val levels = (obj["levels"] as? JsonArray).orEmpty()
                    .mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                JevQuestion.Score(questionText, levels)
            }

            else -> return jevError("bad_type", "type must be yes_no, choice or score")
        }
    } catch (e: IllegalArgumentException) {
        return jevError("bad_question", e.message)
    }
    val context = obj.str("context")
    val items = (obj["items"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
    val started = System.currentTimeMillis()

    fun stateFor(content: String): JsonElement =
        if (context == null) JevClient.textState(content)
        else buildJsonObject { put("context", context.take(4_000)); put("content", content.take(20_000)) }

    if (items.isNullOrEmpty()) {
        val content = obj.str("state") ?: return jevError("missing_state", "give state or items")
        val result = jev.ask("jev_decide", stateFor(content), mapOf("q" to question))
            ?: return JEV_UNAVAILABLE
        val answer = result.answers["q"] ?: return JEV_UNAVAILABLE
        jev.log("jev_decide", "1 item", result.latencyMs)
        return buildJsonObject {
            put("result", answer.toJson())
            put("latency_ms", result.latencyMs)
        }
    }

    val batch = items.take(MAX_BATCH_ITEMS)
    val gate = Semaphore(BATCH_CONCURRENCY)
    val answers: List<JevAnswer?> = coroutineScope {
        batch.map { item ->
            async { gate.withPermit { jev.ask("jev_decide", stateFor(item), mapOf("q" to question))?.answers?.get("q") } }
        }.map { it.await() }
    }
    if (answers.all { it == null }) return JEV_UNAVAILABLE
    val minP = obj["min_probability"]?.jsonPrimitive?.doubleOrNull ?: 0.0
    val indexed = batch.indices.mapNotNull { i -> answers[i]?.let { i to it } }
    val ordered = when (question) {
        is JevQuestion.YesNo -> indexed
            .filter { (_, a) -> ((a as? JevAnswer.YesNo)?.probability ?: 0.0) >= minP }
            .sortedByDescending { (_, a) -> (a as? JevAnswer.YesNo)?.probability ?: 0.0 }

        is JevQuestion.Score -> indexed.sortedByDescending { (_, a) -> (a as? JevAnswer.Score)?.score ?: 0.0 }
        else -> indexed
    }
    val elapsed = System.currentTimeMillis() - started
    jev.log("jev_decide", "${batch.size} items, ${ordered.size} returned", elapsed)
    return buildJsonObject {
        put("results", buildJsonArray {
            ordered.forEach { (i, a) ->
                add(JsonObject(buildMap<String, JsonElement> {
                    put("index", JsonPrimitive(i))
                    put("item", JsonPrimitive(batch[i].replace('\n', ' ').take(100)))
                    putAll(a.toJson())
                }))
            }
        })
        put("judged", indexed.size)
        if (items.size > MAX_BATCH_ITEMS) put("skipped_over_limit", items.size - MAX_BATCH_ITEMS)
        if (indexed.size < batch.size) put("failed", batch.size - indexed.size)
        put("latency_ms", elapsed)
    }
}

// ============================================================================ browser

private fun jsString(s: String): String = JsonPrimitive(s).toString()

private fun parseJs(raw: String?): JsonElement? = runCatching {
    val outer = Json.parseToJsonElement(raw ?: return null)
    val inner = if (outer is JsonPrimitive && outer.isString) outer.content else outer.toString()
    Json.parseToJsonElement(inner)
}.getOrNull()

/** Collects visible interactive elements, tags each with data-jev-id, returns their summaries. */
private fun collectElementsJs(max: Int, includeLinks: Boolean) = """(function(){
  try {
    var sel = 'button,input,select,textarea,summary,[role=button],[role=tab],[role=menuitem],[role=checkbox],[role=radio],[role=switch],[role=option],[role=combobox],[onclick],[contenteditable=true]'${if (includeLinks) " + ',a[href],[role=link]'" else ""};
    var nodes = document.querySelectorAll(sel);
    var out = [];
    document.querySelectorAll('[data-jev-id]').forEach(function(n){ n.removeAttribute('data-jev-id'); });
    for (var i = 0; i < nodes.length && out.length < $max; i++) {
      var el = nodes[i];
      if (el.disabled || el.type === 'hidden') continue;
      var r = el.getBoundingClientRect();
      if (r.width < 2 || r.height < 2) continue;
      var st = window.getComputedStyle(el);
      if (st.visibility === 'hidden' || st.display === 'none' || st.opacity === '0') continue;
      var label = (el.getAttribute('aria-label') || el.innerText || el.value || el.placeholder || el.title || el.alt || el.name || '').replace(/\s+/g,' ').trim();
      if (!label && el.labels && el.labels.length) label = (el.labels[0].innerText || '').trim();
      var id = out.length;
      el.setAttribute('data-jev-id', String(id));
      out.push({
        id: id,
        tag: el.tagName.toLowerCase(),
        type: el.type || el.getAttribute('role') || '',
        label: label.substring(0, 120),
        href: el.href ? String(el.href).substring(0, 80) : '',
        visible: r.bottom > 0 && r.top < window.innerHeight
      });
    }
    return JSON.stringify({elements: out, url: location.href, title: document.title});
  } catch(e) { return JSON.stringify({error: 'js_failed', detail: String(e)}); }
})()"""

private fun clickJs(selector: String) = """(function(){
  try {
    var el = document.querySelector(${jsString(selector)});
    if (!el) return JSON.stringify({error:'element_gone'});
    el.scrollIntoView({block:'center', inline:'center'});
    el.click();
    return JSON.stringify({clicked:true});
  } catch(e) { return JSON.stringify({error:'js_failed', detail:String(e)}); }
})()"""

/**
 * @param allowClick / @param allowType mirror the user's Settings → Browser toggles for
 * browser_click / browser_type, so this tool can never act where those are switched off.
 */
fun browserFindElementTool(jev: JevService, allowClick: Boolean, allowType: Boolean): Tool = Tool(
    name = "browser_find_element",
    description = buildString {
        append("Find the element on the current in-app browser page that matches a plain-language intent ")
        append("(\"the Sign in button\", \"search box\", \"Add to cart for the blue one\") using the fast Jev model — ")
        append("no need to read the DOM yourself. Returns a CSS selector usable with the other browser tools, plus alternatives. ")
        val acts = listOfNotNull("click".takeIf { allowClick }, "type".takeIf { allowType })
        if (acts.isNotEmpty()) {
            append("Set action=${acts.joinToString("|")} to act on the match in the same call")
            if (allowType) append(" (type needs `text`)")
            append("; it only acts when Jev is confident, otherwise it returns the candidates for you to choose.")
        }
    },
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("intent", buildJsonObject {
                    put("type", "string")
                    put("description", "What you are looking for, in words.")
                })
                put("action", buildJsonObject {
                    put("type", "string")
                    putJsonArray("enum") {
                        add("none")
                        if (allowClick) add("click")
                        if (allowType) add("type")
                    }
                    put("description", "What to do with the match (default none).")
                })
                if (allowType) put("text", buildJsonObject {
                    put("type", "string")
                    put("description", "Text to type when action=type (replaces the field's value).")
                })
                put("include_links", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Consider plain links too (default true).")
                })
            },
            required = listOf("intent"),
        )
    },
    needsApproval = { input -> (input.str("action") ?: "none") != "none" },
    execute = { input -> text(runBrowserFind(jev, input, allowClick, allowType)) },
)

private suspend fun runBrowserFind(jev: JevService, input: JsonElement, allowClick: Boolean, allowType: Boolean): JsonObject {
    val intent = input.str("intent") ?: return jevError("missing_intent")
    val action = input.str("action") ?: "none"
    if ((action == "click" && !allowClick) || (action == "type" && !allowType) || action !in setOf("none", "click", "type")) {
        return jevError("action_not_allowed", "enable browser_click / browser_type in Settings → Browser")
    }
    val typed = input.str("text")
    if (action == "type" && typed == null) return jevError("missing_text")
    val includeLinks = (input as? JsonObject)?.get("include_links")?.jsonPrimitive?.contentOrNull != "false"
    val cfg = jev.config.value
    val max = cfg.browser.maxCandidates.coerceIn(10, JevQuestion.MAX_CHOICE_OPTIONS - 1)

    return BrowserControllerHandle.withController {
        val collected = parseJs(webView.evaluateJavascriptAsync(collectElementsJs(max, includeLinks)))
            as? JsonObject ?: return@withController jevError("js_failed")
        if (collected.containsKey("error")) return@withController collected
        val elements = collected["elements"]?.jsonArray.orEmpty().mapNotNull { it as? JsonObject }
        if (elements.isEmpty()) return@withController jevError("no_interactive_elements")

        val options = linkedMapOf("none" to "No element on the page matches")
        elements.forEach { e ->
            val id = e["id"]?.jsonPrimitive?.intOrNull ?: return@forEach
            options["e$id"] = buildString {
                append(e.str("tag"))
                e.str("type")?.let { append(" [$it]") }
                append(": ")
                append(e.str("label") ?: "(no label)")
                e.str("href")?.let { append(" -> $it") }
                if (e["visible"]?.jsonPrimitive?.contentOrNull == "false") append(" (off-screen)")
            }
        }
        val state = buildJsonObject {
            put("page_title", collected.str("title") ?: "")
            put("url", collected.str("url") ?: "")
            put("looking_for", intent)
        }
        val result = jev.ask(
            "browser_find",
            state,
            mapOf("pick" to JevQuestion.Choice("Which page element best matches looking_for?", options)),
            cfg,
        ) ?: return@withController JEV_UNAVAILABLE
        val pick = result.choice("pick") ?: return@withController JEV_UNAVAILABLE
        val alternatives = pick.ranked().filter { it.first != "none" && it.first != pick.choice }.take(3)
        fun selectorOf(key: String) = "[data-jev-id=\"${key.removePrefix("e")}\"]"

        val base = buildMap<String, JsonElement> {
            put("confidence", JsonPrimitive(round3(pick.probability)))
            put("alternatives", buildJsonArray {
                alternatives.forEach { (k, p) ->
                    add(buildJsonObject {
                        put("selector", selectorOf(k))
                        put("description", options[k].orEmpty())
                        put("probability", round3(p))
                    })
                }
            })
            put("latency_ms", JsonPrimitive(result.latencyMs))
        }
        if (pick.choice == "none") {
            jev.log("browser_find", "\"${intent.take(40)}\" -> no match", result.latencyMs)
            return@withController JsonObject(base + ("match" to JsonPrimitive(false)))
        }
        val selector = selectorOf(pick.choice)
        val found = base + mapOf(
            "match" to JsonPrimitive(true),
            "selector" to JsonPrimitive(selector),
            "description" to JsonPrimitive(options[pick.choice].orEmpty()),
        )
        jev.log("browser_find", "\"${intent.take(40)}\" -> ${options[pick.choice]?.take(60)} (${JevService.pct(pick.probability)})", result.latencyMs)
        if (action == "none") return@withController JsonObject(found)
        if (pick.probability < cfg.browser.actThreshold) {
            return@withController JsonObject(
                found + ("acted" to JsonPrimitive(false)) +
                    ("hint" to JsonPrimitive("Not confident enough to $action; check the candidates and use browser_$action with the selector you want."))
            )
        }
        val js = if (action == "click") clickJs(selector) else buildTypeScript(selector, typed.orEmpty(), clear = true)
        val acted = parseJs(webView.evaluateJavascriptAsync(js)) as? JsonObject
        if (acted == null || acted.containsKey("error")) {
            return@withController JsonObject(found + ("acted" to JsonPrimitive(false)) + ("action_error" to (acted ?: JsonPrimitive("js_failed"))))
        }
        if (action == "click") webView.awaitReadyState(8_000L)
        JsonObject(found + ("acted" to JsonPrimitive(true)) + ("url_after" to JsonPrimitive(webView.url.orEmpty())))
    }
}

fun browserCheckTool(jev: JevService): Tool = Tool(
    name = "browser_check",
    description = "Ask a yes/no question about the current in-app browser page (\"Is the user logged in?\", " +
        "\"Did the order go through?\", \"Is there a CAPTCHA or error message?\") and get a probability back in ~0.1 s " +
        "from the Jev model, without reading the page text into your context. Use it to verify each step of a browsing task.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("question", buildJsonObject { put("type", "string"); put("description", "A yes/no question about the page.") })
                put("selector", buildJsonObject { put("type", "string"); put("description", "Optional CSS selector to limit the page area (default: whole page).") })
            },
            required = listOf("question"),
        )
    },
    execute = { input ->
        val question = input.str("question")
        text(
            if (question == null) jevError("missing_question") else BrowserControllerHandle.withController {
                val selector = input.str("selector") ?: "body"
                val js = """(function(){
                  try {
                    var el = document.querySelector(${jsString(selector)});
                    if (!el) return JSON.stringify({error:'selector_not_found'});
                    return JSON.stringify({text: (el.innerText || '').substring(0, 60000), url: location.href, title: document.title});
                  } catch(e) { return JSON.stringify({error:'js_failed', detail:String(e)}); }
                })()"""
                val page = parseJs(webView.evaluateJavascriptAsync(js)) as? JsonObject
                    ?: return@withController jevError("js_failed")
                if (page.containsKey("error")) return@withController page
                val state = buildJsonObject {
                    put("url", page.str("url") ?: "")
                    put("title", page.str("title") ?: "")
                    put("page_text", page.str("text") ?: "")
                }
                val result = jev.ask("browser_check", state, mapOf("q" to JevQuestion.YesNo(question)))
                    ?: return@withController JEV_UNAVAILABLE
                val answer = result.yesNo("q") ?: return@withController JEV_UNAVAILABLE
                jev.log("browser_check", "\"${question.take(50)}\" -> ${JevService.pct(answer.probability)}", result.latencyMs)
                buildJsonObject {
                    put("answer", answer.isTrue)
                    put("probability", round3(answer.probability))
                    put("url", page.str("url") ?: "")
                    put("latency_ms", result.latencyMs)
                }
            }
        )
    },
)

// ============================================================================ phone use

private data class Candidate(val node: AccessibilityNodeInfo, val index: Int, val description: String)

private fun describeNode(n: AccessibilityNodeInfo): String = buildString {
    append(n.className?.toString()?.substringAfterLast('.') ?: "View")
    val t = n.text?.toString()?.replace('\n', ' ')?.trim().orEmpty()
    val d = n.contentDescription?.toString()?.replace('\n', ' ')?.trim().orEmpty()
    if (t.isNotEmpty()) append(" \"").append(t.take(100)).append('"')
    if (d.isNotEmpty() && d != t) append(" (").append(d.take(100)).append(')')
    n.viewIdResourceName?.substringAfterLast('/')?.takeIf { it.isNotEmpty() }?.let { append(" #").append(it) }
    val flags = listOfNotNull(
        "clickable".takeIf { n.isClickable },
        "editable".takeIf { n.isEditable },
        "scrollable".takeIf { n.isScrollable },
        "checked".takeIf { n.isChecked },
        "disabled".takeIf { !n.isEnabled },
    )
    if (flags.isNotEmpty()) append(" [").append(flags.joinToString(",")).append(']')
}

fun screenFindNodeTool(jev: JevService): Tool = Tool(
    name = "screen_find_node",
    description = "Find the on-screen element (in whatever app is in front) that matches a plain-language intent — " +
        "\"the Send button\", \"message input\", \"Wi-Fi toggle\", \"the chat with Mom\" — using the fast Jev model, instead of " +
        "reading the whole window tree yourself. Returns a node_id usable with click_node / set_text, plus alternatives. " +
        "Set action=click or action=type (with `text`) to act in the same call; it only acts when Jev is confident, " +
        "otherwise it returns candidates. The result's after.screen_changed confirms an action landed.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("intent", buildJsonObject { put("type", "string"); put("description", "What you are looking for, in words.") })
                put("action", buildJsonObject {
                    put("type", "string")
                    putJsonArray("enum") { add("none"); add("click"); add("type") }
                    put("description", "What to do with the match (default none).")
                })
                put("text", buildJsonObject { put("type", "string"); put("description", "Text to set when action=type.") })
                put("package_name", buildJsonObject { put("type", "string"); put("description", "Optional: fail unless this app is in front.") })
            },
            required = listOf("intent"),
        )
    },
    needsApproval = { input -> (input.str("action") ?: "none") != "none" },
    execute = { input -> text(runScreenFind(jev, input)) },
)

private suspend fun runScreenFind(jev: JevService, input: JsonElement): JsonObject {
    val intent = input.str("intent") ?: return jevError("missing_intent")
    val action = input.str("action") ?: "none"
    if (action !in setOf("none", "click", "type")) return jevError("bad_action")
    val typed = input.str("text")
    if (action == "type" && typed == null) return jevError("missing_text")
    val pkgFilter = input.str("package_name")
    if (action != "none") AgentTurnTracker.recordAutomationAction()
    RikkaAccessibilityService.instance?.let { wakeScreenIfNeeded(it) }
    val cfg = jev.config.value
    val max = cfg.phone.maxCandidates.coerceIn(10, JevQuestion.MAX_CHOICE_OPTIONS - 1)

    return AccessibilityServiceHandle.withService { svc ->
        val root = svc.rootInActiveWindow ?: return@withService jevError("no_active_window")
        val pkg = root.packageName?.toString().orEmpty()
        if (pkgFilter != null && pkgFilter != pkg) {
            return@withService buildJsonObject { put("error", "wrong_foreground_app"); put("current", pkg) }
        }
        val candidates = mutableListOf<Candidate>()
        svc.traverseTree(
            root = root,
            filter = ::defaultFilter,
            cap = max,
            emit = { n, _, idx -> candidates += Candidate(n, idx, describeNode(n)) },
        )
        if (candidates.isEmpty()) return@withService jevError("no_candidates")
        val options = linkedMapOf("none" to "Nothing on screen matches")
        candidates.forEachIndexed { i, c -> options["n$i"] = c.description }
        val state = buildJsonObject {
            put("app", pkg)
            root.window?.title?.toString()?.let { put("window", it) }
            put("looking_for", intent)
        }
        val result = jev.ask(
            "screen_find",
            state,
            mapOf("pick" to JevQuestion.Choice("Which on-screen element best matches looking_for?", options)),
            cfg,
        ) ?: return@withService JEV_UNAVAILABLE
        val pick = result.choice("pick") ?: return@withService JEV_UNAVAILABLE
        fun nodeId(key: String) = candidates.getOrNull(key.removePrefix("n").toIntOrNull() ?: -1)
            ?.let { "${root.windowId}:${it.index}" }
        val alternatives = buildJsonArray {
            pick.ranked().filter { it.first != "none" && it.first != pick.choice }.take(3).forEach { (k, p) ->
                add(buildJsonObject {
                    put("node_id", nodeId(k) ?: "")
                    put("description", options[k].orEmpty())
                    put("probability", round3(p))
                })
            }
        }
        val chosen = candidates.getOrNull(pick.choice.removePrefix("n").toIntOrNull() ?: -1)
        if (pick.choice == "none" || chosen == null) {
            jev.log("screen_find", "\"${intent.take(40)}\" -> no match", result.latencyMs)
            return@withService buildJsonObject {
                put("match", false)
                put("alternatives", alternatives)
                put("screen_state", screenStateJson(svc, screenChanged = null))
            }
        }
        jev.log("screen_find", "\"${intent.take(40)}\" -> ${chosen.description.take(60)} (${JevService.pct(pick.probability)})", result.latencyMs)
        val found = mapOf(
            "match" to JsonPrimitive(true),
            "node_id" to JsonPrimitive("${root.windowId}:${chosen.index}"),
            "description" to JsonPrimitive(chosen.description),
            "confidence" to JsonPrimitive(round3(pick.probability)),
            "alternatives" to alternatives,
            "latency_ms" to JsonPrimitive(result.latencyMs),
        )
        if (action == "none") return@withService JsonObject(found)
        if (pick.probability < cfg.phone.actThreshold) {
            return@withService JsonObject(
                found + ("acted" to JsonPrimitive(false)) +
                    ("hint" to JsonPrimitive("Not confident enough to $action; pick a node_id and use click_node / set_text."))
            )
        }
        withActionEnvelope(svc) { _ ->
            val ok = if (action == "click") {
                val target = svc.resolveClickable(chosen.node)
                    ?: return@withActionEnvelope jevError("no_clickable_ancestor")
                target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            } else {
                var editable: AccessibilityNodeInfo? = chosen.node
                while (editable != null && !editable.isEditable) editable = editable.parent
                if (editable == null) return@withActionEnvelope jevError("node_not_editable")
                val args = android.os.Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, typed)
                }
                editable.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            }
            JsonObject(found + ("acted" to JsonPrimitive(ok)))
        }
    }
}

fun screenCheckTool(jev: JevService): Tool = Tool(
    name = "screen_check",
    description = "Ask a yes/no question about what is on the phone screen right now (\"Did the message send?\", " +
        "\"Is a permission dialog showing?\", \"Is Wi-Fi on?\") and get a probability back in ~0.1 s from the Jev model, " +
        "without reading the window tree or a screenshot into your context. Use it to verify each step of a phone task.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("question", buildJsonObject { put("type", "string"); put("description", "A yes/no question about the current screen.") })
            },
            required = listOf("question"),
        )
    },
    execute = { input ->
        val question = input.str("question")
        RikkaAccessibilityService.instance?.let { wakeScreenIfNeeded(it) }
        text(
            if (question == null) jevError("missing_question") else AccessibilityServiceHandle.withService { svc ->
                val root = svc.rootInActiveWindow ?: return@withService jevError("no_active_window")
                val lines = mutableListOf<String>()
                svc.traverseTree(root = root, filter = ::defaultFilter, cap = 400, emit = { n, _, _ -> lines += describeNode(n) })
                val state = buildJsonObject {
                    put("app", root.packageName?.toString().orEmpty())
                    root.window?.title?.toString()?.let { put("window", it) }
                    put("screen_elements", lines.joinToString("\n").take(50_000))
                }
                val result = jev.ask("screen_check", state, mapOf("q" to JevQuestion.YesNo(question)))
                    ?: return@withService JEV_UNAVAILABLE
                val answer = result.yesNo("q") ?: return@withService JEV_UNAVAILABLE
                jev.log("screen_check", "\"${question.take(50)}\" -> ${JevService.pct(answer.probability)}", result.latencyMs)
                buildJsonObject {
                    put("answer", answer.isTrue)
                    put("probability", round3(answer.probability))
                    put("latency_ms", result.latencyMs)
                    put("screen_state", screenStateJson(svc, screenChanged = null))
                }
            }
        )
    },
)
