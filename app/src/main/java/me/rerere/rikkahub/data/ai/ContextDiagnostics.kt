package me.rerere.rikkahub.data.ai

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.Json
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import kotlin.uuid.Uuid

/** One line of the breakdown: a named contributor and its estimated size. */
data class ContextItem(val label: String, val tokens: Int, val detail: String? = null)

/** A group of contributors (system prompt, tool definitions, history…). */
data class ContextSection(val title: String, val items: List<ContextItem>) {
    val tokens: Int get() = items.sumOf { it.tokens }
}

/**
 * What one model request was made of. Sizes are local estimates (≈3 chars/token, CJK 1:1),
 * so they won't match the provider's count exactly, but the proportions are what matter.
 */
data class ContextReport(
    val atMillis: Long,
    val conversationId: Uuid?,
    /** The response this request produced, once known. */
    val responseMessageId: Uuid?,
    val modelName: String,
    val contextLength: Int?,
    val toolCount: Int,
    val sections: List<ContextSection>,
    val findings: List<String>,
) {
    val estimatedTotal: Int get() = sections.sumOf { it.tokens }
}

/**
 * Answers "why is this chat starting at 150k tokens?". Every model request is broken down at
 * the point it is assembled ([GenerationLoop.generateInternal]), after every transformer ran,
 * so it sees exactly what the provider receives: system prompt parts, every tool definition
 * (grouped per MCP server), memories, inlined attachments, prompt-shaping injections and each
 * tool's accumulated results. The latest report per conversation is kept in memory for the
 * chat's "context breakdown" sheet and the `context_report` tool.
 */
object ContextDiagnostics {
    private val schemaJson = Json { encodeDefaults = false }
    private val _reports = MutableStateFlow<Map<String, ContextReport>>(emptyMap())
    val reports: StateFlow<Map<String, ContextReport>> = _reports.asStateFlow()

    private fun key(conversationId: Uuid?) = conversationId?.toString() ?: "-"

    /** Latest report for [conversationId]; with no id, the most recent report of any chat. */
    fun latest(conversationId: Uuid?): ContextReport? =
        if (conversationId == null) _reports.value.values.maxByOrNull { it.atMillis }
        else _reports.value[key(conversationId)]

    /** Report for the request that produced [messageId], if it was recorded this session. */
    fun forMessage(messageId: Uuid): ContextReport? =
        _reports.value.values.firstOrNull { it.responseMessageId == messageId }

    fun record(report: ContextReport) {
        _reports.update { (it + (key(report.conversationId) to report)).entries.toList().takeLast(30).associate { e -> e.key to e.value } }
    }

    private fun tokens(text: String): Int = ContextBudgetPlanner.estimateTextTokens(text).toInt()

    internal fun schemaTokens(tool: Tool): Int {
        val schema = runCatching { tool.parameters() }.getOrNull()
        val schemaText = schema?.let { runCatching { schemaJson.encodeToString(InputSchema.serializer(), it) }.getOrNull() }.orEmpty()
        // + ~10 tokens of wrapper per tool (type/function/name keys) on every provider.
        return tokens(tool.name) + tokens(tool.description) + tokens(schemaText) + 10
    }

    /** "mcp__ab12cd34_frappe__get_doc" -> "frappe". */
    private fun mcpServerOf(toolName: String): String? {
        if (!toolName.startsWith("mcp__")) return null
        val middle = toolName.removePrefix("mcp__").substringBefore("__")
        return middle.substringAfter('_', middle).ifBlank { middle }
    }

    internal fun toolGroup(name: String): String = when {
        name.startsWith("mcp__") -> "MCP · ${mcpServerOf(name)}"
        name.startsWith("workspace_") -> "Workspace"
        name.startsWith("browser_") -> "In-app browser"
        name.startsWith("telegram_") || name.startsWith("tg_") -> "Telegram"
        name.startsWith("termux_") -> "Termux"
        name.startsWith("ssh_") -> "SSH"
        name.startsWith("skill") || name == "use_skill" -> "Skills"
        name in SCREEN_TOOLS -> "Phone use (screen)"
        else -> "Built-in tools"
    }

    private val SCREEN_TOOLS = setOf(
        "tap", "long_press", "swipe", "scroll", "read_window_tree", "find_node", "click_node", "set_text",
        "global_action", "take_screenshot", "wake_screen", "screen_find_node", "screen_check", "screen_snapshot", "screen_act",
    )

