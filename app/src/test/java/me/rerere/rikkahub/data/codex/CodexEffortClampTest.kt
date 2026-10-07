package me.rerere.rikkahub.data.codex

import me.rerere.ai.provider.Model
import org.junit.Assert.assertEquals
import org.junit.Test

class CodexEffortClampTest {
    @Test
    fun `unknown model keeps legacy behaviour`() {
        assertEquals("none", clampCodexEffort("none", null))
        assertEquals("xhigh", clampCodexEffort("max", null))
        assertEquals("high", clampCodexEffort("high", emptyList()))
    }

    @Test
    fun `supported effort passes through`() {
        assertEquals("max", clampCodexEffort("max", listOf("low", "medium", "high", "xhigh", "max")))
    }

    @Test
    fun `off on a model without none becomes the cheapest effort`() {
        assertEquals("low", clampCodexEffort("none", listOf("low", "medium", "high", "xhigh", "max")))
    }

    @Test
    fun `max on an older model drops to xhigh`() {
        assertEquals("xhigh", clampCodexEffort("max", listOf("low", "medium", "high", "xhigh")))
    }

    @Test
    fun `merge appends new models and flags retired ones`() {
        val existing = listOf(Model(modelId = "gpt-5.5", displayName = "GPT-5.5"), Model(modelId = "gpt-6-luna", displayName = "Luna"))
        val catalog = listOf(Model(modelId = "gpt-6-luna", displayName = "GPT-6 Luna"), Model(modelId = "gpt-6.1-sol", displayName = "GPT-6.1 Sol"))
        val (merged, report) = CodexModelSync.merge(existing, catalog)
        assertEquals(listOf("gpt-5.5", "gpt-6-luna", "gpt-6.1-sol"), merged.map { it.modelId })
        assertEquals("GPT-5.5 (retired)", merged[0].displayName)
        assertEquals("Luna", merged[1].displayName) // user's own name is kept
        assertEquals(listOf("gpt-6.1-sol"), report.added)
        assertEquals(listOf("gpt-5.5"), report.retired)
        // a model that comes back loses the suffix
        val (again, _) = CodexModelSync.merge(merged, catalog + Model(modelId = "gpt-5.5"))
        assertEquals("GPT-5.5", again[0].displayName)
    }
}
