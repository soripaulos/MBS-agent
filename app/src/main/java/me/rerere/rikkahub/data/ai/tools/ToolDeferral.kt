package me.rerere.rikkahub.data.ai.tools

import android.content.Context
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.ContextDiagnostics
import me.rerere.rikkahub.jev.JevQuestion
import me.rerere.rikkahub.jev.JevService
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid

/**
 * How tool definitions are loaded into requests. Stored in plain SharedPreferences because
 * the generation loop reads it on every step and it must never block.
 */
object ToolLoadingPrefs {
    enum class Mode {
        /** Every enabled tool's full definition on every request (old behaviour). */
        OFF,

        /** Defer the biggest tools only once definitions pass [thresholdTokens]. */
        AUTO,

        /** Load only core and already-used tools; everything else is found via tool_search. */
        AGGRESSIVE,
    }

    private const val PREFS = "tool_loading"

    fun mode(context: Context): Mode = runCatching {
        Mode.valueOf(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("mode", Mode.AUTO.name)!!)
    }.getOrDefault(Mode.AUTO)

    fun setMode(context: Context, mode: Mode) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("mode", mode.name).apply()

    /** Above this many tokens of definitions, AUTO starts deferring. */
    fun thresholdTokens(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("threshold", 8_000)

    fun setThresholdTokens(context: Context, value: Int) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt("threshold", value.coerceIn(2_000, 100_000)).apply()
}

/**
 * Deferred tool loading, the same idea as Claude Code's / Anthropic's tool search: instead of
 * sending every tool's full JSON schema on every request (MCP servers alone can be 20–40k
 * tokens), large tools are replaced by a compact name catalogue plus one `tool_search` tool.
 * When the model needs one, it searches by intent; the matches are loaded for the rest of the
 * conversation and appear with full schemas from the next step on.
 *
 * Never deferred: core tools (asking the user, skills, memory, tool_search itself) and any tool
 * already used in this conversation, so an ongoing task never loses a tool mid-way. With Jev
 * on, the tools that fit the user's message are pre-loaded before the first step, so most
 * turns need no search round-trip at all.
 */
object ToolDeferral {
    const val SEARCH_TOOL = "tool_search"
    private const val MAX_ACTIVATE = 8
    private const val LOADED_BUDGET_TOKENS = 5_000

    private val CORE = setOf(
        SEARCH_TOOL, "ask_user", "use_skill", "skill_get_content", "memory_tool",
        "context_report", "get_time_info",
    )

    /** conversation -> tool names loaded via search / pre-selection this session. */
    private val activated = ConcurrentHashMap<String, MutableSet<String>>()

    private fun key(conversationId: Uuid?) = conversationId?.toString() ?: "-"

    fun activate(conversationId: Uuid?, names: Collection<String>) {
        activated.getOrPut(key(conversationId)) { ConcurrentHashMap.newKeySet() }.addAll(names)
    }

    fun activated(conversationId: Uuid?): Set<String> = activated[key(conversationId)].orEmpty()

    data class Plan(val active: List<Tool>, val deferred: List<Tool>) {
        val isDeferring: Boolean get() = deferred.isNotEmpty()
    }

    fun plan(context: Context, all: List<Tool>, conversationId: Uuid?, history: List<UIMessage>): Plan {
        val mode = ToolLoadingPrefs.mode(context)
        if (mode == ToolLoadingPrefs.Mode.OFF || all.size < 6) return Plan(all, emptyList())
        val sizes = all.associate { it.name to ContextDiagnostics.schemaTokens(it) }
        val total = sizes.values.sum()
        if (mode == ToolLoadingPrefs.Mode.AUTO && total <= ToolLoadingPrefs.thresholdTokens(context)) {
            return Plan(all, emptyList())
        }
        val used = history.flatMapTo(mutableSetOf()) { m ->
            m.parts.filterIsInstance<UIMessagePart.Tool>().map { it.toolName }
        }
        val pinned = CORE + used + activated(conversationId)
        val candidates = all.filter { it.name !in pinned }
        val toDefer = mutableSetOf<String>()
        if (mode == ToolLoadingPrefs.Mode.AGGRESSIVE) {
            candidates.forEach { toDefer += it.name }
        } else {
            // MCP first (opaque, usually the largest), then the biggest local tools, until what
            // stays loaded fits the budget.
            var loaded = total
            candidates
                .sortedWith(compareByDescending<Tool> { it.name.startsWith("mcp__") }.thenByDescending { sizes[it.name] ?: 0 })
                .forEach { tool ->
                    if (loaded > LOADED_BUDGET_TOKENS) {
                        toDefer += tool.name
                        loaded -= sizes[tool.name] ?: 0
                    }
                }
        }
        if (toDefer.size < 3) return Plan(all, emptyList()) // not worth a search round-trip
        val (deferred, active) = all.partition { it.name in toDefer }
        return Plan(active, deferred)
    }