    @Suppress("DEPRECATION")
    fun analyze(
        conversationId: Uuid?,
        modelName: String,
        contextLength: Int?,
        systemParts: List<Pair<String, String>>,
        toolPrompts: List<Pair<String, String>>,
        tools: List<Tool>,
        preTransformMessages: List<UIMessage>,
        finalMessages: List<UIMessage>,
    ): ContextReport {
        val sections = mutableListOf<ContextSection>()

        // --- system prompt -------------------------------------------------------------
        val systemItems = systemParts
            .filter { it.second.isNotBlank() }
            .map { (label, text) -> ContextItem(label, tokens(text)) }
            .toMutableList()
        val promptItems = toolPrompts.filter { it.second.isNotBlank() }
            .map { (name, text) -> ContextItem(name, tokens(text)) }
            .sortedByDescending { it.tokens }
        if (promptItems.isNotEmpty()) {
            systemItems += ContextItem(
                "Tool instructions (${promptItems.size} tools)",
                promptItems.sumOf { it.tokens },
                promptItems.take(5).joinToString { "${it.label} ${it.tokens}" },
            )
        }
        sections += ContextSection("System prompt", systemItems)

        // --- tool definitions ----------------------------------------------------------
        val perTool = tools.map { it.name to schemaTokens(it) }
        val toolItems = perTool.groupBy { toolGroup(it.first) }
            .map { (group, list) ->
                ContextItem(
                    "$group (${list.size})",
                    list.sumOf { it.second },
                    list.sortedByDescending { it.second }.take(4).joinToString { "${it.first} ${it.second}" },
                )
            }
            .sortedByDescending { it.tokens }
        sections += ContextSection("Tool definitions (sent every request)", toolItems)

        // --- conversation --------------------------------------------------------------
        var userText = 0
        var assistantText = 0
        var reasoning = 0
        var toolArgs = 0
        var media = 0
        var mediaCount = 0
        val attachments = mutableListOf<ContextItem>()
        val toolResults = mutableMapOf<String, Int>()
        val toolResultCalls = mutableMapOf<String, Int>()
        var compactionSummary = 0
        finalMessages.filter { it.role != MessageRole.SYSTEM }.forEach { message ->
            message.parts.forEach { part ->
                when (part) {
                    is UIMessagePart.Text -> {
                        val t = part.text
                        when {
                            t.trimStart().startsWith("<UploadFile") -> {
                                val name = Regex("name=\"([^\"]*)\"").find(t)?.groupValues?.get(1) ?: "file"
                                attachments += ContextItem("📎 $name", tokens(t))
                            }

                            message.isSynthetic && message.role == MessageRole.USER -> compactionSummary += tokens(t)
                            message.role == MessageRole.USER -> userText += tokens(t)
                            else -> assistantText += tokens(t)
                        }
                    }

                    is UIMessagePart.Reasoning -> reasoning += tokens(part.reasoning)
                    is UIMessagePart.Tool -> {
                        toolArgs += tokens(part.input)
                        val out = part.output.sumOf { ContextBudgetPlanner.estimatePartTokens(it).toInt() }
                        toolResults[part.toolName] = (toolResults[part.toolName] ?: 0) + out
                        toolResultCalls[part.toolName] = (toolResultCalls[part.toolName] ?: 0) + 1
                    }

                    is UIMessagePart.ToolCall -> toolArgs += tokens(part.arguments)
                    is UIMessagePart.ToolResult -> {
                        val out = tokens(part.content.toString())
                        toolResults[part.toolName] = (toolResults[part.toolName] ?: 0) + out
                        toolResultCalls[part.toolName] = (toolResultCalls[part.toolName] ?: 0) + 1
                    }

                    is UIMessagePart.Image, is UIMessagePart.Video, is UIMessagePart.Audio, is UIMessagePart.Document -> {
                        mediaCount++
                        media += ContextBudgetPlanner.estimatePartTokens(part).toInt()
                    }

                    else -> Unit
                }
            }
        }
        val messageCount = finalMessages.count { it.role != MessageRole.SYSTEM }
        sections += ContextSection(
            "Conversation ($messageCount messages)",
            listOfNotNull(
                ContextItem("Your messages", userText).takeIf { userText > 0 },
                ContextItem("Assistant replies", assistantText).takeIf { assistantText > 0 },
                ContextItem("Reasoning kept in history", reasoning, "only re-sent by some providers").takeIf { reasoning > 0 },
                ContextItem("Tool-call arguments", toolArgs).takeIf { toolArgs > 0 },
                ContextItem("Compacted summary", compactionSummary).takeIf { compactionSummary > 0 },
                ContextItem("Images / audio / video ($mediaCount)", media).takeIf { media > 0 },
            ),
        )
        if (attachments.isNotEmpty()) {
            sections += ContextSection("Attached files (re-sent every turn)", attachments.sortedByDescending { it.tokens })
        }
        if (toolResults.isNotEmpty()) {
            sections += ContextSection(
                "Tool results in history",
                toolResults.entries.sortedByDescending { it.value }.map { (name, t) ->
                    ContextItem("$name ×${toolResultCalls[name] ?: 1}", t)
                },
            )
        }

        // Prompt shaping: whatever transformers added beyond inlined attachments (lorebooks,
        // mode injections, time/workspace reminders, templates).
        val pre = preTransformMessages.sumOf { ContextBudgetPlanner.estimateMessageTokens(it) }
        val post = finalMessages.sumOf { ContextBudgetPlanner.estimateMessageTokens(it) }
        val preDocs = preTransformMessages.sumOf { m -> m.parts.count { it is UIMessagePart.Document } } * 1_024
        val shaping = (post - pre - (attachments.sumOf { it.tokens } - preDocs)).toInt()
        if (shaping > 200) {
            sections += ContextSection(
                "Prompt shaping",
                listOf(ContextItem("Lorebooks, mode injections, reminders, templates", shaping)),
            )
        }

        val report = ContextReport(
            atMillis = System.currentTimeMillis(),
            conversationId = conversationId,
            responseMessageId = null,
            modelName = modelName,
            contextLength = contextLength,
            toolCount = tools.size,
            sections = sections.filter { it.items.isNotEmpty() },
            findings = emptyList(),
        )
        return report.copy(findings = findings(report, perTool, toolResults, attachments))
    }

