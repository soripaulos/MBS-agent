package me.rerere.rikkahub.jev

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.uuid.Uuid

private const val TAG = "JevService"
private const val LOG_CAPACITY = 60

/** One line in Settings → Jev → Recent decisions. */
data class JevDecision(
    val atMillis: Long,
    val feature: String,
    val summary: String,
    val latencyMs: Long,
    val ok: Boolean,
)

/** What the tool guard decided for one tool call. */
sealed interface JevGuardVerdict {
    /** Jev had nothing to add (disabled, unsure, excluded, failed). Normal approval rules apply. */
    data object NoChange : JevGuardVerdict

    /** Run without prompting even though the tool is approval-gated. */
    data class AutoApprove(val reason: String) : JevGuardVerdict

    /** Stop and ask the user even though the call would have run on its own. */
    data class RequireApproval(val reason: String) : JevGuardVerdict

    /** Refuse the call; [reason] is returned to the agent. */
    data class Block(val reason: String) : JevGuardVerdict
}

/** Per-turn plan computed from the user's latest message. */
data class JevTurnPlan(
    /** Model to run this turn on instead of the default, or null to keep it. */
    val modelId: Uuid? = null,
    val modelReason: String? = null,
    /** Extra system-prompt lines for this turn (skill hint, rule instructions). */
    val instructions: List<String> = emptyList(),
    /** Skills that keep their full description in the prompt this turn; null = no trimming. */
    val relevantSkills: Set<String>? = null,
) {
    val isEmpty: Boolean get() = modelId == null && instructions.isEmpty() && relevantSkills == null
}

/**
 * The one place the rest of the app talks to Jev. Every entry point FAILS OPEN: no key,
 * disabled feature, network error, timeout or low confidence all mean "change nothing",
 * so a Jev outage can never stall or break the agent.
 */