    /** Compact catalogue: "Group: name1, name2 · Group2: …" — names only, a few tokens each. */
    fun catalogue(deferred: List<Tool>): String = deferred
        .groupBy { ContextDiagnostics.toolGroup(it.name) }
        .entries.sortedBy { it.key }
        .joinToString(" · ") { (group, tools) -> "$group: " + tools.joinToString(", ") { it.name } }

    /** Ranks [deferred] tools for [query] by keyword overlap with name + description. */
    private fun keywordRank(deferred: List<Tool>, query: String): List<Tool> {
        val words = query.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length > 2 }.toSet()
        if (words.isEmpty()) return emptyList()
        return deferred
            .map { tool ->
                val hay = (tool.name.replace('_', ' ') + " " + tool.description).lowercase()
                val nameHay = tool.name.lowercase()
                tool to words.sumOf { w -> (if (w in nameHay) 3 else 0) + (if (w in hay) 1 else 0).toInt() }
            }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .map { it.first }
    }

    private fun describe(tool: Tool) = "${tool.name}: ${tool.description.replace('\n', ' ').take(160)}"

    /** Jev ranking over deferred tools (≤254), best first; null when Jev isn't usable. */
    private suspend fun jevRank(jev: JevService?, deferred: List<Tool>, intent: String): List<Pair<Tool, Double>>? {
        if (jev == null || !jev.config.value.isUsable || deferred.size < 2) return null
        val pool = deferred.take(JevQuestion.MAX_CHOICE_OPTIONS - 1)
        val options = linkedMapOf("none" to "None of these tools is needed")
        pool.forEachIndexed { i, t -> options["t$i"] = describe(t) }
        val result = jev.ask(
            "tool_search",
            kotlinx.serialization.json.JsonPrimitive(intent.take(4_000)),
            mapOf("tool" to JevQuestion.Choice("Which tool would an assistant need to carry out this request?", options)),
        ) ?: return null
        val choice = result.choice("tool") ?: return null
        return choice.ranked().mapNotNull { (key, p) ->
            key.removePrefix("t").toIntOrNull()?.let { pool.getOrNull(it) }?.let { it to p }
        }
    }

    /**
     * Before the first step of a turn: load the deferred tools Jev thinks the user's message
     * needs, so the model usually has them without searching. Returns the names it loaded.
     */
    suspend fun preselect(jev: JevService?, conversationId: Uuid?, userMessage: String, deferred: List<Tool>): List<String> {
        val ranked = jevRank(jev, deferred, userMessage) ?: return emptyList()
        val picked = ranked.filter { it.second >= 0.12 }.take(5).map { it.first.name }
        if (picked.isNotEmpty()) {
            activate(conversationId, picked)
            jev?.log("tool_preload", picked.joinToString(", "), 0)
        }
        return picked
    }

    fun searchTool(deferred: List<Tool>, conversationId: Uuid?, jev: JevService?): Tool = Tool(
        name = SEARCH_TOOL,
        description = "Load tools that are available but not loaded yet, to save context. Describe what you " +
            "need to do (e.g. \"send a Telegram message\", \"read an ERPNext invoice\") or pass exact names; the " +
            "matching tools are loaded and usable from your NEXT step. Not loaded yet: " + catalogue(deferred),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("query", buildJsonObject {
                        put("type", "string")
                        put("description", "What you want to do, in words.")
                    })
                    put("names", buildJsonObject {
                        put("type", "array")
                        put("items", buildJsonObject { put("type", "string") })
                        put("description", "Exact tool names from the list, if you already know them.")
                    })
                },
            )
        },
        execute = { input ->
            val obj = input.jsonObject
            val query = obj["query"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val exact = (obj["names"] as? JsonArray).orEmpty()
                .mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                .filter { n -> deferred.any { it.name == n } }
            val ranked: List<Tool> = when {
                query.isBlank() -> emptyList()
                else -> jevRank(jev, deferred, query)?.filter { it.second >= 0.05 }?.map { it.first }
                    ?.ifEmpty { null }
                    ?: keywordRank(deferred, query)
            }
            val chosen = (exact + ranked.map { it.name }).distinct().take(MAX_ACTIVATE)
            activate(conversationId, chosen)
            val text = buildJsonObject {
                if (chosen.isEmpty()) {
                    put("loaded", buildJsonArray { })
                    put("hint", "Nothing matched. Rephrase, or pick names from the list in this tool's description.")
                } else {
                    put("loaded", buildJsonArray {
                        chosen.forEach { n -> deferred.firstOrNull { it.name == n }?.let { add(describe(it)) } }
                    })
                    put("next", "These tools are available from your next step - call them directly.")
                }
            }
            listOf(UIMessagePart.Text(text.toString()))
        },
    )
}
