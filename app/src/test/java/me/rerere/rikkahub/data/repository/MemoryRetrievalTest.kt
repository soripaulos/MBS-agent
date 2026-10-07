package me.rerere.rikkahub.data.repository

import me.rerere.rikkahub.data.model.AssistantMemory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryRetrievalTest {
    private fun notes(n: Int) = (1..n).map { AssistantMemory(it, "note number $it about gardening topic$it") }

    @Test
    fun `small stores are injected whole`() {
        val all = notes(5) + AssistantMemory(99, "[profile] Name is Sol")
        val sel = MemoryRetrieval.select(all, "anything", emptyMap())
        assertEquals(1, sel.core.size)
        assertEquals(5, sel.notes.size)
        assertEquals(0, sel.hiddenNotes)
    }

    @Test
    fun `large stores keep core and the relevant notes`() {
        val all = notes(40) + AssistantMemory(100, "User's car is a blue Toyota needing an oil change") +
            AssistantMemory(101, "[preference] Replies in English")
        val sel = MemoryRetrieval.select(all, "when is my toyota oil change due?", emptyMap())
        assertTrue(sel.core.any { it.id == 101 })
        assertTrue(sel.notes.any { it.id == 100 })
        assertTrue(sel.notes.size <= MemoryRetrieval.MAX_NOTES)
        assertEquals(41 - sel.notes.size, sel.hiddenNotes)
    }

    @Test
    fun `pinned notes are core`() {
        val all = notes(40)
        val sel = MemoryRetrieval.select(all, "x", mapOf(7 to MemoryMeta(pinned = true)))
        assertTrue(sel.core.any { it.id == 7 })
    }

    @Test
    fun `restatements are detected`() {
        assertTrue(MemoryRetrieval.similarity("User prefers short replies in English", "[preference] User prefers short English replies") >= 0.8)
        assertTrue(MemoryRetrieval.similarity("User prefers short replies", "User lives in Addis Ababa") < 0.2)
    }
}
