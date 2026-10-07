package me.rerere.rikkahub.data.ai.tools.local

import android.accessibilityservice.GestureDescription
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.AgentTurnTracker
import me.rerere.rikkahub.data.ai.tools.ToolInvocationContext
import me.rerere.rikkahub.jev.JevQuestion
import me.rerere.rikkahub.jev.JevService
import me.rerere.rikkahub.service.ActionLogEntry
import me.rerere.rikkahub.service.RikkaAccessibilityService

/*
 * Phone use the browser-use way: one compact, numbered text view of the screen
 * (screen_snapshot) and one tool that acts on an entry AND returns the new screen
 * (screen_act). A typical step is a single round trip with a few hundred tokens, instead of
 * read_window_tree (hundreds of JSON nodes with bounds) + click_node + read_window_tree again,
 * or a screenshot for a vision model.
 */

private data class SnapEntry(
    val node: AccessibilityNodeInfo,
    val line: String,
    val text: String?,
    val desc: String?,
    val viewId: String?,
    val cls: String?,
)

/** Entries of the most recent snapshot; refs in screen_act index into this. */
private object ScreenSnapshotCache {
    @Volatile
    var entries: List<SnapEntry> = emptyList()
}

private const val MAX_LINES = 150

private fun kindOf(cls: String?): String {
    val simple = cls?.substringAfterLast('.') ?: return "view"
    return when {
        simple.contains("EditText") -> "input"
        simple.contains("Button") -> "button"
        simple.contains("CheckBox") -> "checkbox"
        simple.contains("Switch") || simple.contains("Toggle") -> "switch"
        simple.contains("Image") -> "image"
        simple.contains("TextView") -> "text"
        simple.contains("RecyclerView") || simple.contains("ListView") || simple.contains("ScrollView") -> "list"
        simple.contains("Tab") -> "tab"
        else -> "view"
    }
}

private fun buildEntries(svc: RikkaAccessibilityService, root: AccessibilityNodeInfo): Pair<List<SnapEntry>, Boolean> {
    val out = mutableListOf<SnapEntry>()
    val seenLabels = HashSet<String>()
    val (_, _, truncated) = svc.traverseTree(
        root = root,
        filter = ::defaultFilter,
        cap = MAX_LINES * 2,
        emit = { n, _, _ ->
            if (out.size >= MAX_LINES) return@traverseTree
            val text = n.text?.toString()?.replace('\n', ' ')?.trim()?.takeIf { it.isNotEmpty() }
            val desc = n.contentDescription?.toString()?.replace('\n', ' ')?.trim()?.takeIf { it.isNotEmpty() }
            val viewId = n.viewIdResourceName?.substringAfterLast('/')?.takeIf { it.isNotEmpty() }
            val cls = n.className?.toString()
            val flags = listOfNotNull(
                "tap".takeIf { n.isClickable },
                "edit".takeIf { n.isEditable },
                "scroll".takeIf { n.isScrollable },
                "on".takeIf { n.isCheckable && n.isChecked },
                "off".takeIf { n.isCheckable && !n.isChecked },
                "disabled".takeIf { !n.isEnabled },
            )
            // Plain text that merely repeats a label already listed adds nothing.
            val label = text ?: desc
            if (flags.isEmpty() && label != null && !seenLabels.add(label)) return@traverseTree
            label?.let { seenLabels += it }
            val line = buildString {
                append('[').append(out.size).append("] ").append(kindOf(cls))
                text?.let { append(" \"").append(it.take(90)).append('"') }
                if (desc != null && desc != text) append(" (").append(desc.take(80)).append(')')
                viewId?.let { append(" #").append(it) }
                if (flags.isNotEmpty()) append(" {").append(flags.joinToString(",")).append('}')
            }
            out += SnapEntry(n, line, text, desc, viewId, cls)
        },
    )
    return out to (truncated || out.size >= MAX_LINES)
}

private fun snapshotJson(svc: RikkaAccessibilityService): JsonObject {
    val root = svc.rootInActiveWindow ?: return buildJsonObject { put("error", "no_active_window") }
    val (entries, truncated) = buildEntries(svc, root)
    ScreenSnapshotCache.entries = entries
    return buildJsonObject {
        put("app", root.packageName?.toString().orEmpty())
        root.window?.title?.toString()?.takeIf { it.isNotEmpty() }?.let { put("window", it) }
        put("screen", entries.joinToString("\n") { it.line })
        if (truncated) put("truncated", true)
        put("state", screenStateJson(svc, screenChanged = null))
    }
}

