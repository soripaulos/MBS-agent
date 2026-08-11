package me.rerere.rikkahub.ui.pages.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.db.dao.ConversationDAO
import me.rerere.rikkahub.data.db.dao.MessageNodeDAO
import me.rerere.rikkahub.data.db.dao.getMessageCountPerDay
import me.rerere.rikkahub.data.db.dao.getTokenStats
import me.rerere.rikkahub.data.db.dao.getTokensByConversation
import me.rerere.rikkahub.data.db.dao.getTokensByModel
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.SettingsStore
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

data class AppStats(
    val isLoading: Boolean = true,
    val totalConversations: Int = 0,
    val totalMessages: Int = 0,
    val totalPromptTokens: Long = 0L,
    val totalCompletionTokens: Long = 0L,
    val totalCachedTokens: Long = 0L,
    val conversationsPerDay: Map<LocalDate, Int> = emptyMap(),
    val launchCount: Int = 0,
    // Phase 21 — token attribution: where the spend actually goes.
    val topConversations: List<TokenAttribution> = emptyList(),
    val byModel: List<TokenAttribution> = emptyList(),
)

/** One row of the "what consumed tokens" breakdown. */
data class TokenAttribution(
    val label: String,
    val totalTokens: Long,
    val promptTokens: Long,
    val completionTokens: Long,
    val messageCount: Int,
    val cachedTokens: Long = 0L,
)

class StatsVM(
    private val conversationDAO: ConversationDAO,
    private val messageNodeDAO: MessageNodeDAO,
    private val settingsStore: SettingsStore,
) : ViewModel() {

    private val _stats = MutableStateFlow(AppStats())
    val stats = _stats.asStateFlow()

    init {
        viewModelScope.launch { loadStats() }
    }

    private suspend fun loadStats() {
        delay(50)

        val today = LocalDate.now()

        // 热力图起始日期（52 周前的周日），格式 "yyyy-MM-dd" 直接与 JSON 中的 LocalDateTime 前缀比较
        val startDate = today
            .with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY))
            .minusWeeks(52)
            .toString()

        // 基于用户消息的 createdAt 统计每日活跃消息数，SQLite 侧 GROUP BY，返回 ≤371 行
        val conversationsPerDay = withContext(Dispatchers.IO) {
            messageNodeDAO
                .getMessageCountPerDay(startDate)
                .mapNotNull { entry ->
                    runCatching { LocalDate.parse(entry.day) to entry.count }.getOrNull()
                }
                .toMap()
        }

        val totalConversations = conversationDAO.countAll()

        // json_each() + json_extract() 在 SQLite 侧聚合，不再加载完整 JSON 到 Kotlin
        val tokenStats = messageNodeDAO.getTokenStats()

        val launchCount = settingsStore.settingsFlow.value.launchCount

        // Token attribution. Conversation titles come straight from the join; model rows
        // carry a model UUID that we resolve to a display name via Settings (falling back
        // to the raw id so an since-deleted model still shows up rather than vanishing).
        val settings = settingsStore.settingsFlow.value
        val topConversations = withContext(Dispatchers.IO) {
            runCatching { messageNodeDAO.getTokensByConversation(limit = 15) }.getOrDefault(emptyList())
        }.map { row ->
            TokenAttribution(
                label = row.title.ifBlank { row.conversationId.take(8) },
                totalTokens = row.totalTokens,
                promptTokens = row.promptTokens,
                completionTokens = row.completionTokens,
                messageCount = row.messageCount,
            )
        }
        val byModel = withContext(Dispatchers.IO) {
            runCatching { messageNodeDAO.getTokensByModel(limit = 12) }.getOrDefault(emptyList())
        }.map { row ->
            val name = runCatching {
                kotlin.uuid.Uuid.parse(row.model).let { id -> settings.findModelById(id)?.displayName }
            }.getOrNull()
            TokenAttribution(
                label = name ?: row.model.take(8),
                totalTokens = row.totalTokens,
                promptTokens = row.promptTokens,
                completionTokens = row.completionTokens,
                messageCount = row.messageCount,
                cachedTokens = row.cachedTokens,
            )
        }

        _stats.value = AppStats(
            isLoading = false,
            totalConversations = totalConversations,
            totalMessages = tokenStats.totalMessages,
            totalPromptTokens = tokenStats.promptTokens,
            totalCompletionTokens = tokenStats.completionTokens,
            totalCachedTokens = tokenStats.cachedTokens,
            conversationsPerDay = conversationsPerDay,
            launchCount = launchCount,
            topConversations = topConversations,
            byModel = byModel,
        )
    }
}
