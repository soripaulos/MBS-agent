package me.rerere.rikkahub.data.ai.tools

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart

private const val DOCS_ASSET = "docs/user-guide.md"

/**
 * Phase 18 — `read_app_docs`: lets the assistant answer "how do I use X in this app"
 * questions from the bundled user guide (assets/docs/user-guide.md) instead of guessing or
 * hallucinating menu paths. The guide is split on its `## ` section headings; with no
 * `section` argument the tool returns the table of contents (heading list) so the model can
 * pick the relevant one, then re-call with a keyword to pull that section's text.
 *
 * Pure read, no approval. Same doc the in-app Help page renders, so answers stay in sync.
 */
fun createAppDocsTool(context: Context): Tool = Tool(
    name = "read_app_docs",
    description = """
        Read the in-app user guide to answer questions about how to use THIS app's features
        (assistants, skills, memory, quick messages vs mode injections vs lorebooks, local
        tools, MCP, smart mode, token efficiency, etc.). Call with no arguments to get the
        list of section titles, then call again with `section` set to a keyword from a title
        to get that section's full text. Use this before explaining app features so your
        answer matches the actual UI.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("section", buildJsonObject {
                    put("type", "string")
                    put("description", "Keyword matching a section title (omit to list all sections)")
                })
            },
            required = emptyList()
        )
    },
    execute = { input ->
        val query = input.jsonObject["section"]?.jsonPrimitive?.contentOrNull?.trim()
        val doc = withContext(Dispatchers.IO) {
            runCatching {
                context.assets.open(DOCS_ASSET).bufferedReader().use { it.readText() }
            }.getOrNull()
        } ?: return@Tool listOf(
            UIMessagePart.Text(
                buildJsonObject {
                    put("error", "docs_unavailable")
                    put("detail", "The bundled user guide could not be read.")
                }.toString()
            )
        )

        // Split into ("## heading", body) sections. The preamble before the first "## "
        // heading is kept under a synthetic "Overview" so nothing is lost.
        val sections = linkedMapOf<String, String>()
        val sb = StringBuilder()
        var currentTitle = "Overview"
        doc.lineSequence().forEach { line ->
            if (line.startsWith("## ")) {
                sections[currentTitle] = sb.toString().trim()
                sb.setLength(0)
                currentTitle = line.removePrefix("## ").trim()
            } else {
                sb.appendLine(line)
            }
        }
        sections[currentTitle] = sb.toString().trim()

        if (query.isNullOrBlank()) {
            return@Tool listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("sections", buildJsonArray { sections.keys.forEach { add(JsonPrimitive(it)) } })
                        put("hint", "Call read_app_docs again with section set to a keyword from one of these titles.")
                    }.toString()
                )
            )
        }

        val match = sections.entries.firstOrNull { (title, _) ->
            title.contains(query, ignoreCase = true)
        } ?: sections.entries.firstOrNull { (_, body) ->
            body.contains(query, ignoreCase = true)
        }

        if (match == null) {
            return@Tool listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("error", "section_not_found")
                        put("query", query)
                        put("sections", buildJsonArray { sections.keys.forEach { add(JsonPrimitive(it)) } })
                    }.toString()
                )
            )
        }

        listOf(
            UIMessagePart.Text(
                buildJsonObject {
                    put("section", match.key)
                    put("content", match.value.take(8_000))
                }.toString()
            )
        )
    }
)
