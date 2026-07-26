package me.rerere.rikkahub.data.ai

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantMemory
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.utils.JsonInstantPretty
import me.rerere.rikkahub.utils.toLocalDate

/**
 * Phase 17 — memories are grouped by their leading `[kind]` tag ([profile] / [preference] /
 * everything else = notes), Hermes USER.md-vs-MEMORY.md style: "who the user is" renders
 * before "what I've noted". The tag is written by memory_tool's optional `kind` argument;
 * untagged legacy records land in notes unchanged.
 */
private val MEMORY_KIND_TAG = Regex("""^\[(profile|preference)]\s*""")

internal fun buildMemoryPrompt(memories: List<AssistantMemory>) =
    buildString {
        appendLine()
        append("**Memories**")
        appendLine()
        append("These are memories stored via the memory_tool that you can reference in future conversations.")
        appendLine()
        val profile = mutableListOf<AssistantMemory>()
        val preferences = mutableListOf<AssistantMemory>()
        val notes = mutableListOf<AssistantMemory>()
        memories.forEach { memory ->
            when (MEMORY_KIND_TAG.find(memory.content)?.groupValues?.get(1)) {
                "profile" -> profile.add(memory)
                "preference" -> preferences.add(memory)
                else -> notes.add(memory)
            }
        }

        fun appendGroup(label: String, group: List<AssistantMemory>) {
            if (group.isEmpty()) return
            appendLine("$label:")
            val json = buildJsonArray {
                group.forEach { memory ->
                    add(buildJsonObject {
                        put("id", memory.id)
                        put("content", memory.content.replace(MEMORY_KIND_TAG, ""))
                    })
                }
            }
            append(JsonInstantPretty.encodeToString(json))
            appendLine()
        }
        appendGroup("User profile", profile)
        appendGroup("Preferences", preferences)
        appendGroup("Notes", notes)

        // Curation nudge — the "closed learning loop": once the store grows past a point
        // where stale/duplicate records start costing tokens every single turn, tell the
        // model to garden it. Threshold is deliberately generous; the nudge itself is one
        // sentence so it never costs more than the mess it prevents.
        if (memories.size >= 30) {
            appendLine(
                "Memory curation: there are ${memories.size} stored memories. When convenient, " +
                    "use memory_tool edit/delete to merge duplicates and remove outdated records."
            )
        }
    }

/**
 * Phase 18 — Smart Autopilot policy, prepended to the assistant prompt when
 * [Assistant.smartModeEnabled] is on. Makes the assistant proactive and context-aware:
 * classify → recall → act → file → learn, asking when genuinely uncertain. It leans on
 * tools the assistant may or may not have enabled (memory_tool, session_search,
 * skill_manage, set_agent_config); the policy tells it to offer to enable missing ones
 * rather than silently failing.
 */
internal val SMART_MODE_PROMPT = """

    **Smart mode is ON — be proactive and context-aware, not a passive responder.**
    On each user message, follow this loop:
    1. CLASSIFY what the user is doing before acting: a task to perform, a question, a
       journal/reflection, a preference, or new information to file. State nothing about
       this step unless asked — just let it guide you.
    2. RECALL before re-deriving. If memory, an enabled skill, or a past conversation may
       already hold the answer or procedure, retrieve it (memory is in <memories>; use
       session_search for past chats and use_skill for skills) instead of recomputing from
       scratch. Reuse prior solutions to save tokens.
    3. FILE information automatically. When the user shares something worth keeping, use
       memory_tool with the right kind (profile = who they are, preference = how they like
       things, note = plans/journal/project state) WITHOUT being told to remember. Capture
       journal entries and to-dos you notice. Don't ask "should I remember this?" for
       obvious cases; just do it and mention it briefly.
    4. LEARN. When you work out a non-obvious, reusable procedure, persist it with
       skill_manage so next time is instant. When an existing skill was wrong, patch it.
    5. SELF-CONFIGURE AND BUILD. You can create most things yourself — skills
       (skill_manage / skill_install_from_*), assistants (create_assistant), recurring
       automations (workflow_* tools, schedule_job), MCP servers (mcp_add/mcp_test), and
       settings changes (get_agent_config/set_agent_config). Approval cards are the user's
       checkpoint, so request the action rather than refusing or deflecting to Settings.
       If a capability is missing because its tool category is disabled, offer to enable
       it. If unsure how an app feature works, consult read_app_docs before answering.
    6. ASK when genuinely uncertain about something only the user can decide (ambiguous
       goal, destructive choice, missing key info) — use ask_user with concrete options.
       Do NOT ask about things you can reasonably infer or look up.
    Stay efficient: prefer one good recall/tool call over several speculative ones, and
    don't announce this policy or your internal steps to the user.
""".trimIndent()

/**
 * Phase 17 — Plan mode section, appended to the assistant prompt when
 * [Assistant.planModeEnabled] is on. Prompt-level contract only: the per-tool approval
 * layer underneath remains the hard enforcement floor.
 */
internal val PLAN_MODE_PROMPT = """

    **Plan mode is ON.**
    Before making any change or running any side-effecting tool (shell/SSH commands, file
    writes, messages, device control, deletions, installs), you must:
    1. Investigate first using read-only tools where needed.
    2. Present a short numbered plan of the actions you intend to take and their risks.
    3. Get explicit user approval — call ask_user with options (e.g. "Proceed" / "Modify")
       when it is available, otherwise end your message with the plan and wait.
    4. Only after approval, execute the plan. If the plan changes materially mid-way,
       stop and re-confirm.
    Pure read-only questions do not require a plan.
""".trimIndent()

internal suspend fun buildRecentChatsPrompt(
    assistant: Assistant,
    conversationRepo: ConversationRepository
): String {
    val recentConversations = conversationRepo.getRecentConversations(
        assistantId = assistant.id,
        limit = 10,
    )
    if (recentConversations.isNotEmpty()) {
        return buildString {
            appendLine()
            append("**Recent Chats**")
            appendLine()
            append("These are some of the user's recent conversations. You can use them to understand user preferences:")
            appendLine()
            val json = buildJsonArray {
                recentConversations.forEach { conversation ->
                    add(buildJsonObject {
                        put("title", conversation.title)
                        put("last_chat", conversation.updateAt.toLocalDate())
                    })
                }
            }
            append(JsonInstantPretty.encodeToString(json))
            appendLine()
        }
    }
    return ""
}
