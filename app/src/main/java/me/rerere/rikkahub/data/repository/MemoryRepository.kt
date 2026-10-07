package me.rerere.rikkahub.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import me.rerere.rikkahub.data.db.dao.MemoryDAO
import me.rerere.rikkahub.data.db.entity.MemoryEntity
import me.rerere.rikkahub.data.model.AssistantMemory

class MemoryRepository(
    private val memoryDAO: MemoryDAO,
    // Timestamps / usage / pins sidecar; optional so tests can build a bare repository.
    val metaStore: MemoryMetaStore? = null,
) {
    companion object {
        const val GLOBAL_MEMORY_ID = "__global__"
        private const val DUPLICATE_SIMILARITY = 0.8
    }

    fun getMemoriesOfAssistantFlow(assistantId: String): Flow<List<AssistantMemory>> =
        memoryDAO.getMemoriesOfAssistantFlow(assistantId)
            .map { entities ->
                entities.map { AssistantMemory(it.id, it.content) }
            }

    suspend fun getMemoriesOfAssistant(assistantId: String): List<AssistantMemory> {
        return memoryDAO.getMemoriesOfAssistant(assistantId)
            .map { AssistantMemory(it.id, it.content) }
    }

    fun getGlobalMemoriesFlow(): Flow<List<AssistantMemory>> =
        memoryDAO.getMemoriesOfAssistantFlow(GLOBAL_MEMORY_ID)
            .map { entities ->
                entities.map { AssistantMemory(it.id, it.content) }
            }

    suspend fun getGlobalMemories(): List<AssistantMemory> {
        return memoryDAO.getMemoriesOfAssistant(GLOBAL_MEMORY_ID)
            .map { AssistantMemory(it.id, it.content) }
    }

    suspend fun deleteMemoriesOfAssistant(assistantId: String) {
        memoryDAO.deleteMemoriesOfAssistant(assistantId)
    }

    suspend fun updateContent(id: Int, content: String): AssistantMemory {
        val old = memoryDAO.getMemoryById(id) ?: error("Memory record #$id not found")
        val newMemory = old.copy(
            content = content
        )
        memoryDAO.updateMemory(newMemory)
        metaStore?.touchWritten(id, created = false)
        return AssistantMemory(
            id = newMemory.id,
            content = newMemory.content,
        )
    }

    suspend fun addMemory(assistantId: String, content: String): AssistantMemory {
        // Dedupe on write: a near-restatement of an existing record updates it (newer wording
        // wins) instead of piling up duplicates that cost tokens on every later turn.
        val existing = memoryDAO.getMemoriesOfAssistant(assistantId)
        existing
            .map { it to MemoryRetrieval.similarity(it.content, content) }
            .filter { it.second >= DUPLICATE_SIMILARITY }
            .maxByOrNull { it.second }
            ?.let { (match, _) -> return updateContent(match.id, content) }
        val memory = AssistantMemory(
            id = 0,
            content = content,
        )
        val newMemory = memory.copy(
            id = memoryDAO.insertMemory(
                MemoryEntity(
                    assistantId = assistantId,
                    content = memory.content
                )
            ).toInt()
        )
        metaStore?.touchWritten(newMemory.id, created = true)
        return newMemory
    }

    suspend fun deleteMemory(id: Int) {
        memoryDAO.deleteMemory(id)
        metaStore?.remove(id)
    }

    /** Relevance-ranked search across [assistantId]'s records (memory_tool `search`). */
    suspend fun search(assistantId: String, query: String, limit: Int = 10): List<AssistantMemory> {
        val all = getMemoriesOfAssistant(assistantId)
        return MemoryRetrieval.rank(all, query, metaStore?.all().orEmpty())
            .filter { MemoryRetrieval.tokens(it.content).any { t -> t in MemoryRetrieval.tokens(query) } }
            .take(limit)
    }
}
