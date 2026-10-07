package me.rerere.rikkahub.data.codex

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.datastore.SettingsStore
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

private const val TAG = "CodexModelSync"
private const val PREFS = "codex_model_sync"
private const val KEY_AUTO = "auto_sync_enabled"
private const val KEY_LAST = "last_sync_ms"

/** What a sync changed, for the toast / Settings line. */
data class CodexSyncReport(
    val total: Int,
    val added: List<String>,
    val retired: List<String>,
)

/**
 * Keeps the ChatGPT (Codex) provider's model list in step with what the account's Codex
 * catalog offers, so new models (GPT-6 Sol/Luna, GPT-6.1 Sol, Astra…) appear without the
 * user having to tap "Sync" or sign in again.
 *
 * - New catalog models are appended (user ordering and per-model edits are kept).
 * - Models the catalog stopped offering are NOT deleted, since assistants may point at them,
 *   but their name gets a " (retired)" suffix so it is obvious why they fail. The suffix is
 *   removed again if the model comes back.
 *
 * Runs on app start (at most every [MIN_INTERVAL_MS]) and every 12 h from WorkManager.
 */
object CodexModelSync {
    private const val MIN_INTERVAL_MS = 6 * 60 * 60 * 1000L
    const val RETIRED_SUFFIX = " (retired)"
    private const val WORK_NAME = "codex-model-sync"

    fun isAutoSyncEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_AUTO, true)

    fun setAutoSyncEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_AUTO, enabled).apply()
        if (enabled) schedule(context) else WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }

    fun schedule(context: Context) {
        if (!isAutoSyncEnabled(context)) return
        val request = PeriodicWorkRequestBuilder<CodexModelSyncWorker>(12, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    /** Sync if auto-sync is on, the provider is signed in, and the last sync is old enough. */
    suspend fun syncIfDue(
        context: Context,
        settingsStore: SettingsStore,
        providerManager: ProviderManager,
        repository: CodexAccountRepository,
    ) {
        if (!isAutoSyncEnabled(context)) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (System.currentTimeMillis() - prefs.getLong(KEY_LAST, 0) < MIN_INTERVAL_MS) return
        if (repository.accounts.value.none { it.enabled }) return
        runCatching { sync(settingsStore, providerManager) }
            .onSuccess { report ->
                prefs.edit().putLong(KEY_LAST, System.currentTimeMillis()).apply()
                if (report != null && (report.added.isNotEmpty() || report.retired.isNotEmpty())) {
                    Log.i(TAG, "auto-sync: +${report.added} retired=${report.retired}")
                }
            }
            .onFailure { Log.w(TAG, "auto-sync failed", it) }
    }

    /** Fetch the catalog and merge it into every Codex provider. Null when there is none. */
    suspend fun sync(settingsStore: SettingsStore, providerManager: ProviderManager): CodexSyncReport? {
        // Never merge into the placeholder settings that exist before DataStore has loaded:
        // writing on top of them would overwrite the user's real settings.
        val loaded = settingsStore.settingsFlow.first { !it.init }
        val codex = loaded.providers.filterIsInstance<ProviderSetting.Codex>().firstOrNull()
            ?: return null
        val catalog = providerManager.getProviderByType(codex).listModels(codex)
        if (catalog.isEmpty()) return null // never "retire" everything on an empty/failed catalog
        var report: CodexSyncReport? = null
        settingsStore.update { settings ->
            settings.copy(
                providers = settings.providers.map { provider ->
                    if (provider !is ProviderSetting.Codex) return@map provider
                    val (models, r) = merge(provider.models, catalog)
                    report = r
                    provider.copy(models = models)
                }
            )
        }
        return report
    }

    /** Pure merge, shared with the provider page's manual "Sync" button. */
    fun merge(existing: List<Model>, catalog: List<Model>): Pair<List<Model>, CodexSyncReport> {
        val byId = catalog.associateBy(Model::modelId)
        val retired = mutableListOf<String>()
        val updated = existing.map { model ->
            val fresh = byId[model.modelId]
            val baseName = model.displayName.removeSuffix(RETIRED_SUFFIX)
            if (fresh != null) {
                model.copy(
                    displayName = baseName,
                    inputModalities = fresh.inputModalities,
                    outputModalities = fresh.outputModalities,
                    abilities = fresh.abilities,
                    contextLength = fresh.contextLength ?: model.contextLength,
                )
            } else {
                retired += model.modelId
                model.copy(displayName = baseName.ifBlank { model.modelId } + RETIRED_SUFFIX)
            }
        }
        val known = existing.mapTo(mutableSetOf(), Model::modelId)
        val added = catalog.filterNot { it.modelId in known }
        return (updated + added) to CodexSyncReport(
            total = catalog.size,
            added = added.map { it.modelId },
            retired = retired,
        )
    }
}

class CodexModelSyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params), KoinComponent {
    private val settingsStore: SettingsStore by inject()
    private val providerManager: ProviderManager by inject()
    private val repository: CodexAccountRepository by inject()

    override suspend fun doWork(): Result {
        CodexModelSync.syncIfDue(applicationContext, settingsStore, providerManager, repository)
        return Result.success()
    }
}
