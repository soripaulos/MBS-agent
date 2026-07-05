package me.rerere.rikkahub.data.ai.tools

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
import me.rerere.rikkahub.data.db.fts.MessageSearchSort
import me.rerere.rikkahub.data.repository.ConversationRepository
import kotlin.uuid.Uuid

/**
 * Phase 17 — cross-session recall tools (Hermes `session_search` parity).
 *
 * `session_search` runs the existing FTS5 index (message_fts, built and maintained by
 * [me.rerere.rikkahub.data.db.fts.MessageFtsManager]) as an LLM-callable tool so the
 * model can actively look things up in past conversations instead of relying on the
 * passive recent-chats injection. `session_get` then pulls the tail of a specific
 * conversation found that way.
 *
 * Both are pure reads over data the model could already see passively, so neither is in
 * [ToolApprovalDefaults.ALWAYS_ASK].
 */
fun createSessionSearchTools(
    conversationRepo: ConversationRepository,
): List<Tool> = listOf(
    Tool(
        name = "session_search",
        description = """
            Full-text search across ALL past conversations (every chat session stored on
            this device). Use it to recall earlier discussions, decisions, names, or facts
            the user mentioned in another session. Returns up to 15 matches grouped by
            conversation: { conversation_id, title, updated_at, snippets[] }. Follow up
            with session_get to read a conversation's recent messages.
        """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("query", buildJsonObject {
                        put("type", "string")
                        put("description", "Search keywords (matched against message text)")
                    })
                    put("sort", buildJsonObject {
                        put("type", "string")
                        put(
                            "enum",
                            buildJsonArray {
                                add("relevance")
                                add("newest_first")
                                add("oldest_first")
                            }
                        )
                        put("description", "Result ordering; defaults to relevance")
                    })
                },
                required = listOf("query")
            )
        },
        execute = { input ->
            val params = input.jsonObject
            val query = params["query"]?.jsonPrimitive?.contentOrNull?.trim()
            if (query.isNullOrBlank()) {
                return@Tool listOf(
                    UIMessagePart.Text(
                        buildJsonObject {
                            put("error", "missing_query")
                            put("detail", "session_search requires a non-empty 'query'.")
                        }.toString()
                    )
                )
            }
            val sort = when (params["sort"]?.jsonPrimitive?.contentOrNull) {
                "newest_first" -> MessageSearchSort.NEWEST_FIRST
                "oldest_first" -> MessageSearchSort.OLDEST_FIRST
                else -> MessageSearchSort.RELEVANCE
            }
            val hits = runCatching { conversationRepo.searchMessages(query, sort) }
                .getOrElse { e ->
                    return@Tool listOf(
                        UIMessagePart.Text(
                            buildJsonObject {
                                put("error", "search_failed")
                                put("detail", e.message ?: e.javaClass.simpleName)
                            }.toString()
                        )
                    )
                }
            // Group hits by conversation, keep first-seen order (matches the SQL sort),
            // cap at 15 conversations / 3 snippets each so a broad query can't flood the
            // context window.
            val grouped = LinkedHashMap<String, MutableList<String>>()
            val titles = HashMap<String, String>()
            val updatedAt = HashMap<String, String>()
            for (hit in hits) {
                val list = grouped.getOrPut(hit.conversationId) { mutableListOf() }
                if (list.size < 3) list.add(hit.snippet)
                titles[hit.conversationId] = hit.title
                updatedAt[hit.conversationId] = hit.updateAt.toString()
                if (grouped.size >= 15 && list.size >= 3) continue
            }
            val payload = buildJsonObject {
                put("query", query)
                put("match_count", hits.size)
                put(
                    "conversations",
                    buildJsonArray {
                        grouped.entries.take(15).forEach { (convId, snippets) ->
                            add(buildJsonObject {
                                put("conversation_id", convId)
                                put("title", JsonPrimitive(titles[convId] ?: ""))
                                put("updated_at", JsonPrimitive(updatedAt[convId] ?: ""))
                                put(
                                    "snippets",
                                    buildJsonArray {
                                        snippets.forEach { add(JsonPrimitive(it)) }
                                    }
                                )
                            })
                        }
                    }
                )
            }
            listOf(UIMessagePart.Text(payload.toString()))
        }
    ),
    Tool(
        name = "session_get",
        description = """
            Read the most recent messages of a past conversation by id (use
            session_search first to find the id). Returns up to max_messages (default 20,
            max 50) of the conversation tail as { role, text } entries, each truncated to
            600 characters.
        """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("conversation_id", buildJsonObject {
                        put("type", "string")
                        put("description", "Conversation id from session_search results")
                    })
                    put("max_messages", buildJsonObject {
                        put("type", "integer")
                        put("description", "How many messages from the tail to return (1..50, default 20)")
                    })
                },
                required = listOf("conversation_id")
            )
        },
        execute = { input ->
            val params = input.jsonObject
            val rawId = params["conversation_id"]?.jsonPrimitive?.contentOrNull
            val maxMessages = (params["max_messages"]?.jsonPrimitive?.intOrNull ?: 20)
                .coerceIn(1, 50)
            val convId = runCatching { Uuid.parse(rawId ?: "") }.getOrNull()
                ?: return@Tool listOf(
                    UIMessagePart.Text(
                        buildJsonObject {
                            put("error", "invalid_conversation_id")
                            put("detail", "conversation_id must be a UUID from session_search results.")
                        }.toString()
                    )
                )
            val conversation = conversationRepo.getConversationById(convId)
                ?: return@Tool listOf(
                    UIMessagePart.Text(
                        buildJsonObject {
                            put("error", "conversation_not_found")
                            put("detail", "No conversation with id $rawId.")
                        }.toString()
                    )
                )
            val tail = conversation.currentMessages.takeLast(maxMessages)
            val payload = buildJsonObject {
                put("conversation_id", JsonPrimitive(rawId))
                put("title", conversation.title)
                put("message_count", conversation.currentMessages.size)
                put(
                    "messages",
                    buildJsonArray {
                        tail.forEach { msg ->
                            val text = msg.parts
                                .filterIsInstance<UIMessagePart.Text>()
                                .joinToString("\n") { it.text }
                                .take(600)
                            if (text.isNotBlank()) {
                                add(buildJsonObject {
                                    put("role", msg.role.name.lowercase())
                                    put("text", text)
                                })
                            }
                        }
                    }
                )
            }
            listOf(UIMessagePart.Text(payload.toString()))
        }
    ),
)