fun screenSnapshotTool(
    invocationContext: ToolInvocationContext = ToolInvocationContext.EMPTY,
    streamer: InteractiveToolStreamer = InteractiveToolStreamer.NoOp,
): Tool = Tool(
    name = "screen_snapshot",
    description = "Compact numbered text view of what's on the phone screen now ([n] kind \"text\" (description) " +
        "#id {tap,edit,scroll,on/off}). Much cheaper and faster than read_window_tree or a screenshot - use it first, " +
        "then screen_act(ref=n) which also returns the next screen. Refs change with every snapshot.",
    parameters = { InputSchema.Obj(properties = buildJsonObject { }) },
    execute = {
        RikkaAccessibilityService.instance?.let { wakeScreenIfNeeded(it) }
        val payload = AccessibilityServiceHandle.withService { svc -> snapshotJson(svc) }
        streamer.streamIfHeadless(invocationContext, "ScreenSnapshot")
        listOf(UIMessagePart.Text(payload.toString()))
    },
)

private fun JsonElement.arg(key: String): String? =
    (this as? JsonObject)?.get(key)?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }

/** Re-finds [old] in a fresh tree by its identity (id/text/description/class). */
private fun rematch(fresh: List<SnapEntry>, old: SnapEntry): SnapEntry? =
    fresh.firstOrNull { it.viewId == old.viewId && it.text == old.text && it.desc == old.desc && it.cls == old.cls && (old.viewId != null || old.text != null || old.desc != null) }

private suspend fun pickByIntent(intent: String, entries: List<SnapEntry>, app: String): SnapEntry? {
    val jev = runCatching { org.koin.core.context.GlobalContext.get().get<JevService>() }.getOrNull()
    if (jev != null && jev.config.value.isUsable) {
        val pool = entries.take(JevQuestion.MAX_CHOICE_OPTIONS - 1)
        val options = linkedMapOf("none" to "Nothing on screen matches")
        pool.forEachIndexed { i, e -> options["n$i"] = e.line }
        val result = jev.ask(
            "screen_act",
            buildJsonObject { put("app", app); put("looking_for", intent) },
            mapOf("pick" to JevQuestion.Choice("Which on-screen element best matches looking_for?", options)),
        )
        val choice = result?.choice("pick")
        if (choice != null) {
            if (choice.choice == "none" || choice.probability < jev.config.value.phone.actThreshold) return null
            jev.log("screen_act", "\"${intent.take(40)}\" -> ${options[choice.choice]?.take(60)}", result?.latencyMs ?: 0)
            return pool.getOrNull(choice.choice.removePrefix("n").toIntOrNull() ?: -1)
        }
    }
    val words = intent.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length > 1 }
    if (words.isEmpty()) return null
    val scored = entries.map { e -> e to words.count { it in e.line.lowercase() } }
        .filter { it.second > 0 }
        .sortedByDescending { it.second }
    val best = scored.firstOrNull() ?: return null
    if (best.second * 2 < words.size) return null
    if (scored.size > 1 && scored[1].second == best.second) return null
    return best.first
}

fun screenActTool(
    invocationContext: ToolInvocationContext = ToolInvocationContext.EMPTY,
    streamer: InteractiveToolStreamer = InteractiveToolStreamer.NoOp,
): Tool = Tool(
    name = "screen_act",
    description = "Act on the phone screen and get the NEW screen back in the same call. Target an entry by `ref` from " +
        "the latest screen_snapshot/screen_act result, or describe it in `intent` (\"the Send button\") and the fast Jev " +
        "model picks it. Actions: tap, long_press, type (text replaces the field), scroll_down, scroll_up (ref optional: " +
        "first scrollable list). Use global_action for back/home/recents. Returns {done, target, after, screen}.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("action", buildJsonObject {
                    put("type", "string")
                    putJsonArray("enum") { add("tap"); add("long_press"); add("type"); add("scroll_down"); add("scroll_up") }
                })
                put("ref", buildJsonObject { put("type", "integer"); put("description", "Entry number from the latest snapshot") })
                put("intent", buildJsonObject { put("type", "string"); put("description", "Describe the element instead of giving ref") })
                put("text", buildJsonObject { put("type", "string"); put("description", "Text for action=type") })
            },
            required = listOf("action"),
        )
    },
    needsApproval = { true },
    execute = { input ->
        AgentTurnTracker.recordAutomationAction()
        RikkaAccessibilityService.instance?.let { wakeScreenIfNeeded(it) }
        val payload = AccessibilityServiceHandle.withService { svc -> runScreenAct(svc, input) }
        streamer.streamIfHeadless(invocationContext, "ScreenAct ${input.arg("action")}")
        listOf(UIMessagePart.Text(payload.toString()))
    },
)

