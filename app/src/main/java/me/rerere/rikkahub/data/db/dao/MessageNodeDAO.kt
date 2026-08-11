package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Update
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery
import me.rerere.rikkahub.data.db.entity.MessageNodeEntity

@Dao
interface MessageNodeDAO {
    @Query("SELECT * FROM message_node WHERE conversation_id = :conversationId ORDER BY node_index ASC")
    suspend fun getNodesOfConversation(conversationId: String): List<MessageNodeEntity>

    @Query(
        "SELECT * FROM message_node WHERE conversation_id = :conversationId " +
            "ORDER BY node_index ASC LIMIT :limit OFFSET :offset"
    )
    suspend fun getNodesOfConversationPaged(
        conversationId: String,
        limit: Int,
        offset: Int
    ): List<MessageNodeEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(nodes: List<MessageNodeEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(node: MessageNodeEntity)

    @Update
    suspend fun update(node: MessageNodeEntity)

    @Query("DELETE FROM message_node WHERE conversation_id = :conversationId")
    suspend fun deleteByConversation(conversationId: String)

    @Query("DELETE FROM message_node WHERE id = :nodeId")
    suspend fun deleteById(nodeId: String)

    // 使用 @RawQuery 绕过 Room 编译期校验，以便使用 json_each() 虚拟表
    @RawQuery
    suspend fun getTokenStatsRaw(query: SupportSQLiteQuery): MessageTokenStats

    @RawQuery
    suspend fun getMessageCountPerDayRaw(query: SupportSQLiteQuery): List<MessageDayCount>

    // Phase 21 — token-attribution queries powering the stats breakdown ("what is eating
    // my tokens"). Both aggregate in SQLite over the messages JSON, same as above.
    @RawQuery
    suspend fun getTokensByConversationRaw(query: SupportSQLiteQuery): List<ConversationTokenRow>

    @RawQuery
    suspend fun getTokensByModelRaw(query: SupportSQLiteQuery): List<ModelTokenRow>
}

/** One conversation's cumulative token spend (prompt+completion), for the top-N list. */
data class ConversationTokenRow(
    val conversationId: String = "",
    val title: String = "",
    val totalTokens: Long = 0,
    val promptTokens: Long = 0,
    val completionTokens: Long = 0,
    val messageCount: Int = 0,
)

/** Token spend grouped by the model id that produced the messages (name resolved in the VM). */
data class ModelTokenRow(
    val model: String = "",
    val totalTokens: Long = 0,
    val promptTokens: Long = 0,
    val completionTokens: Long = 0,
    val cachedTokens: Long = 0,
    val messageCount: Int = 0,
)

data class MessageTokenStats(
    val totalMessages: Int = 0,
    val promptTokens: Long = 0,
    val completionTokens: Long = 0,
    val cachedTokens: Long = 0,
)

data class MessageDayCount(val day: String, val count: Int)

// SQLite json_each() 展开 messages JSON 数组，json_extract() 提取 Token 字段并聚合
private val TOKEN_STATS_SQL = SimpleSQLiteQuery(
    "SELECT COUNT(*) AS totalMessages, " +
        "COALESCE(SUM(CAST(json_extract(j.value, '$.usage.promptTokens') AS INTEGER)), 0) AS promptTokens, " +
        "COALESCE(SUM(CAST(json_extract(j.value, '$.usage.completionTokens') AS INTEGER)), 0) AS completionTokens, " +
        "COALESCE(SUM(CAST(json_extract(j.value, '$.usage.cachedTokens') AS INTEGER)), 0) AS cachedTokens " +
        "FROM message_node mn, json_each(mn.messages) j"
)

suspend fun MessageNodeDAO.getTokenStats(): MessageTokenStats = getTokenStatsRaw(TOKEN_STATS_SQL)

// 按用户消息的 createdAt 字段（LocalDateTime ISO 字符串前10位即日期）统计每日消息数
suspend fun MessageNodeDAO.getMessageCountPerDay(startDate: String): List<MessageDayCount> =
    getMessageCountPerDayRaw(
        SimpleSQLiteQuery(
            "SELECT substr(json_extract(j.value, '$.createdAt'), 1, 10) AS day, " +
                "COUNT(*) AS count " +
                "FROM message_node mn, json_each(mn.messages) j " +
                "WHERE json_extract(j.value, '$.role') = 'user' " +
                "AND json_extract(j.value, '$.createdAt') >= ? " +
                "GROUP BY day",
            arrayOf(startDate)
        )
    )


// Phase 21 — top conversations by token spend. prompt+completion summed per conversation;
// joins the conversation row for a human-readable title.
suspend fun MessageNodeDAO.getTokensByConversation(limit: Int = 15): List<ConversationTokenRow> =
    getTokensByConversationRaw(
        SimpleSQLiteQuery(
            "SELECT mn.conversation_id AS conversationId, " +
                "COALESCE(c.title, '') AS title, " +
                "COALESCE(SUM(CAST(json_extract(j.value, '$.usage.promptTokens') AS INTEGER)), 0) + " +
                "COALESCE(SUM(CAST(json_extract(j.value, '$.usage.completionTokens') AS INTEGER)), 0) AS totalTokens, " +
                "COALESCE(SUM(CAST(json_extract(j.value, '$.usage.promptTokens') AS INTEGER)), 0) AS promptTokens, " +
                "COALESCE(SUM(CAST(json_extract(j.value, '$.usage.completionTokens') AS INTEGER)), 0) AS completionTokens, " +
                "COUNT(*) AS messageCount " +
                "FROM message_node mn, json_each(mn.messages) j " +
                "LEFT JOIN conversation c ON c.id = mn.conversation_id " +
                "GROUP BY mn.conversation_id HAVING totalTokens > 0 " +
                "ORDER BY totalTokens DESC LIMIT ?",
            arrayOf<Any>(limit)
        )
    )

// Phase 21 — token spend grouped by model id/name recorded on each message.
suspend fun MessageNodeDAO.getTokensByModel(limit: Int = 12): List<ModelTokenRow> =
    getTokensByModelRaw(
        SimpleSQLiteQuery(
            "SELECT COALESCE(json_extract(j.value, '$.modelId'), 'unknown') AS model, " +
                "COALESCE(SUM(CAST(json_extract(j.value, '$.usage.promptTokens') AS INTEGER)), 0) + " +
                "COALESCE(SUM(CAST(json_extract(j.value, '$.usage.completionTokens') AS INTEGER)), 0) AS totalTokens, " +
                "COALESCE(SUM(CAST(json_extract(j.value, '$.usage.promptTokens') AS INTEGER)), 0) AS promptTokens, " +
                "COALESCE(SUM(CAST(json_extract(j.value, '$.usage.completionTokens') AS INTEGER)), 0) AS completionTokens, " +
                "COALESCE(SUM(CAST(json_extract(j.value, '$.usage.cachedTokens') AS INTEGER)), 0) AS cachedTokens, " +
                "COUNT(*) AS messageCount " +
                "FROM message_node mn, json_each(mn.messages) j " +
                "GROUP BY model HAVING totalTokens > 0 " +
                "ORDER BY totalTokens DESC LIMIT ?",
            arrayOf<Any>(limit)
        )
    )