class JevService(
    private val client: JevClient,
    private val prefs: JevPreferences,
) {
    val config: StateFlow<JevConfig> get() = prefs.config

    private val _log = MutableStateFlow<List<JevDecision>>(emptyList())
    val recentDecisions: StateFlow<List<JevDecision>> = _log.asStateFlow()

    fun log(feature: String, summary: String, latencyMs: Long, ok: Boolean = true) {
        val entry = JevDecision(System.currentTimeMillis(), feature, summary, latencyMs, ok)
        _log.update { (listOf(entry) + it).take(LOG_CAPACITY) }
        Log.i(TAG, "[$feature] $summary (${latencyMs}ms)")
    }

    fun clearLog() {
        _log.value = emptyList()
    }

    /**
     * Raw call used by tools and hooks. Returns null (and logs) on any failure. Throws only
     * [CancellationException], so a user's stop still propagates.
     */
    suspend fun ask(
        feature: String,
        state: JsonElement,
        questions: Map<String, JevQuestion>,
        config: JevConfig = prefs.config.value,
    ): JevResult? {
        if (!config.isUsable || questions.isEmpty()) return null
        val started = System.currentTimeMillis()
        return try {
            val result = client.ask(config, state, questions)
            prefs.recordCall(result.usage.inputTokens, result.latencyMs, failed = false)
            result
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val latency = System.currentTimeMillis() - started
            prefs.recordCall(0, latency, failed = true)
            log(feature, "failed: ${e.message?.take(160)}", latency, ok = false)
            null
        }
    }

    /** Throwing variant for the Settings "Test connection" button. */
    suspend fun test(config: JevConfig): JevResult = client.ask(
        config,
        JevClient.textState("The user wrote: please set an alarm for 7am tomorrow."),
        mapOf(
            "is_request" to JevQuestion.YesNo("Is the user asking the assistant to do something?"),
            "intent" to JevQuestion.Choice(
                "What kind of task is it?",
                mapOf(
                    "alarm" to "Alarms, timers and reminders",
                    "message" to "Sending a message or email",
                    "other" to "Anything else",
                ),
            ),
        ),
    )

    // ---------------------------------------------------------------- tool guard

    /**
     * Judge one tool call before it executes.
     *
     * @param approvalGated the tool asks for approval by default (sending, deleting, shell…).
     * @param preApproved the user already allowed it (Always-allow, per-chat allow, YOLO, cron).
     * @param unattended nobody can answer an approval prompt (cron, sub-agent, Telegram).
     */
    suspend fun guardTool(
        toolName: String,
        toolDescription: String?,
        argumentsJson: String,
        userRequest: String?,
        approvalGated: Boolean,
        preApproved: Boolean,
        unattended: Boolean,
    ): JevGuardVerdict {
        val config = prefs.config.value
        if (!config.isUsable) return JevGuardVerdict.NoChange
        if (toolName in NEVER_GUARDED || config.toolGuard.excludedTools.any { toolPatternMatches(it, toolName) }) {
            return JevGuardVerdict.NoChange
        }
        val guard = config.toolGuard
        val wouldPrompt = approvalGated && !preApproved
        val wouldRun = !wouldPrompt
        val canAutoApprove = wouldPrompt && guard.autoApproveSafe
        val canEscalate = wouldRun && guard.escalateRisky && (approvalGated || guard.allTools)
        val rules = config.rules.filter {
            it.enabled && it.trigger == JevRuleTrigger.BEFORE_TOOL && it.question.isNotBlank() &&
                toolPatternMatches(it.toolFilter, toolName) &&
                // REQUIRE_APPROVAL is a no-op when the call is going to prompt anyway.
                !(it.action == JevRuleAction.REQUIRE_APPROVAL && wouldPrompt)
        }
        if (!canAutoApprove && !canEscalate && rules.isEmpty()) return JevGuardVerdict.NoChange

        val questions = linkedMapOf<String, JevQuestion>()
        if (canAutoApprove || canEscalate) {
            questions["risky"] = JevQuestion.YesNo(
                instructions = "Would executing this tool call risk harm the user did not clearly ask for: " +
                    "deleting or overwriting data, sending messages, emails or posts to other people, " +
                    "spending money, changing security or account settings, exposing private data, " +
                    "or any other hard-to-undo action?",
                trueMeans = "risky or hard to undo",
                falseMeans = "harmless, read-only, easily undone, or plainly what the user asked for",
            )
            questions["on_task"] = JevQuestion.YesNo(
                instructions = "Is this tool call a reasonable, expected step toward what the user asked for in user_request?",
                trueMeans = "on task",
                falseMeans = "unrelated to or beyond what the user asked",
            )
        }
        rules.forEachIndexed { i, rule -> questions["rule_$i"] = JevQuestion.YesNo(rule.question) }

        val state = buildJsonObject {
            put("user_request", (userRequest ?: "(unknown)").take(4_000))
            put("tool", toolName)
            toolDescription?.takeIf { it.isNotBlank() }?.let { put("tool_description", it.take(600)) }
            put("arguments", argumentsJson.take(6_000))
        }
        val result = ask("tool_guard", state, questions, config) ?: return JevGuardVerdict.NoChange

        // User rules first: an explicit rule beats the generic judgement.
        rules.forEachIndexed { i, rule ->
            val p = result.yesNo("rule_$i")?.probability ?: return@forEachIndexed
            if (p >= rule.threshold) {
                val label = rule.name.ifBlank { rule.question.take(60) }
                when (rule.action) {
                    JevRuleAction.BLOCK_TOOL -> {
                        val reason = rule.text.ifBlank { "blocked by the user's Jev rule \"$label\"" }
                        log("rule", "$toolName blocked by \"$label\" (p=${pct(p)})", result.latencyMs)
                        return JevGuardVerdict.Block(reason)
                    }

                    JevRuleAction.REQUIRE_APPROVAL -> {
                        log("rule", "$toolName needs approval per \"$label\" (p=${pct(p)})", result.latencyMs)
                        return approvalOrBlock(unattended, guard, "Jev rule \"$label\" requires approval for this call")
                    }

                    else -> Unit // USER_MESSAGE-only actions never reach here
                }
            }
        }

        val risky = result.yesNo("risky")?.probability
        val onTask = result.yesNo("on_task")?.probability
        if (risky == null || onTask == null) return JevGuardVerdict.NoChange
        val summary = "risk ${pct(risky)}, on-task ${pct(onTask)}"

        if (canEscalate && (risky >= guard.riskThreshold || (1 - onTask) >= guard.riskThreshold)) {
            log("tool_guard", "$toolName escalated ($summary)", result.latencyMs)
            val why = if (risky >= guard.riskThreshold) "looks risky" else "doesn't match the request"
            return approvalOrBlock(unattended, guard, "Jev flagged this call: it $why ($summary)")
        }
        if (canAutoApprove && (1 - risky) >= guard.safeThreshold && onTask >= guard.safeThreshold) {
            log("tool_guard", "$toolName auto-approved ($summary)", result.latencyMs)
            return JevGuardVerdict.AutoApprove("Jev judged it safe and on-task ($summary)")
        }
        log("tool_guard", "$toolName unchanged ($summary)", result.latencyMs)
        return JevGuardVerdict.NoChange
    }

    private fun approvalOrBlock(unattended: Boolean, guard: ToolGuardConfig, reason: String): JevGuardVerdict =
        if (unattended) {
            if (guard.blockRiskyWhenUnattended) JevGuardVerdict.Block("$reason. Nobody is available to approve it in this unattended run.")
            else JevGuardVerdict.NoChange
        } else {
            JevGuardVerdict.RequireApproval(reason)
        }

    // ---------------------------------------------------------------- turn planning

    /**
     * Model routing + skill hint + USER_MESSAGE rules, answered in ONE Jev call.
     *
     * @param skills enabled skills for this assistant, name -> description.
     * @param routingAllowed false when the router must leave the model alone this turn.
     */
    suspend fun planTurn(
        userMessage: String,
        previousAssistantMessage: String?,
        skills: Map<String, String>,
        routingAllowed: Boolean,
    ): JevTurnPlan {
        val config = prefs.config.value
        if (!config.isUsable || userMessage.isBlank()) return JevTurnPlan()
        val router = config.modelRouter
        val routeOn = routingAllowed && router.enabled && router.fastModelId != null
        val skillOn = config.skillHint.enabled && skills.isNotEmpty()
        val rules = config.rules.filter {
            it.enabled && it.trigger == JevRuleTrigger.USER_MESSAGE && it.question.isNotBlank() &&
                (it.action != JevRuleAction.USE_MODEL || (routingAllowed && it.modelId != null))
        }
        if (!routeOn && !skillOn && rules.isEmpty()) return JevTurnPlan()

        val questions = linkedMapOf<String, JevQuestion>()
        if (routeOn) {
            questions["complexity"] = JevQuestion.Choice(
                instructions = "How demanding is the user's latest message for an AI assistant to handle well?",
                options = mapOf(
                    "simple" to router.simpleCriteria.ifBlank { ModelRouterConfig.DEFAULT_SIMPLE },
                    "complex" to router.complexCriteria.ifBlank { ModelRouterConfig.DEFAULT_COMPLEX },
                ),
            )
        }
        val skillKeys = mutableMapOf<String, String>() // question key -> skill name
        if (skillOn) {
            val options = linkedMapOf("none" to "None of these skills is relevant to the request")
            skills.entries.take(JevQuestion.MAX_CHOICE_OPTIONS - 1).forEachIndexed { i, (name, desc) ->
                val key = "s$i"
                skillKeys[key] = name
                options[key] = "$name: ${desc.ifBlank { name }}".take(400)
            }
            questions["skill"] = JevQuestion.Choice(
                instructions = "Which skill (a saved playbook the assistant can load) would most help answer the user's latest message?",
                options = options,
            )
        }
        rules.forEachIndexed { i, rule -> questions["rule_$i"] = JevQuestion.YesNo(rule.question) }

        val state = buildJsonObject {
            put("user_message", userMessage.take(8_000))
            previousAssistantMessage?.takeIf { it.isNotBlank() }?.let { put("previous_assistant_message", it.take(1_500)) }
        }
        val result = ask("turn_plan", state, questions, config) ?: return JevTurnPlan()

        var modelId: Uuid? = null
        var modelReason: String? = null
        val instructions = mutableListOf<String>()
        val notes = mutableListOf<String>()

        rules.forEachIndexed { i, rule ->
            val p = result.yesNo("rule_$i")?.probability ?: return@forEachIndexed
            if (p < rule.threshold) return@forEachIndexed
            val label = rule.name.ifBlank { rule.question.take(60) }
            when (rule.action) {
                JevRuleAction.ADD_INSTRUCTION -> if (rule.text.isNotBlank()) {
                    instructions += rule.text.trim()
                    notes += "rule \"$label\" -> instruction"
                }

                JevRuleAction.USE_MODEL -> if (modelId == null) {
                    modelId = rule.modelId
                    modelReason = "Jev rule \"$label\""
                    notes += "rule \"$label\" -> model"
                }

                else -> Unit
            }
        }
        if (modelId == null && routeOn) {
            result.choice("complexity")?.let { c ->
                val pSimple = c.probabilities["simple"] ?: 0.0
                if (pSimple >= router.threshold) {
                    modelId = router.fastModelId
                    modelReason = "simple turn (${pct(pSimple)})"
                } else if (router.strongModelId != null && c.choice == "complex") {
                    modelId = router.strongModelId
                    modelReason = "complex turn (${pct(c.probabilities["complex"] ?: 0.0)})"
                }
                notes += "route: ${c.choice} ${pct(c.probability)}"
            }
        }
        var relevantSkills: Set<String>? = null
        if (skillOn) {
            result.choice("skill")?.let { c ->
                val name = skillKeys[c.choice]
                if (name != null && c.probability >= config.skillHint.threshold) {
                    instructions += "The \"$name\" skill matches this request. Load it with use_skill and follow it before improvising."
                    notes += "skill: $name ${pct(c.probability)}"
                }
                if (config.skillHint.trimListing && skills.size > config.skillHint.maxListed) {
                    // Keep the plausible ones (a small floor filters the long tail), capped.
                    relevantSkills = c.ranked()
                        .filter { (key, p) -> key != "none" && p >= 0.02 }
                        .take(config.skillHint.maxListed.coerceIn(1, 20))
                        .mapNotNull { (key, _) -> skillKeys[key] }
                        .toSet()
                    notes += "skills listed: ${relevantSkills?.size}/${skills.size}"
                }
            }
        }
        log("turn_plan", notes.ifEmpty { listOf("no change") }.joinToString("; "), result.latencyMs)
        return JevTurnPlan(modelId, modelReason, instructions, relevantSkills)
    }

    companion object {
        /**
         * Human-input and Jev's own read-only helpers are never judged. (The find tools are
         * judged: with click/text set they act on the page or screen.)
         */
        val NEVER_GUARDED = setOf("ask_user", "jev_decide", "browser_check", "screen_check")

        fun pct(p: Double): String = "${(p * 100).toInt()}%"
    }
}
