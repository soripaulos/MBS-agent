package me.rerere.rikkahub.data.repository

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.model.AssistantMemory
import java.io.File
import kotlin.math.ln

/** Per-memory bookkeeping kept beside the DB (no schema migration needed). */
@Serializable
data class MemoryMeta(
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    val lastUsedAt: Long = 0,
    val uses: Int = 0,
    val pinned: Boolean = false,
)

/**
 * Small JSON sidecar (`filesDir/memory_meta.json`) with timestamps, usage counts and pins
 * for memory records. Missing entries simply mean "unknown"; nothing breaks without it.
 */
class MemoryMetaStore(context: Context) {
    private val file = File(context.filesDir, "memory_meta.json")
    private val json = Json { ignoreUnknownKeys = true }
    private var cache: MutableMap<Int, MemoryMeta>? = null

    @Synchronized
    private fun load(): MutableMap<Int, MemoryMeta> {
        cache?.let { return it }
        val loaded = runCatching {
            if (file.exists()) json.decodeFromString(MapSerializer(Int.serializer(), MemoryMeta.serializer()), file.readText()).toMutableMap() else null
        }.getOrNull() ?: mutableMapOf()
        cache = loaded
        return loaded
    }

    @Synchronized
    private fun save() {
        val data = cache ?: return
        runCatching {
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(json.encodeToString(MapSerializer(Int.serializer(), MemoryMeta.serializer()), data))
            tmp.renameTo(file)
        }
    }

    @Synchronized
    fun get(id: Int): MemoryMeta = load()[id] ?: MemoryMeta()

    @Synchronized
    fun all(): Map<Int, MemoryMeta> = load().toMap()

    @Synchronized
    fun touchWritten(id: Int, created: Boolean) {
        val now = System.currentTimeMillis()
        val m = load()
        val old = m[id] ?: MemoryMeta(createdAt = now)
        m[id] = old.copy(createdAt = if (created || old.createdAt == 0L) now else old.createdAt, updatedAt = now)
        save()
    }

    /** Called with the notes injected into a request; throttled to one write per call batch. */
    @Synchronized
    fun touchUsed(ids: Collection<Int>) {
        if (ids.isEmpty()) return
        val now = System.currentTimeMillis()
        val m = load()
        ids.forEach { id ->
            val old = m[id] ?: MemoryMeta()
            m[id] = old.copy(lastUsedAt = now, uses = old.uses + 1)
        }
        save()
    }

    @Synchronized
    fun setPinned(id: Int, pinned: Boolean) {
        val m = load()
        m[id] = (m[id] ?: MemoryMeta()).copy(pinned = pinned)
        save()
    }

    @Synchronized
    fun remove(id: Int) {
        if (load().remove(id) != null) save()
    }
}

/**
 * Memory selection and hygiene, after the patterns used by agent memory systems (MemGPT/Letta
 * core vs archival memory, Mem0's add-or-update dedupe, ChatGPT's saved-memory curation):
 *
 * - **Core memory** — profile and preference records, plus anything pinned, are always in
 *   context: they're small and describe *who* the user is.
 * - **Archival memory** — notes are ranked against the current message (BM25-style keyword
 *   relevance, with a nudge for recently used / updated records) and only the best fit within
 *   a token budget is injected. The rest stays one `memory_tool search` away, so a store of
 *   hundreds of notes costs the same per turn as a store of twenty.
 * - **Dedupe on write** — a new record that is near-identical to an existing one updates that
 *   record instead of adding a duplicate.
 */
object MemoryRetrieval {
    private val KIND_TAG = Regex("""^\[(profile|preference)]\s*""")
    const val MAX_NOTES = 12
    private const val NOTES_CHAR_BUDGET = 4_000

    data class Selection(val core: List<AssistantMemory>, val notes: List<AssistantMemory>, val hiddenNotes: Int)

    private val STOP = setOf(
        "the", "and", "for", "you", "are", "with", "that", "this", "have", "was", "but", "not", "what", "can", "how",
        "user", "users", "about", "from", "they", "their", "will", "would", "should", "please", "into", "your", "just",
    )

    internal fun tokens(text: String): List<String> =
        text.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length > 2 && it !in STOP }

    fun isCore(memory: AssistantMemory, meta: MemoryMeta?): Boolean =
        KIND_TAG.containsMatchIn(memory.content) || meta?.pinned == true

    fun select(
        memories: List<AssistantMemory>,
        query: String?,
        meta: Map<Int, MemoryMeta>,
        maxNotes: Int = MAX_NOTES,
    ): Selection {
        val (core, notes) = memories.partition { isCore(it, meta[it.id]) }
        if (notes.size <= maxNotes) return Selection(core, notes, 0)
        val ranked = rank(notes, query.orEmpty(), meta)
        val picked = mutableListOf<AssistantMemory>()
        var chars = 0
        for (m in ranked) {
            if (picked.size >= maxNotes || chars + m.content.length > NOTES_CHAR_BUDGET) break
            picked += m
            chars += m.content.length
        }
        // Keep the injected notes in their original (chronological) order.
        val keep = picked.map { it.id }.toSet()
        return Selection(core, notes.filter { it.id in keep }, notes.size - picked.size)
    }

    /** Relevance-ranked records (BM25-lite over [memories]); ties broken by recency/use. */
    fun rank(memories: List<AssistantMemory>, query: String, meta: Map<Int, MemoryMeta>): List<AssistantMemory> {
        val docs = memories.map { it to tokens(it.content) }
        val q = tokens(query).toSet()
        val n = docs.size.coerceAtLeast(1)
        val df = HashMap<String, Int>()
        docs.forEach { (_, t) -> t.toSet().forEach { df[it] = (df[it] ?: 0) + 1 } }
        val avgLen = docs.map { it.second.size }.average().takeIf { it > 0 } ?: 1.0
        val now = System.currentTimeMillis()
        return docs.map { (m, t) ->
            var score = 0.0
            if (q.isNotEmpty()) {
                val tf = t.groupingBy { it }.eachCount()
                q.forEach { term ->
                    val f = tf[term] ?: return@forEach
                    val idf = ln(1 + (n - (df[term] ?: 0) + 0.5) / ((df[term] ?: 0) + 0.5))
                    score += idf * (f * 2.2) / (f + 1.2 * (0.25 + 0.75 * t.size / avgLen))
                }
            }
            val info = meta[m.id]
            val lastTouch = maxOf(info?.lastUsedAt ?: 0, info?.updatedAt ?: 0)
            if (lastTouch > 0) {
                val days = (now - lastTouch) / 86_400_000.0
                score += 0.3 / (1 + days / 7) // gentle recency prior
            }
            score += 0.05 * ln(1.0 + (info?.uses ?: 0))
            m to score
        }.sortedByDescending { it.second }.map { it.first }
    }

    /** Word-set Jaccard similarity, used to spot a re-statement of an existing memory. */
    fun similarity(a: String, b: String): Double {
        val x = tokens(a.replace(KIND_TAG, "")).toSet()
        val y = tokens(b.replace(KIND_TAG, "")).toSet()
        if (x.isEmpty() || y.isEmpty()) return 0.0
        return x.intersect(y).size.toDouble() / x.union(y).size
    }
}
