package me.rerere.rikkahub.data.telegram.account

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.up9cloud.td.JsonClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

private const val TAG = "TelegramAccount"

private val Context.telegramAccountStore by preferencesDataStore(name = "telegram_account")

/** Where the Telegram (user account, not bot) login stands. */
sealed interface TgAuthState {
    /** libtdjson.so isn't in this build (or failed to load on this device). */
    data class Unavailable(val reason: String) : TgAuthState

    /** API id / hash not entered yet. */
    data object NotConfigured : TgAuthState

    /** Configured, client not started. */
    data object Stopped : TgAuthState

    data object Starting : TgAuthState
    data object WaitPhone : TgAuthState
    data class WaitCode(val sentTo: String, val codeType: String) : TgAuthState
    data class WaitPassword(val hint: String) : TgAuthState
    data object WaitEmail : TgAuthState
    data class WaitEmailCode(val pattern: String) : TgAuthState
    data object WaitRegistration : TgAuthState
    data class Ready(val name: String, val username: String?, val phone: String?) : TgAuthState
    data object LoggingOut : TgAuthState
    data class Error(val message: String) : TgAuthState
}

class TelegramException(message: String, val code: Int = 0) : Exception(message)

internal fun JsonObject.tdType(): String? = (this["@type"] as? JsonPrimitive)?.contentOrNull

/**
 * Native Telegram **user-account** client (MTProto via TDLib), so the agent can read and send
 * as the user — chats, groups, channels, Saved Messages — with no external MCP server or
 * Termux/Python setup. Telegram requires an API id/hash from my.telegram.org for any
 * third-party client; the user enters them once.
 *
 * One TDLib client per process. A single daemon thread pumps `td_receive`, routes responses
 * to their callers by `@extra`, and turns authorization updates into [state]. The session
 * (including the login) lives in `filesDir/tdlib`, so a login survives restarts.
 */
