package me.rerere.rikkahub.data.telegram.account

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/*
 * Agent tools over the user's own Telegram account (TDLib). Reading is free; anything that
 * sends on the user's behalf (tg_send_message, tg_send_file) is approval-gated.
 */

private fun ok(obj: JsonObject) = listOf(UIMessagePart.Text(obj.toString()))
private fun fail(message: String) = ok(buildJsonObject { put("error", message) })

private fun JsonElement.arg(key: String): String? =
    (this as? JsonObject)?.get(key)?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }

private fun JsonElement.intArg(key: String): Int? = (this as? JsonObject)?.get(key)?.jsonPrimitive?.intOrNull

private val dateFmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)

private suspend fun guarded(client: TelegramAccountClient, block: suspend () -> List<UIMessagePart>): List<UIMessagePart> {
    if (!client.isReady) {
        return fail("Telegram account is not logged in. Ask the user to sign in under Settings → Telegram account.")
    }
    return try {
        block()
    } catch (e: TelegramException) {
        fail("telegram: ${e.message}")
    }
}

/** Resolve "123456", "-100…", "@username", or a chat title / person name to a chat id. */
private suspend fun resolveChat(client: TelegramAccountClient, ref: String): Long {
    ref.trim().toLongOrNull()?.let { return it }
    val r = ref.trim()
    if (r.equals("me", true) || r.equals("saved", true) || r.equals("saved messages", true)) {
        val me = client.send(buildJsonObject { put("@type", "getMe") })
        val myId = me["id"]?.jsonPrimitive?.longOrNull ?: throw TelegramException("no self id")
        val chat = client.send(buildJsonObject { put("@type", "createPrivateChat"); put("user_id", myId); put("force", false) })
        return chat["id"]?.jsonPrimitive?.longOrNull ?: throw TelegramException("no chat id")
    }
    if (r.startsWith("@")) {
        val chat = client.send(buildJsonObject { put("@type", "searchPublicChat"); put("username", r.removePrefix("@")) })
        return chat["id"]?.jsonPrimitive?.longOrNull ?: throw TelegramException("no chat for $r")
    }
    // Local search over the user's chats (titles, contact names), then the server.
    for (type in listOf("searchChats", "searchChatsOnServer")) {
        val found = runCatching {
            client.send(buildJsonObject { put("@type", type); put("query", r); put("limit", 5) })
        }.getOrNull()
        val first = (found?.get("chat_ids") as? JsonArray)?.firstOrNull()?.jsonPrimitive?.longOrNull
        if (first != null) return first
    }
    throw TelegramException("no chat matches \"$r\"; use tg_list_chats to see chat ids")
}

private suspend fun chatTitle(client: TelegramAccountClient, chatId: Long): String =
    client.chatTitles[chatId] ?: runCatching {
        client.send(buildJsonObject { put("@type", "getChat"); put("chat_id", chatId) })["title"]
            ?.jsonPrimitive?.contentOrNull.orEmpty().also { client.chatTitles[chatId] = it }
    }.getOrDefault("chat $chatId")

private suspend fun senderName(client: TelegramAccountClient, sender: JsonObject?): String {
    sender ?: return "?"
    return when (sender.tdType()) {
        "messageSenderUser" -> {
            val id = sender["user_id"]?.jsonPrimitive?.longOrNull ?: return "?"
            client.userNames[id] ?: runCatching {
                client.send(buildJsonObject { put("@type", "getUser"); put("user_id", id) }).also { client.rememberUser(it) }
                client.userNames[id]
            }.getOrNull() ?: "user $id"
        }

        "messageSenderChat" -> chatTitle(client, sender["chat_id"]?.jsonPrimitive?.longOrNull ?: 0)
        else -> "?"
    }
}

