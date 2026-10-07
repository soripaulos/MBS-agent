package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.AssistantMemory
import me.rerere.rikkahub.utils.toLocalString
import java.time.LocalDate

fun buildMemoryTools(
    json: Json,
    onCreation: suspend (String) -> AssistantMemory,
    onUpdate: suspend (Int, String) -> AssistantMemory,
    onDelete: suspend (Int) -> Unit,
    // Archival lookup over every record (not just the ones injected this turn).
    onSearch: (suspend (String) -> List<AssistantMemory>)? = null,
    // Pin = always keep in context (core memory).
    onPin: (suspend (Int, Boolean) -> Unit)? = null,
): List<Tool> = listOf(
    Tool(
        name = "memory_tool",
        description = """
            The memory tool stores long-term information across conversations.
            Use `action` to control the operation: `create` (add), `edit` (update), `delete` (remove),
            `search` (find stored notes by `query` - only the most relevant notes are shown each turn),
            `pin` / `unpin` (`id`: keep a note in every conversation's context).
            Creating a record that restates an existing one updates that record instead.
            - No relevant record: `create` + `content`
            - Existing relevant record: `edit` + `id` + `content`
            - Outdated/irrelevant record: `delete` + `id`
            Use `kind` to classify a memory: `profile` (stable facts about who the user is —
            name, role, timezone), `preference` (how they like things done — reply style,
            language, formats), or `note` (everything else: plans, project state, learned facts).
            Memories render grouped by kind in the <memories> tag in later conversations.
            Do not store sensitive information (e.g., ethnicity, religion, sexual orientation, political views, sex life, criminal records).
            You may store: preferred name, preferences, plans, work-related notes, chat style preferences, first chat time, etc.
            Do not show memory content directly in the conversation unless the user explicitly asks.
            Today is ${LocalDate.now().toLocalString(true)}.
            Similar memories should be merged; prefer updating existing records.

            Examples:
            {"action":"create","kind":"preference","content":"User prefers brief replies and is more active on weekends."}
            {"action":"edit","id":12,"kind":"profile","content":"User’s preferred name updated to “A-Xing”, prefers Chinese replies."}
            {"action":"delete","id":7}
        """.trimIndent(),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("action", buildJsonObject {
                        put("type", "string")
                        put(
                            "enum",
                            buildJsonArray {
                                add("create")
                                add("edit")
                                add("delete")
                                if (onSearch != null) add("search")
                                if (onPin != null) { add("pin"); add("unpin") }
                            }
                        )
                        put("description", "Operation to perform")
                    })
                    put("id", buildJsonObject {
                        put("type", "integer")
                        put("description", "The id of the memory record (required for edit/delete)")
                    })
                    put("content", buildJsonObject {
                        put("type", "string")
                        put("description", "The content of the memory record (required for create/edit)")
                    })
                    if (onSearch != null) put("query", buildJsonObject {
                        put("type", "string")
                        put("description", "What to look for (search)")
                    })
                    put("kind", buildJsonObject {
                        put("type", "string")
                        put(
                            "enum",
                            buildJsonArray {
                                add("profile")
                                add("preference")
                                add("note")
                            }
                        )
                        put("description", "Classification: profile (who the user is), preference (how they like things), note (default)")
                    })
                },
                required = listOf("action")
            )
        },
        execute = {
            val params = it.jsonObject
            val action = params["action"]?.jsonPrimitive?.contentOrNull ?: error("action is required")
            // Phase 17 — kind is stored as a leading "[kind] " tag in the content string
            // (no schema change; buildMemoryPrompt groups by it, untagged = note).
            fun tagContent(raw: String): String {
                val kind = params["kind"]?.jsonPrimitive?.contentOrNull
                return if (kind == "profile" || kind == "preference") {
                    if (raw.startsWith("[$kind]")) raw else "[$kind] $raw"
                } else raw
            }
            val payload = when (action) {
                "create" -> {
                    val content = params["content"]?.jsonPrimitive?.contentOrNull ?: error("content is required")
                    json.encodeToJsonElement(AssistantMemory.serializer(), onCreation(tagContent(content)))
                }

                "edit" -> {
                    val id = params["id"]?.jsonPrimitive?.intOrNull ?: error("id is required")
                    val content = params["content"]?.jsonPrimitive?.contentOrNull ?: error("content is required")
                    json.encodeToJsonElement(AssistantMemory.serializer(), onUpdate(id, tagContent(content)))
                }

                "delete" -> {
                    val id = params["id"]?.jsonPrimitive?.intOrNull ?: error("id is required")
                    onDelete(id)
                    buildJsonObject {
                        put("success", true)
                        put("id", id)
                    }
                }

                "search" -> {
                    val search = onSearch ?: error("search is not available")
                    val query = params["query"]?.jsonPrimitive?.contentOrNull
                        ?: params["content"]?.jsonPrimitive?.contentOrNull
                        ?: error("query is required")
                    buildJsonObject {
                        put("results", buildJsonArray {
                            search(query).forEach { m ->
                                add(buildJsonObject { put("id", m.id); put("content", m.content) })
                            }
                        })
                    }
                }

                "pin", "unpin" -> {
                    val pin = onPin ?: error("pinning is not available")
                    val id = params["id"]?.jsonPrimitive?.intOrNull ?: error("id is required")
                    pin(id, action == "pin")
                    buildJsonObject { put("success", true); put("id", id); put("pinned", action == "pin") }
                }

                else -> error("unknown action: $action, must be one of [create, edit, delete, search, pin, unpin]")
            }
            listOf(UIMessagePart.Text(payload.toString()))
        }
    )
)