class TelegramAccountClient(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true }
    private val store = context.telegramAccountStore
    private val kApiId = stringPreferencesKey("api_id")
    private val kApiHash = stringPreferencesKey("api_hash")

    private val _state = MutableStateFlow<TgAuthState>(
        if (!JsonClient.AVAILABLE) TgAuthState.Unavailable(JsonClient.LOAD_ERROR ?: "TDLib is not bundled in this build")
        else TgAuthState.Stopped
    )
    val state: StateFlow<TgAuthState> = _state.asStateFlow()

    val credentialsFlow: Flow<Pair<String, String>> =
        store.data.map { (it[kApiId].orEmpty()) to (it[kApiHash].orEmpty()) }

    @Volatile
    private var clientId: Int = -1
    private val pending = ConcurrentHashMap<String, CompletableDeferred<JsonObject>>()
    private val nextExtra = AtomicLong(1)
    private var receiver: Thread? = null

    /** user_id -> display name, filled from updateUser / getUser. */
    internal val userNames = ConcurrentHashMap<Long, String>()

    /** chat_id -> title, filled from updateNewChat / getChat. */
    internal val chatTitles = ConcurrentHashMap<Long, String>()

    val isReady: Boolean get() = _state.value is TgAuthState.Ready

    suspend fun saveCredentials(apiId: String, apiHash: String) {
        store.edit {
            it[kApiId] = apiId.trim()
            it[kApiHash] = apiHash.trim()
        }
        if (_state.value is TgAuthState.NotConfigured) _state.value = TgAuthState.Stopped
    }

    /** Start (or resume) the client; with a saved session this goes straight to Ready. */
    @Synchronized
    fun start() {
        if (!JsonClient.AVAILABLE) return
        if (clientId >= 0) return
        _state.value = TgAuthState.Starting
        runCatching {
            JsonClient.td_execute("""{"@type":"setLogVerbosityLevel","new_verbosity_level":1}""")
        }
        clientId = JsonClient.td_create_client_id()
        if (receiver == null) {
            receiver = Thread({ receiveLoop() }, "tdlib-receive").apply {
                isDaemon = true
                start()
            }
        }
        // Any request makes TDLib emit the current authorization state.
        sendRaw(buildJsonObject { put("@type", "getOption"); put("name", "version") })
    }

    /** App start: resume a previously logged-in session without user action. */
    suspend fun startIfConfigured() {
        if (!JsonClient.AVAILABLE) return
        val (id, hash) = credentialsFlow.first()
        if (id.isBlank() || hash.isBlank()) {
            _state.value = TgAuthState.NotConfigured
            return
        }
        if (File(context.filesDir, "tdlib/db").exists()) start()
    }

    private fun receiveLoop() {
        while (true) {
            val raw = try {
                JsonClient.td_receive(1.0)
            } catch (t: Throwable) {
                Log.e(TAG, "td_receive failed", t)
                Thread.sleep(1_000)
                null
            } ?: continue
            val obj = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: continue
            val extra = (obj["@extra"] as? JsonPrimitive)?.contentOrNull
            if (extra != null) {
                pending.remove(extra)?.complete(obj)
                continue
            }
            runCatching { onUpdate(obj) }.onFailure { Log.w(TAG, "update handling failed", it) }
        }
    }

    private fun sendRaw(request: JsonObject) {
        val id = clientId
        if (id < 0) return
        JsonClient.td_send(id, request.toString())
    }

    /** Send a TDLib request and await its response; throws [TelegramException] on `error`. */
    suspend fun send(request: JsonObject, timeoutMs: Long = 30_000): JsonObject {
        if (!JsonClient.AVAILABLE) throw TelegramException("TDLib is not bundled in this build")
        if (clientId < 0) throw TelegramException("Telegram account is not started")
        val extra = "r${nextExtra.getAndIncrement()}"
        val deferred = CompletableDeferred<JsonObject>()
        pending[extra] = deferred
        sendRaw(JsonObject(request + ("@extra" to JsonPrimitive(extra))))
        val response = try {
            withTimeout(timeoutMs) { deferred.await() }
        } finally {
            pending.remove(extra)
        }
        if (response.tdType() == "error") {
            throw TelegramException(
                response["message"]?.jsonPrimitive?.contentOrNull ?: "Telegram error",
                response["code"]?.jsonPrimitive?.intOrNull ?: 0,
            )
        }
        return response
    }

    private fun onUpdate(update: JsonObject) {
        when (update.tdType()) {
            "updateAuthorizationState" -> (update["authorization_state"] as? JsonObject)?.let { onAuthState(it) }
            "updateUser" -> (update["user"] as? JsonObject)?.let { rememberUser(it) }
            "updateNewChat" -> (update["chat"] as? JsonObject)?.let { c ->
                val id = c["id"]?.jsonPrimitive?.longOrNull
                if (id != null) chatTitles[id] = c["title"]?.jsonPrimitive?.contentOrNull.orEmpty()
            }

            "updateChatTitle" -> {
                val id = update["chat_id"]?.jsonPrimitive?.longOrNull
                if (id != null) chatTitles[id] = update["title"]?.jsonPrimitive?.contentOrNull.orEmpty()
            }
        }
    }

    internal fun rememberUser(u: JsonObject) {
        val id = u["id"]?.jsonPrimitive?.longOrNull ?: return
        val name = listOfNotNull(
            u["first_name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
            u["last_name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
        ).joinToString(" ")
        userNames[id] = name.ifBlank { "user $id" }
    }

    private fun onAuthState(s: JsonObject) {
        when (s.tdType()) {
            "authorizationStateWaitTdlibParameters" -> sendTdlibParameters()
            "authorizationStateWaitPhoneNumber" -> _state.value = TgAuthState.WaitPhone
            "authorizationStateWaitCode" -> {
                val info = s["code_info"] as? JsonObject
                _state.value = TgAuthState.WaitCode(
                    sentTo = info?.get("phone_number")?.jsonPrimitive?.contentOrNull.orEmpty(),
                    codeType = (info?.get("type") as? JsonObject)?.tdType()
                        ?.removePrefix("authenticationCodeType").orEmpty(),
                )
            }

            "authorizationStateWaitPassword" -> _state.value =
                TgAuthState.WaitPassword(s["password_hint"]?.jsonPrimitive?.contentOrNull.orEmpty())

            "authorizationStateWaitEmailAddress" -> _state.value = TgAuthState.WaitEmail
            "authorizationStateWaitEmailCode" -> _state.value = TgAuthState.WaitEmailCode(
                (s["code_info"] as? JsonObject)?.get("email_address_pattern")?.jsonPrimitive?.contentOrNull.orEmpty()
            )

            "authorizationStateWaitRegistration" -> _state.value = TgAuthState.WaitRegistration
            "authorizationStateReady" -> onReady()
            "authorizationStateLoggingOut" -> _state.value = TgAuthState.LoggingOut
            "authorizationStateClosed" -> {
                synchronized(this) { clientId = -1 }
                _state.value = TgAuthState.Stopped
            }
        }
    }

    private fun sendTdlibParameters() {
        val (apiId, apiHash) = kotlinx.coroutines.runBlocking { credentialsFlow.first() }
        val id = apiId.toIntOrNull()
        if (id == null || apiHash.isBlank()) {
            _state.value = TgAuthState.NotConfigured
            return
        }
        val dir = File(context.filesDir, "tdlib").apply { mkdirs() }
        sendRaw(buildJsonObject {
            put("@type", "setTdlibParameters")
            put("use_test_dc", false)
            put("database_directory", File(dir, "db").absolutePath)
            put("files_directory", File(dir, "files").absolutePath)
            put("database_encryption_key", "")
            put("use_file_database", true)
            put("use_chat_info_database", true)
            put("use_message_database", true)
            put("use_secret_chats", false)
            put("api_id", id)
            put("api_hash", apiHash)
            put("system_language_code", java.util.Locale.getDefault().language.ifBlank { "en" })
            put("device_model", "${Build.MANUFACTURER} ${Build.MODEL}")
            put("system_version", "Android ${Build.VERSION.RELEASE}")
            put("application_version", "Omnitrix agent")
        })
    }

    private fun onReady() {
        _state.value = TgAuthState.Ready("", null, null)
        // Fetch our own profile off the receive thread (send() awaits that thread).
        Thread {
            runCatching {
                kotlinx.coroutines.runBlocking {
                    val me = send(buildJsonObject { put("@type", "getMe") })
                    rememberUser(me)
                    val myId = me["id"]?.jsonPrimitive?.longOrNull ?: 0L
                    val username = ((me["usernames"] as? JsonObject)?.get("active_usernames") as? JsonArray)
                        ?.firstOrNull()?.jsonPrimitive?.contentOrNull
                    _state.value = TgAuthState.Ready(
                        name = userNames[myId].orEmpty(),
                        username = username,
                        phone = me["phone_number"]?.jsonPrimitive?.contentOrNull,
                    )
                }
            }.onFailure { Log.w(TAG, "getMe failed", it) }
        }.start()
    }

    // ---- login steps (driven from Settings → Telegram account) ----

    suspend fun submitPhone(phone: String) = authStep(buildJsonObject {
        put("@type", "setAuthenticationPhoneNumber")
        put("phone_number", phone.trim())
    })

    suspend fun submitCode(code: String) = authStep(buildJsonObject {
        put("@type", "checkAuthenticationCode")
        put("code", code.trim())
    })

    suspend fun submitPassword(password: String) = authStep(buildJsonObject {
        put("@type", "checkAuthenticationPassword")
        put("password", password)
    })

    suspend fun submitEmail(email: String) = authStep(buildJsonObject {
        put("@type", "setAuthenticationEmailAddress")
        put("email_address", email.trim())
    })

    suspend fun submitEmailCode(code: String) = authStep(buildJsonObject {
        put("@type", "checkAuthenticationEmailCode")
        put("code", buildJsonObject {
            put("@type", "emailAddressAuthenticationCode")
            put("code", code.trim())
        })
    })

    suspend fun submitRegistration(firstName: String, lastName: String) = authStep(buildJsonObject {
        put("@type", "registerUser")
        put("first_name", firstName.trim())
        put("last_name", lastName.trim())
    })

    private suspend fun authStep(request: JsonObject): Result<Unit> = try {
        send(request)
        Result.success(Unit)
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

    suspend fun logOut(): Result<Unit> = authStep(buildJsonObject { put("@type", "logOut") })
}