/** Human-readable text of a message's content, with a tag for media. */
private fun contentText(content: JsonObject?): String {
    content ?: return ""
    fun formatted(key: String) = ((content[key] as? JsonObject)?.get("text") as? JsonPrimitive)?.contentOrNull.orEmpty()
    return when (val t = content.tdType()) {
        "messageText" -> formatted("text")
        "messagePhoto" -> "[photo] " + formatted("caption")
        "messageVideo" -> "[video] " + formatted("caption")
        "messageDocument" -> {
            val name = ((content["document"] as? JsonObject)?.get("file_name") as? JsonPrimitive)?.contentOrNull.orEmpty()
            "[file: $name] " + formatted("caption")
        }

        "messageVoiceNote" -> "[voice note] " + formatted("caption")
        "messageAudio" -> "[audio] " + formatted("caption")
        "messageSticker" -> "[sticker]"
        "messageAnimation" -> "[GIF] " + formatted("caption")
        "messageLocation" -> "[location]"
        "messageContact" -> "[contact]"
        "messagePoll" -> "[poll] " + (((content["poll"] as? JsonObject)?.get("question") as? JsonObject)?.get("text") as? JsonPrimitive)?.contentOrNull.orEmpty()
        else -> "[${t?.removePrefix("message") ?: "message"}]"
    }.trim()
}

private suspend fun messageLine(client: TelegramAccountClient, m: JsonObject, withChat: Boolean = false): JsonObject {
    val chatId = m["chat_id"]?.jsonPrimitive?.longOrNull ?: 0
    return buildJsonObject {
        put("id", m["id"]?.jsonPrimitive?.longOrNull ?: 0)
        if (withChat) {
            put("chat_id", chatId)
            put("chat", chatTitle(client, chatId))
        }
        put("from", senderName(client, m["sender_id"] as? JsonObject))
        if (m["is_outgoing"]?.jsonPrimitive?.contentOrNull == "true") put("outgoing", true)
        m["date"]?.jsonPrimitive?.longOrNull?.let { put("date", dateFmt.format(Date(it * 1000))) }
        put("text", contentText(m["content"] as? JsonObject).take(2_000))
    }
}