private suspend fun runScreenAct(svc: RikkaAccessibilityService, input: JsonElement): JsonObject {
    val action = input.arg("action") ?: return buildJsonObject { put("error", "missing_action") }
    if (action !in setOf("tap", "long_press", "type", "scroll_down", "scroll_up")) {
        return buildJsonObject { put("error", "bad_action") }
    }
    val text = input.arg("text")
    if (action == "type" && text == null) return buildJsonObject { put("error", "missing_text") }
    val ref = (input as? JsonObject)?.get("ref")?.jsonPrimitive?.intOrNull
    val intent = input.arg("intent")
    val isScroll = action.startsWith("scroll")
    if (ref == null && intent == null && !isScroll) return buildJsonObject { put("error", "give ref or intent") }

    val root = svc.rootInActiveWindow ?: return buildJsonObject { put("error", "no_active_window") }
    var entries = ScreenSnapshotCache.entries
    val target: SnapEntry? = when {
        ref != null -> {
            val cached = entries.getOrNull(ref)
            when {
                cached == null -> null
                cached.node.refresh() -> cached
                else -> {
                    // The screen moved on since that snapshot; find the same element again.
                    entries = buildEntries(svc, root).first.also { ScreenSnapshotCache.entries = it }
                    rematch(entries, cached)
                }
            }
        }

        intent != null -> {
            entries = buildEntries(svc, root).first.also { ScreenSnapshotCache.entries = it }
            pickByIntent(intent, entries, root.packageName?.toString().orEmpty())
        }

        else -> null // scroll without a target
    }
    if (target == null && !isScroll) {
        return buildJsonObject {
            put("error", if (ref != null) "stale_ref" else "no_confident_match")
            put("hint", "Here is the current screen; pick a ref from it.")
            put("screen", snapshotJson(svc))
        }
    }

    val result = withActionEnvelope(svc) { _ ->
        val ok: Boolean = when (action) {
            "tap" -> {
                val clickable = svc.resolveClickable(target!!.node)
                if (clickable != null) {
                    clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                } else {
                    // Not clickable in the a11y tree (custom-drawn views): tap its centre.
                    val r = Rect().also { target.node.getBoundsInScreen(it) }
                    val path = svc.buildTapPath(r.exactCenterX(), r.exactCenterY())
                    svc.dispatchGestureAsync(
                        GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0L, 50L)).build()
                    )
                }
            }

            "long_press" -> (svc.resolveClickable(target!!.node) ?: target.node)
                .performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)

            "type" -> {
                var editable: AccessibilityNodeInfo? = target!!.node
                while (editable != null && !editable.isEditable) editable = editable.parent
                if (editable == null) return@withActionEnvelope buildJsonObject { put("error", "not_editable") }
                editable.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                val args = android.os.Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                }
                editable.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            }

            else -> {
                var scrollable: AccessibilityNodeInfo? = target?.node
                while (scrollable != null && !scrollable.isScrollable) scrollable = scrollable.parent
                val node = scrollable ?: entries.firstOrNull { it.node.isScrollable }?.node
                    ?: return@withActionEnvelope buildJsonObject { put("error", "nothing_scrollable") }
                node.performAction(
                    if (action == "scroll_down") AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                    else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                )
            }
        }
        svc.appendLog(
            ActionLogEntry(
                type = "screen_act",
                paramsSummary = "$action ${target?.line?.take(60) ?: ""}",
                success = ok,
                timestampMs = System.currentTimeMillis(),
            )
        )
        buildJsonObject {
            put("done", ok)
            target?.let { put("target", it.line) }
        }
    }
    if (result.containsKey("error")) return result
    return JsonObject(result + ("screen" to snapshotJson(svc)))
}
