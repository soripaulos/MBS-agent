package me.rerere.rikkahub.jev

import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

/**
 * Everything the user can configure about Jev. Persisted as one JSON blob in
 * [JevPreferences]. Every field has a default so older stored blobs keep decoding.
 *
 * Jev never replaces the chat model: it makes small, fast decisions *around* it. Each
 * feature below is independently switchable and fails open — if Jev is slow, down or
 * unsure, the app behaves exactly as it would without it.
 */
@Serializable
data class JevConfig(
    val enabled: Boolean = false,
    val apiKey: String = "",
    val baseUrl: String = DEFAULT_BASE_URL,
    /** `jev-latest`, `jev-preview` or a pinned version such as `jev-1.13.0`. */
    val model: String = "jev-latest",
    /** Hard budget per Jev call. Jev answers in ~70–500 ms; past this the caller proceeds without it. */
    val timeoutMs: Long = 4_000,

    val toolGuard: ToolGuardConfig = ToolGuardConfig(),
    val modelRouter: ModelRouterConfig = ModelRouterConfig(),
    val skillHint: SkillHintConfig = SkillHintConfig(),
    val browser: BrowserAssistConfig = BrowserAssistConfig(),
    val phone: PhoneAssistConfig = PhoneAssistConfig(),
    /** Registers the general-purpose `jev_decide` tool (bulk classify / rank / filter). */
    val decideTool: Boolean = true,
    val rules: List<JevRule> = emptyList(),
) {
    val isUsable: Boolean get() = enabled && apiKey.isNotBlank()

    companion object {
        const val DEFAULT_BASE_URL = "https://api.typesafe.ai/v1"
    }
}

/**
 * Tool-call safety gate, run in the agent loop before a tool executes.
 *
 * - [autoApproveSafe]: a call that would normally stop for your approval runs straight away
 *   when Jev is confident it is low-risk AND what you asked for.
 * - [escalateRisky]: a call that would run without asking (Always-allow, "I AM STUPID" mode)
 *   stops for approval when Jev thinks it is risky or off-task.
 *
 * Both only look at tools that are approval-gated by default (sending, deleting, paying,
 * shell, screen control…) unless [allTools] is on. The hardline floor always runs first.
 */
@Serializable
data class ToolGuardConfig(
    val autoApproveSafe: Boolean = false,
    val escalateRisky: Boolean = true,
    /** Minimum P(safe & on-task) to skip the approval prompt. */
    val safeThreshold: Float = 0.90f,
    /** Minimum P(risky or off-task) to force the approval prompt. */
    val riskThreshold: Float = 0.70f,
    /** Also judge tools that never ask for approval (adds ~100 ms per such call). */
    val allTools: Boolean = false,
    /** Unattended runs (cron, sub-agents, Telegram) have no one to ask: deny instead of pausing. */
    val blockRiskyWhenUnattended: Boolean = false,
    /** Tool names (or `prefix*`) Jev never judges. */
    val excludedTools: List<String> = emptyList(),
)

/**
 * Per-turn model routing: Jev reads your latest message and sends easy turns to [fastModelId]
 * and hard ones to [strongModelId] (null = whatever model the chat/assistant already uses).
 */
@Serializable
data class ModelRouterConfig(
    val enabled: Boolean = false,
    val fastModelId: Uuid? = null,
    val strongModelId: Uuid? = null,
    /** Minimum P(simple) before a turn is sent to the fast model. */
    val threshold: Float = 0.75f,
    /** What "simple" means for you; shown to Jev verbatim. */
    val simpleCriteria: String = DEFAULT_SIMPLE,
    /** What "complex" means for you; shown to Jev verbatim. */
    val complexCriteria: String = DEFAULT_COMPLEX,
    /** Leave turns alone when you picked a model for the chat yourself. */
    val respectManualChatModel: Boolean = true,
) {
    companion object {
        const val DEFAULT_SIMPLE =
            "Chit-chat, a quick fact, a short rewrite or translation, a single simple tool action " +
                "(set a timer, toggle a setting, open an app), or a follow-up that needs little reasoning."
        const val DEFAULT_COMPLEX =
            "Multi-step tasks, planning, coding, debugging, math, analysis of long material, research, " +
                "driving the phone or browser through several screens, or anything ambiguous or high-stakes."
    }
}

/** Before each turn, Jev picks the enabled skill (if any) that fits the request and the agent is told to load it first. */
@Serializable
data class SkillHintConfig(
    val enabled: Boolean = false,
    val threshold: Float = 0.70f,
)

/** Jev-powered in-app browser helpers (`browser_find_element`, `browser_check`). */
@Serializable
data class BrowserAssistConfig(
    val enabled: Boolean = true,
    /** Minimum confidence before `browser_find_element` acts (click/type) on its own pick. */
    val actThreshold: Float = 0.60f,
    val maxCandidates: Int = 150,
)

/** Jev-powered phone-use helpers (`screen_find_node`, `screen_check`). */
@Serializable
data class PhoneAssistConfig(
    val enabled: Boolean = true,
    val actThreshold: Float = 0.60f,
    val maxCandidates: Int = 150,
)

/**
 * A user-written rule: one yes/no question Jev answers at a [trigger] point, and what to do
 * when the answer is "yes" with at least [threshold] probability. All rules for the same
 * trigger are answered in a single Jev call.
 */
@Serializable
data class JevRule(
    val id: String = Uuid.random().toString(),
    val name: String = "",
    val enabled: Boolean = true,
    val trigger: JevRuleTrigger = JevRuleTrigger.USER_MESSAGE,
    /** For [JevRuleTrigger.BEFORE_TOOL]: tool name or `prefix*`; blank = every tool. */
    val toolFilter: String = "",
    /** The yes/no question, e.g. "Is the user asking about their calendar?" */
    val question: String = "",
    val threshold: Float = 0.70f,
    val action: JevRuleAction = JevRuleAction.ADD_INSTRUCTION,
    /** ADD_INSTRUCTION: text given to the agent. BLOCK_TOOL: reason shown to the agent. */
    val text: String = "",
    /** USE_MODEL: the model to switch this turn to. */
    val modelId: Uuid? = null,
)

@Serializable
enum class JevRuleTrigger { USER_MESSAGE, BEFORE_TOOL }

@Serializable
enum class JevRuleAction {
    /** USER_MESSAGE: append [JevRule.text] to this turn's system prompt. */
    ADD_INSTRUCTION,

    /** USER_MESSAGE: run this turn on [JevRule.modelId]. */
    USE_MODEL,

    /** BEFORE_TOOL: stop and ask the user before the tool runs. */
    REQUIRE_APPROVAL,

    /** BEFORE_TOOL: refuse the tool call; [JevRule.text] is the reason given to the agent. */
    BLOCK_TOOL,
}

/** `name` matches `pattern` exactly, or by prefix when the pattern ends in `*`. Blank matches all. */
internal fun toolPatternMatches(pattern: String, name: String): Boolean {
    val p = pattern.trim()
    if (p.isEmpty()) return true
    return if (p.endsWith("*")) name.startsWith(p.dropLast(1)) else name == p
}