fun createTelegramAccountTools(client: TelegramAccountClient): List<Tool> = listOf(
    Tool(
        name = "tg_list_chats",
        description = "List the user's Telegram chats (their own account, not the bot) with unread counts and the last message. " +
            "Use chat_id (or a chat name / @username) with the other tg_ tools.",
        parameters = {
            InputSchema.Obj(properties = buildJsonObject {
                put("limit", buildJsonObject { put("type", "integer"); put("description", "Default 25, max 100") })
                put("unread_only", buildJsonObject { put("type", "boolean") })
            })
        },
        execute = { input ->
            guarded(client) {
                val limit = (input.intArg("limit") ?: 25).coerceIn(1, 100)
                val unreadOnly = input.arg("unread_only") == "true"
                runCatching {
                    client.send(buildJsonObject {
                        put("@type", "loadChats")
                        put("chat_list", buildJsonObject { put("@type", "chatListMain") })
                        put("limit", limit)
                    })
                } // 404 = everything already loaded
                val ids = (client.send(buildJsonObject {
                    put("@type", "getChats")
                    put("chat_list", buildJsonObject { put("@type", "chatListMain") })
                    put("limit", limit)
                })["chat_ids"] as? JsonArray).orEmpty().mapNotNull { it.jsonPrimitive.longOrNull }
                val chats = buildJsonArray {
                    ids.forEach { id ->
                        val chat = runCatching { client.send(buildJsonObject { put("@type", "getChat"); put("chat_id", id) }) }.getOrNull()
                            ?: return@forEach
                        val unread = chat["unread_count"]?.jsonPrimitive?.intOrNull ?: 0
                        if (unreadOnly && unread == 0) return@forEach
                        val title = chat["title"]?.jsonPrimitive?.contentOrNull.orEmpty().also { client.chatTitles[id] = it }
                        add(buildJsonObject {
                            put("chat_id", id)
                            put("title", title)
                            put("type", (chat["type"] as? JsonObject)?.tdType()?.removePrefix("chatType").orEmpty())
                            if (unread > 0) put("unread", unread)
                            (chat["last_message"] as? JsonObject)?.let { lm ->
                                put("last", contentText(lm["content"] as? JsonObject).take(120))
                            }
                        })
                    }
                }
                ok(buildJsonObject { put("chats", chats) })
            }
        },
    ),
    Tool(
        name = "tg_read_messages",
        description = "Read recent messages from one of the user's Telegram chats (newest first). chat: chat_id, @username, " +
            "a chat / contact name, or \"me\" for Saved Messages. Pass before_id to page further back.",
        parameters = {
            InputSchema.Obj(properties = buildJsonObject {
                put("chat", buildJsonObject { put("type", "string") })
                put("limit", buildJsonObject { put("type", "integer"); put("description", "Default 20, max 100") })
                put("before_id", buildJsonObject { put("type", "integer"); put("description", "Message id to read older messages before") })
            }, required = listOf("chat"))
        },
        execute = { input ->
            guarded(client) {
                val chatId = resolveChat(client, input.arg("chat") ?: return@guarded fail("chat is required"))
                val limit = (input.intArg("limit") ?: 20).coerceIn(1, 100)
                val from = (input as? JsonObject)?.get("before_id")?.jsonPrimitive?.longOrNull ?: 0L
                runCatching { client.send(buildJsonObject { put("@type", "openChat"); put("chat_id", chatId) }) }
                // getChatHistory may return fewer than asked on the first call (local cache); ask twice.
                val collected = mutableListOf<JsonObject>()
                var cursor = from
                repeat(3) {
                    if (collected.size >= limit) return@repeat
                    val batch = (client.send(buildJsonObject {
                        put("@type", "getChatHistory")
                        put("chat_id", chatId)
                        put("from_message_id", cursor)
                        put("offset", 0)
                        put("limit", limit - collected.size)
                        put("only_local", false)
                    })["messages"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
                    if (batch.isEmpty()) return@repeat
                    collected += batch
                    cursor = batch.last()["id"]?.jsonPrimitive?.longOrNull ?: return@repeat
                }
                ok(buildJsonObject {
                    put("chat_id", chatId)
                    put("chat", chatTitle(client, chatId))
                    put("messages", buildJsonArray { collected.forEach { add(messageLine(client, it)) } })
                })
            }
        },
    ),
    Tool(
        name = "tg_search_messages",
        description = "Search the user's Telegram messages by text, across all chats or within one chat.",
        parameters = {
            InputSchema.Obj(properties = buildJsonObject {
                put("query", buildJsonObject { put("type", "string") })
                put("chat", buildJsonObject { put("type", "string"); put("description", "Optional: limit to this chat") })
                put("limit", buildJsonObject { put("type", "integer"); put("description", "Default 20, max 50") })
            }, required = listOf("query"))
        },
        execute = { input ->
            guarded(client) {
                val query = input.arg("query") ?: return@guarded fail("query is required")
                val limit = (input.intArg("limit") ?: 20).coerceIn(1, 50)
                val chatRef = input.arg("chat")
                val found = if (chatRef != null) {
                    client.send(buildJsonObject {
                        put("@type", "searchChatMessages")
                        put("chat_id", resolveChat(client, chatRef))
                        put("query", query)
                        put("from_message_id", 0)
                        put("offset", 0)
                        put("limit", limit)
                    })
                } else {
                    client.send(buildJsonObject {
                        put("@type", "searchMessages")
                        put("query", query)
                        put("offset", "")
                        put("limit", limit)
                    })
                }
                val messages = (found["messages"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
                ok(buildJsonObject {
                    put("results", buildJsonArray { messages.forEach { add(messageLine(client, it, withChat = true)) } })
                })
            }
        },
    ),
    Tool(
        name = "tg_send_message",
        description = "Send a text message FROM THE USER'S OWN Telegram account to a chat (person, group, channel, or \"me\" " +
            "for Saved Messages). Optionally reply to a message id.",
        parameters = {
            InputSchema.Obj(properties = buildJsonObject {
                put("chat", buildJsonObject { put("type", "string") })
                put("text", buildJsonObject { put("type", "string") })
                put("reply_to", buildJsonObject { put("type", "integer"); put("description", "Message id to reply to") })
            }, required = listOf("chat", "text"))
        },
        needsApproval = { true },
        execute = { input ->
            guarded(client) {
                val chatId = resolveChat(client, input.arg("chat") ?: return@guarded fail("chat is required"))
                val text = input.arg("text") ?: return@guarded fail("text is required")
                val replyTo = (input as? JsonObject)?.get("reply_to")?.jsonPrimitive?.longOrNull
                val sent = client.send(buildJsonObject {
                    put("@type", "sendMessage")
                    put("chat_id", chatId)
                    if (replyTo != null) put("reply_to", buildJsonObject {
                        put("@type", "inputMessageReplyToMessage")
                        put("message_id", replyTo)
                    })
                    put("input_message_content", buildJsonObject {
                        put("@type", "inputMessageText")
                        put("text", buildJsonObject { put("@type", "formattedText"); put("text", text) })
                    })
                })
                ok(buildJsonObject {
                    put("sent", true)
                    put("chat", chatTitle(client, chatId))
                    put("message_id", sent["id"]?.jsonPrimitive?.longOrNull ?: 0)
                })
            }
        },
    ),
    Tool(
        name = "tg_send_file",
        description = "Send a file from the device FROM THE USER'S OWN Telegram account (as a document, or as a photo " +
            "when as_photo is true), with an optional caption.",
        parameters = {
            InputSchema.Obj(properties = buildJsonObject {
                put("chat", buildJsonObject { put("type", "string") })
                put("path", buildJsonObject { put("type", "string"); put("description", "Absolute file path on the device") })
                put("caption", buildJsonObject { put("type", "string") })
                put("as_photo", buildJsonObject { put("type", "boolean") })
            }, required = listOf("chat", "path"))
        },
        needsApproval = { true },
        execute = { input ->
            guarded(client) {
                val chatId = resolveChat(client, input.arg("chat") ?: return@guarded fail("chat is required"))
                val path = input.arg("path") ?: return@guarded fail("path is required")
                if (!File(path).isFile) return@guarded fail("file not found: $path")
                val asPhoto = input.arg("as_photo") == "true"
                val caption = input.arg("caption")
                val sent = client.send(buildJsonObject {
                    put("@type", "sendMessage")
                    put("chat_id", chatId)
                    put("input_message_content", buildJsonObject {
                        put("@type", if (asPhoto) "inputMessagePhoto" else "inputMessageDocument")
                        put(if (asPhoto) "photo" else "document", buildJsonObject {
                            put("@type", "inputFileLocal")
                            put("path", path)
                        })
                        if (caption != null) put("caption", buildJsonObject { put("@type", "formattedText"); put("text", caption) })
                    })
                }, timeoutMs = 120_000)
                ok(buildJsonObject { put("sent", true); put("message_id", sent["id"]?.jsonPrimitive?.longOrNull ?: 0) })
            }
        },
    ),
    Tool(
        name = "tg_mark_read",
        description = "Mark a Telegram chat as read on the user's account.",
        parameters = {
            InputSchema.Obj(properties = buildJsonObject { put("chat", buildJsonObject { put("type", "string") }) }, required = listOf("chat"))
        },
        execute = { input ->
            guarded(client) {
                val chatId = resolveChat(client, input.arg("chat") ?: return@guarded fail("chat is required"))
                val chat = client.send(buildJsonObject { put("@type", "getChat"); put("chat_id", chatId) })
                val lastId = (chat["last_message"] as? JsonObject)?.get("id")?.jsonPrimitive?.longOrNull
                if (lastId != null) {
                    client.send(buildJsonObject {
                        put("@type", "viewMessages")
                        put("chat_id", chatId)
                        put("message_ids", buildJsonArray { add(JsonPrimitive(lastId)) })
                        put("force_read", true)
                    })
                }
                ok(buildJsonObject { put("marked_read", true) })
            }
        },
    ),
    Tool(
        name = "tg_account_info",
        description = "Which Telegram account is connected (name, @username, phone).",
        parameters = { InputSchema.Obj(properties = buildJsonObject { }) },
        execute = {
            val s = client.state.value
            ok(
                if (s is TgAuthState.Ready) buildJsonObject {
                    put("connected", true)
                    put("name", s.name)
                    s.username?.let { put("username", it) }
                    s.phone?.let { put("phone", it) }
                } else buildJsonObject {
                    put("connected", false)
                    put("state", s::class.simpleName ?: "unknown")
                }
            )
        },
    ),
)
