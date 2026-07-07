package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.Assistant
import kotlin.uuid.Uuid

/**
 * Phase 18 — self-configuration tools. These let the model change the app's own settings
 * from within the chat, so the user can say "turn on plan mode", "enable the ssh tools",
 * "make a coding assistant" instead of hunting through Settings screens. The agent (esp.
 * in Smart mode) also uses them to auto-provision itself when it recognises a task pattern.
 *
 * All three are approval-gated (see ToolApprovalDefaults) because they change persistent
 * config; the approval card shows the diff before it's applied.
 *
 * Scope: writes target the CALLER assistant (the one running this chat), resolved from
 * [ToolInvocationContext.callerAssistantId]. Falls back to the current assistant when the
 * caller id is absent (legacy paths), never to "all assistants".
 */
fun createAgentConfigTools(
    settingsStore: SettingsStore,
    invocationContext: ToolInvocationContext,
): List<Tool> {
    // The set of local-tool option ids the model is allowed to name — the serialized
    // names from LocalToolOption, so "ssh", "termux", "session_search", etc.
    val toolOptionNames = LocalToolOptionCatalog.serialNames

    suspend fun resolveAssistantId(): Uuid? {
        val settings = settingsStore.settingsFlow.first()
        val id = invocationContext.callerAssistantId?.let { runCatching { Uuid.parse(it) }.getOrNull() }
        return id ?: settings.assistants.firstOrNull { it.id == settings.assistantId }?.id
    }

    fun err(code: String, detail: String): List<UIMessagePart> = listOf(
        UIMessagePart.Text(
            buildJsonObject {
                put("ok", false)
                put("error", code)
                put("detail", detail)
            }.toString()
        )
    )

    val getConfig = Tool(
        name = "get_agent_config",
        description = """
            Read the current assistant's configuration: enabled local tool categories,
            enabled skills, and behavior toggles (memory, plan mode, auto-compaction,
            smart mode, recent-chats reference, fast-path router, temperature). Call this
            before changing config so you know the current state and valid tool names.
        """.trimIndent().replace("\n", " "),
        parameters = { InputSchema.Obj(properties = buildJsonObject {}, required = emptyList()) },
        execute = {
            val settings = settingsStore.settingsFlow.first()
            val assistantId = resolveAssistantId()
                ?: return@Tool err("no_assistant", "could not resolve the current assistant")
            val a = settings.assistants.first { it.id == assistantId }
            val payload = buildJsonObject {
                put("assistant_name", a.name)
                put("enabled_tools", buildJsonArray {
                    a.localTools.forEach { add(JsonPrimitive(LocalToolOptionCatalog.serialNameOf(it))) }
                })
                put("enabled_skills", buildJsonArray { a.enabledSkills.forEach { add(JsonPrimitive(it)) } })
                put("enable_memory", a.enableMemory)
                put("use_global_memory", a.useGlobalMemory)
                put("plan_mode", a.planModeEnabled)
                put("auto_compact", a.autoCompactEnabled)
                put("smart_mode", a.smartModeEnabled)
                put("recent_chats_reference", a.enableRecentChatsReference)
                put("fast_path_router", a.fastPathRouterEnabled)
                put("temperature", JsonPrimitive(a.temperature))
                put("available_tool_names", buildJsonArray { toolOptionNames.forEach { add(JsonPrimitive(it)) } })
            }
            listOf(UIMessagePart.Text(payload.toString()))
        }
    )

    val setConfig = Tool(
        name = "set_agent_config",
        description = """
            Change the current assistant's configuration. Provide only the fields to change:
            - enable_tools / disable_tools: arrays of local tool category names (see
              available_tool_names from get_agent_config, e.g. "ssh", "termux",
              "session_search", "web_fetch", "files").
            - enable_skills / disable_skills: arrays of skill names.
            - enable_memory, use_global_memory, plan_mode, auto_compact, smart_mode,
              recent_chats_reference, fast_path_router: booleans.
            - temperature: number, or null to clear.
            Requires user approval. Returns the applied changes.
        """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("enable_tools", buildJsonObject { put("type", "array"); put("description", "tool category names to enable") })
                    put("disable_tools", buildJsonObject { put("type", "array"); put("description", "tool category names to disable") })
                    put("enable_skills", buildJsonObject { put("type", "array"); put("description", "skill names to enable") })
                    put("disable_skills", buildJsonObject { put("type", "array"); put("description", "skill names to disable") })
                    put("enable_memory", buildJsonObject { put("type", "boolean") })
                    put("use_global_memory", buildJsonObject { put("type", "boolean") })
                    put("plan_mode", buildJsonObject { put("type", "boolean") })
                    put("auto_compact", buildJsonObject { put("type", "boolean") })
                    put("smart_mode", buildJsonObject { put("type", "boolean") })
                    put("recent_chats_reference", buildJsonObject { put("type", "boolean") })
                    put("fast_path_router", buildJsonObject { put("type", "boolean") })
                    put("temperature", buildJsonObject { put("type", "number") })
                },
                required = emptyList()
            )
        },
        needsApproval = { true },
        execute = { input ->
            val params = input.jsonObject
            val assistantId = resolveAssistantId()
                ?: return@Tool err("no_assistant", "could not resolve the current assistant")

            fun stringArray(key: String): List<String>? =
                (params[key] as? kotlinx.serialization.json.JsonArray)
                    ?.mapNotNull { it.jsonPrimitive.contentOrNull }
            fun boolOf(key: String): Boolean? = params[key]?.jsonPrimitive?.booleanOrNull

            val enableTools = stringArray("enable_tools").orEmpty()
            val disableTools = stringArray("disable_tools").orEmpty()
            val unknownTools = (enableTools + disableTools).filter { it !in toolOptionNames }
            if (unknownTools.isNotEmpty()) {
                return@Tool err(
                    "unknown_tool",
                    "unknown tool categories: ${unknownTools.joinToString()}. Call get_agent_config for available_tool_names."
                )
            }

            val applied = mutableListOf<String>()
            settingsStore.update { current ->
                current.copy(
                    assistants = current.assistants.map { a ->
                        if (a.id != assistantId) return@map a
                        var next = a
                        if (enableTools.isNotEmpty() || disableTools.isNotEmpty()) {
                            val toAdd = enableTools.mapNotNull(LocalToolOptionCatalog::fromSerialName)
                            val toRemove = disableTools.mapNotNull(LocalToolOptionCatalog::fromSerialName).toSet()
                            next = next.copy(localTools = (next.localTools.toSet() + toAdd - toRemove).toList())
                            if (enableTools.isNotEmpty()) applied.add("enabled tools: ${enableTools.joinToString()}")
                            if (disableTools.isNotEmpty()) applied.add("disabled tools: ${disableTools.joinToString()}")
                        }
                        stringArray("enable_skills")?.let { skills ->
                            next = next.copy(enabledSkills = next.enabledSkills + skills)
                            applied.add("enabled skills: ${skills.joinToString()}")
                        }
                        stringArray("disable_skills")?.let { skills ->
                            next = next.copy(enabledSkills = next.enabledSkills - skills.toSet())
                            applied.add("disabled skills: ${skills.joinToString()}")
                        }
                        boolOf("enable_memory")?.let { next = next.copy(enableMemory = it); applied.add("enable_memory=$it") }
                        boolOf("use_global_memory")?.let { next = next.copy(useGlobalMemory = it); applied.add("use_global_memory=$it") }
                        boolOf("plan_mode")?.let { next = next.copy(planModeEnabled = it); applied.add("plan_mode=$it") }
                        boolOf("auto_compact")?.let { next = next.copy(autoCompactEnabled = it); applied.add("auto_compact=$it") }
                        boolOf("smart_mode")?.let { next = next.copy(smartModeEnabled = it); applied.add("smart_mode=$it") }
                        boolOf("recent_chats_reference")?.let { next = next.copy(enableRecentChatsReference = it); applied.add("recent_chats_reference=$it") }
                        boolOf("fast_path_router")?.let { next = next.copy(fastPathRouterEnabled = it); applied.add("fast_path_router=$it") }
                        if (params.containsKey("temperature")) {
                            val temp = params["temperature"]?.jsonPrimitive?.contentOrNull?.toFloatOrNull()
                            next = next.copy(temperature = temp)
                            applied.add("temperature=$temp")
                        }
                        next
                    }
                )
            }
            if (applied.isEmpty()) {
                return@Tool err("no_changes", "no recognized fields were provided")
            }
            listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("ok", true)
                        put("applied", buildJsonArray { applied.forEach { add(JsonPrimitive(it)) } })
                    }.toString()
                )
            )
        }
    )

    val createAssistant = Tool(
        name = "create_assistant",
        description = """
            Create a new assistant (persona) preconfigured for a kind of task — e.g. a
            "Journal" assistant with memory on, or a "Coder" assistant with termux/ssh/files
            tools. Provide name, system_prompt, and optionally tools (category names),
            enable_memory, plan_mode, smart_mode. Requires user approval. Returns the new
            assistant id. Does NOT switch to it — tell the user it's available in the
            assistant picker.
        """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("name", buildJsonObject { put("type", "string") })
                    put("system_prompt", buildJsonObject { put("type", "string") })
                    put("tools", buildJsonObject { put("type", "array"); put("description", "local tool category names") })
                    put("enable_memory", buildJsonObject { put("type", "boolean") })
                    put("plan_mode", buildJsonObject { put("type", "boolean") })
                    put("smart_mode", buildJsonObject { put("type", "boolean") })
                },
                required = listOf("name")
            )
        },
        needsApproval = { true },
        execute = { input ->
            val params = input.jsonObject
            val name = params["name"]?.jsonPrimitive?.contentOrNull?.trim()
            if (name.isNullOrBlank()) return@Tool err("missing_name", "name is required")
            val toolNames = (params["tools"] as? kotlinx.serialization.json.JsonArray)
                ?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
            val unknown = toolNames.filter { it !in toolOptionNames }
            if (unknown.isNotEmpty()) {
                return@Tool err("unknown_tool", "unknown tool categories: ${unknown.joinToString()}")
            }
            val newAssistant = Assistant(
                name = name,
                systemPrompt = params["system_prompt"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                enableMemory = params["enable_memory"]?.jsonPrimitive?.booleanOrNull ?: false,
                planModeEnabled = params["plan_mode"]?.jsonPrimitive?.booleanOrNull ?: false,
                smartModeEnabled = params["smart_mode"]?.jsonPrimitive?.booleanOrNull ?: false,
                localTools = buildList {
                    add(LocalToolOption.TimeInfo)
                    addAll(toolNames.mapNotNull(LocalToolOptionCatalog::fromSerialName))
                },
            )
            settingsStore.update { current ->
                current.copy(assistants = current.assistants + newAssistant)
            }
            listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("ok", true)
                        put("assistant_id", newAssistant.id.toString())
                        put("name", name)
                        put("detail", "Assistant created. It's available in the assistant picker.")
                    }.toString()
                )
            )
        }
    )

    return listOf(getConfig, setConfig, createAssistant)
}
