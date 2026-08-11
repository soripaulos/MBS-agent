package me.rerere.rikkahub.service

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.flow.first
import me.rerere.rikkahub.data.agentrun.AgentRunRepository
import me.rerere.rikkahub.data.agentrun.AgentRunKind
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.getAssistantById
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.ScheduledJobRepository
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.concurrent.TimeUnit

private const val TAG = "StalledRunWatchdog"

/**
 * Phase 21 — periodic sweep that finds work which STOPPED BEFORE COMPLETION and restarts
 * it, instead of leaving it stranded until the user notices.
 *
 * Two classes of stall are handled:
 *
 *  1. **Interactive chat turns.** A turn can die without an error card if the hosting
 *     process was killed (OOM / swipe-away / battery killer) mid-generation. The
 *     conversation is then left with a trailing USER message and no assistant reply, or an
 *     assistant message whose tool never produced output — and no live generation job.
 *     [ChatService.resumeIfIncomplete] re-enters generation for exactly that shape.
 *
 *  2. **Scheduled jobs.** A cron run whose ledger row is still in-flight long after the
 *     run started means the worker died before writing a terminal outcome. Re-firing the
 *     job is safe: CronJobWorker has its own replay-idempotency guard (a very recent run
 *     row short-circuits duplicates).
 *
 * The in-turn transient-failure retry (ChatService.autoResumeInterrupted) covers failures
 * while the process is ALIVE; this watchdog covers the case where nothing is alive to
 * retry. [AgentRunBootRecovery] still runs at startup to mark truly-lost rows; this sweep
 * runs on a schedule so a stall doesn't have to wait for an app restart.
 */
class StalledRunWatchdog(
    private val chatService: ChatService,
    private val conversationRepo: ConversationRepository,
    private val agentRunRepo: AgentRunRepository,
    private val scheduledJobRepo: ScheduledJobRepository,
    private val cronJobScheduler: CronJobScheduler,
    private val settingsStore: SettingsStore,
) {

    /** @return number of stalled items restarted. */
    suspend fun sweep(): Int {
        var restarted = 0
        restarted += runCatching { resumeStalledChats() }
            .onFailure { Log.w(TAG, "resumeStalledChats failed", it) }
            .getOrDefault(0)
        restarted += runCatching { requeueStalledCronRuns() }
            .onFailure { Log.w(TAG, "requeueStalledCronRuns failed", it) }
            .getOrDefault(0)
        if (restarted > 0) Log.i(TAG, "sweep: restarted $restarted stalled item(s)")
        return restarted
    }

    /**
     * Scan the most recently touched conversations of every assistant and re-enter
     * generation for any whose last turn is incomplete. Bounded to a handful per assistant
     * so the sweep stays cheap; a genuinely stalled chat is always recent.
     */
    private suspend fun resumeStalledChats(): Int {
        val settings = settingsStore.settingsFlow.first()
        var count = 0
        for (assistant in settings.assistants) {
            if (!assistant.autoResumeInterrupted) continue
            val recent = runCatching {
                conversationRepo.getRecentConversations(assistant.id, limit = 5)
            }.getOrDefault(emptyList())
            for (conversation in recent) {
                // Only consider conversations touched in the last 24h: older incomplete
                // turns are abandoned work, not stalls, and silently reviving them would
                // surprise the user (and spend tokens) long after the fact.
                val ageMs = System.currentTimeMillis() - conversation.updateAt.toEpochMilli()
                if (ageMs > 24 * 60 * 60 * 1000L) continue
                if (chatService.resumeIfIncomplete(conversation.id)) {
                    Log.i(TAG, "resumeStalledChats: resumed ${conversation.id}")
                    count++
                }
            }
        }
        return count
    }

    /**
     * Re-fire cron jobs whose ledger row never reached a terminal state. Uses a 20-minute
     * floor so a legitimately long run (the LLM path allows 15 min) is never pre-empted.
     */
    private suspend fun requeueStalledCronRuns(): Int {
        val cutoff = System.currentTimeMillis() - 20 * 60 * 1000L
        val stranded = runCatching { agentRunRepo.getStranded(cutoff) }.getOrDefault(emptyList())
        var count = 0
        for (run in stranded) {
            if (AgentRunKind.fromWire(run.kind) != AgentRunKind.Cron) continue
            // cron domain_id is "jobId:runAtMs"
            val jobId = run.domainId.substringBefore(':').takeIf { it.isNotBlank() } ?: continue
            val job = runCatching { scheduledJobRepo.getById(jobId) }.getOrNull() ?: continue
            if (!job.enabled) continue
            // Mark the dead row terminal first so the next sweep doesn't re-fire forever.
            runCatching {
                agentRunRepo.markTerminal(
                    id = run.id,
                    status = me.rerere.rikkahub.data.agentrun.AgentRunStatus.process_lost,
                    lastError = "watchdog: no terminal outcome; re-queued",
                )
            }
            runCatching { cronJobScheduler.triggerNow(jobId) }
                .onSuccess {
                    Log.i(TAG, "requeueStalledCronRuns: re-fired job $jobId")
                    count++
                }
                .onFailure { Log.w(TAG, "requeueStalledCronRuns: trigger failed for $jobId", it) }
        }
        return count
    }
}

/**
 * WorkManager host for [StalledRunWatchdog]. 15 minutes is WorkManager's minimum periodic
 * interval; a stall is therefore detected within ~15 min of happening.
 */
class StalledRunWatchdogWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params), KoinComponent {

    private val watchdog: StalledRunWatchdog by inject()

    override suspend fun doWork(): Result {
        return runCatching { watchdog.sweep() }
            .fold(
                onSuccess = { Result.success() },
                onFailure = {
                    Log.w(TAG, "watchdog sweep failed", it)
                    // Transient by nature (DB busy, no network) — let WorkManager retry.
                    Result.retry()
                },
            )
    }

    companion object {
        private const val WORK_NAME = "stalled_run_watchdog"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<StalledRunWatchdogWorker>(
                15, TimeUnit.MINUTES
            ).setConstraints(
                // Resuming a turn needs the network; without it the retry just burns a slot.
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            ).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }
}
