package me.rerere.rikkahub.jev

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json

private const val TAG = "JevPreferences"

private val Context.jevDataStore by preferencesDataStore(name = "jev_prefs")

/** Lifetime usage counters, persisted so Settings → Jev can show spend across restarts. */
data class JevUsageTotals(
    val calls: Long = 0,
    val failures: Long = 0,
    val inputTokens: Long = 0,
    val totalLatencyMs: Long = 0,
) {
    val averageLatencyMs: Long get() = if (calls == 0L) 0 else totalLatencyMs / calls

    /** Jev bills input tokens only: $0.042 per million. */
    val estimatedCostUsd: Double get() = inputTokens * 0.042 / 1_000_000.0
}

/**
 * Own DataStore (not settings.json) so Jev can be configured, backed up and reset
 * independently. The full ("everything") backup still carries it, since it sweeps the
 * whole datastore directory.
 */
class JevPreferences(context: Context) {
    private val store = context.jevDataStore
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val kConfig = stringPreferencesKey("config")
    private val kCalls = longPreferencesKey("usage_calls")
    private val kFailures = longPreferencesKey("usage_failures")
    private val kTokens = longPreferencesKey("usage_input_tokens")
    private val kLatency = longPreferencesKey("usage_latency_ms")

    private val _config = MutableStateFlow(JevConfig())

    /** Latest config. Seeded synchronously on construction, then kept live. */
    val config: StateFlow<JevConfig> = _config.asStateFlow()

    val usageFlow: Flow<JevUsageTotals> = store.data.map {
        JevUsageTotals(
            calls = it[kCalls] ?: 0,
            failures = it[kFailures] ?: 0,
            inputTokens = it[kTokens] ?: 0,
            totalLatencyMs = it[kLatency] ?: 0,
        )
    }

    init {
        // One blocking read so the very first tool registration after launch already sees the
        // user's config (tool lists are built from non-suspending code). DataStore reads are a
        // single small file; this runs once per process.
        _config.value = runCatching { runBlocking { decode(store.data.first()[kConfig]) } }
            .getOrElse { JevConfig() }
        scope.launch {
            store.data.map { decode(it[kConfig]) }.collect { _config.value = it }
        }
    }

    private fun decode(raw: String?): JevConfig {
        if (raw.isNullOrBlank()) return JevConfig()
        return runCatching { json.decodeFromString<JevConfig>(raw) }
            .onFailure { Log.w(TAG, "decode failed, using defaults", it) }
            .getOrElse { JevConfig() }
    }

    suspend fun update(transform: (JevConfig) -> JevConfig) {
        store.edit { prefs ->
            val next = transform(decode(prefs[kConfig]))
            prefs[kConfig] = json.encodeToString(JevConfig.serializer(), next)
            _config.value = next
        }
    }

    fun updateAsync(transform: (JevConfig) -> JevConfig) {
        scope.launch { update(transform) }
    }

    fun recordCall(inputTokens: Int, latencyMs: Long, failed: Boolean) {
        scope.launch {
            runCatching {
                store.edit {
                    it[kCalls] = (it[kCalls] ?: 0) + 1
                    if (failed) it[kFailures] = (it[kFailures] ?: 0) + 1
                    it[kTokens] = (it[kTokens] ?: 0) + inputTokens
                    it[kLatency] = (it[kLatency] ?: 0) + latencyMs
                }
            }
        }
    }

    suspend fun resetUsage() {
        store.edit {
            it.remove(kCalls); it.remove(kFailures); it.remove(kTokens); it.remove(kLatency)
        }
    }
}