    private fun findings(
        report: ContextReport,
        perTool: List<Pair<String, Int>>,
        toolResults: Map<String, Int>,
        attachments: List<ContextItem>,
    ): List<String> {
        val total = report.estimatedTotal.coerceAtLeast(1)
        fun pct(n: Int) = n * 100 / total
        fun k(n: Int) = if (n >= 1000) "${n / 1000}.${(n % 1000) / 100}k" else "$n"
        val out = mutableListOf<String>()
        val toolDefs = perTool.sumOf { it.second }
        perTool.groupBy { toolGroup(it.first) }
            .map { it.key to it.value.sumOf { p -> p.second } }
            .filter { (_, t) -> t >= 4_000 }
            .sortedByDescending { it.second }
            .forEach { (group, t) ->
                out += "$group adds ${k(t)} tokens of tool definitions to EVERY request (${pct(t)}%). " +
                    "Turn on Deferred tools (Settings → Tool approvals) or disable unused tools/servers for this assistant."
            }
        if (toolDefs > 0 && pct(toolDefs) >= 25 && out.isEmpty()) {
            out += "Tool definitions are ${pct(toolDefs)}% of the request (${report.toolCount} tools). Deferred tools would load them only when needed."
        }
        attachments.filter { it.tokens >= 8_000 }.forEach {
            out += "${it.label.removePrefix("📎 ")} is ${k(it.tokens)} tokens and is re-sent on every turn. " +
                "Start a new chat once you're done with it, or ask the agent to read only the parts it needs."
        }
        toolResults.entries.filter { it.value >= 8_000 }.sortedByDescending { it.value }.take(3).forEach { (name, t) ->
            out += "Results of $name total ${k(t)} tokens in history. Old results are already shortened; compaction or a fresh chat clears them."
        }
        report.sections.firstOrNull { it.title == "System prompt" }?.items?.forEach { item ->
            if (item.tokens >= 6_000) out += "${item.label} is ${k(item.tokens)} tokens of the system prompt."
        }
        report.contextLength?.takeIf { it > 0 }?.let { limit ->
            if (total >= limit * 0.6) out += "This request uses ~${pct(total).coerceAtMost(999)}% of the context limit's budget (${k(total)} / ${k(limit)})."
        }
        if (out.isEmpty()) out += "Nothing unusually large. The biggest part is \"${report.sections.maxByOrNull { it.tokens }?.title}\"."
        return out
    }

    /** Plain-text rendering for the `context_report` tool and sharing. */
    fun render(report: ContextReport): String = buildString {
        appendLine("Context breakdown for the last request to ${report.modelName} (≈${report.estimatedTotal} tokens, ${report.toolCount} tools)")
        report.sections.sortedByDescending { it.tokens }.forEach { section ->
            appendLine("- ${section.title}: ${section.tokens}")
            section.items.sortedByDescending { it.tokens }.take(8).forEach { item ->
                append("    • ${item.label}: ${item.tokens}")
                item.detail?.let { append(" ($it)") }
                appendLine()
            }
        }
        appendLine("Findings:")
        report.findings.forEach { appendLine("- $it") }
    }
}
