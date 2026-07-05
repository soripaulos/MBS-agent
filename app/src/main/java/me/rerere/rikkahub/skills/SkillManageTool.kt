package me.rerere.rikkahub.skills

import kotlinx.coroutines.flow.first
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
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.files.SkillManager

/**
 * Phase 17 — `skill_manage`: the self-improvement verb (Hermes `skill_manage` parity).
 *
 * Where `skill_install_from_*` brings skills in from OUTSIDE (URLs, pasted text — every
 * call individually approved because the source is untrusted), `skill_manage` lets the
 * model persist and refine procedures it worked out IN-SESSION:
 *
 *  - `create`      — write a brand-new skill (name + full SKILL.md content).
 *  - `patch`       — surgical find/replace inside an existing skill's SKILL.md. The
 *                    `find` string must occur exactly once; content outside the match is
 *                    untouched, and sibling files in the skill dir are preserved.
 *  - `write_file`  — add or overwrite a support file inside the skill dir.
 *  - `delete_file` — remove a support file (SKILL.md itself is refused; use `delete`).
 *  - `delete`      — remove the whole skill (also unenables it for every assistant).
 *
 * Approval: side-effecting (writes to disk, and skill content rides into future system
 * prompts), so the tool is in [me.rerere.rikkahub.data.ai.tools.ToolApprovalDefaults]
 * ALWAYS_ASK. Unlike the install tools it CAN be "Always Allow"-ed — the content comes
 * from the model's own session work, not an arbitrary URL the model named.
 *
 * The systemPrompt below is the "closed learning loop" nudge: it rides along whenever
 * the tool is registered, telling the model to persist reusable procedures.
 */
fun skillManageTool(
    settingsStore: SettingsStore,
    skillManager: SkillManager,
): Tool = Tool(
    name = "skill_manage",
    description = """
        Create, patch, or delete your own skills (persisted procedures usable in future
        sessions). Actions: create (name + content = full SKILL.md markdown with a
        '# Title' heading and a description paragraph), patch (name + find + replace —
        find must match exactly once in SKILL.md), write_file (name + path + content),
        delete_file (name + path), delete (name). Skill names: 1..40 chars of [a-z0-9_-].
        Newly created skills are auto-enabled for the current assistant.
    """.trimIndent().replace("\n", " "),
    systemPrompt = { _, _ ->
        """
        Self-improvement: when you solve a task that required a non-obvious, reusable,
        multi-step procedure (a working command sequence, an API quirk, a recovery path),
        persist it with skill_manage(action="create") so future sessions start smarter.
        When an existing skill's instructions proved wrong or incomplete during use, fix
        them with skill_manage(action="patch"). Keep skills short and procedural; do not
        store secrets in them.
        """.trimIndent().replace("\n", " ")
    },
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("action", buildJsonObject {
                    put("type", "string")
                    put(
                        "enum",
                        buildJsonArray {
                            add("create")
                            add("patch")
                            add("write_file")
                            add("delete_file")
                            add("delete")
                        }
                    )
                    put("description", "Operation to perform")
                })
                put("name", buildJsonObject {
                    put("type", "string")
                    put("description", "Skill name (1..40 chars, [a-z0-9_-])")
                })
                put("content", buildJsonObject {
                    put("type", "string")
                    put("description", "Full SKILL.md content (create) or file content (write_file). Max 256KB.")
                })
                put("find", buildJsonObject {
                    put("type", "string")
                    put("description", "patch only: exact text to replace in SKILL.md; must occur exactly once")
                })
                put("replace", buildJsonObject {
                    put("type", "string")
                    put("description", "patch only: replacement text")
                })
                put("path", buildJsonObject {
                    put("type", "string")
                    put("description", "write_file/delete_file only: relative path inside the skill directory")
                })
            },
            required = listOf("action", "name")
        )
    },
    needsApproval = { true },
    execute = { input ->
        fun err(code: String, detail: String): List<UIMessagePart> = listOf(
            UIMessagePart.Text(
                buildJsonObject {
                    put("ok", false)
                    put("error", code)
                    put("detail", detail)
                }.toString()
            )
        )

        fun ok(action: String, name: String, extra: Map<String, String> = emptyMap()): List<UIMessagePart> =
            listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("ok", true)
                        put("action", action)
                        put("name", name)
                        extra.forEach { (k, v) -> put(k, JsonPrimitive(v)) }
                    }.toString()
                )
            )

        val params = input.jsonObject
        val action = params["action"]?.jsonPrimitive?.contentOrNull
            ?: return@Tool err("missing_action", "action is required")
        val name = params["name"]?.jsonPrimitive?.contentOrNull?.trim()
            ?: return@Tool err("missing_name", "name is required")
        if (!(name.length in 1..40 && name.matches(Regex("""^[a-z0-9_-]+$""")))) {
            return@Tool err("invalid_name", "skill name must be 1..40 chars of [a-z0-9_-] (got '$name')")
        }
        val content = params["content"]?.jsonPrimitive?.contentOrNull
        if (content != null && content.length > 256 * 1024) {
            return@Tool err("content_too_large", "content is capped at 256KB")
        }
        val exists = skillManager.listSkills().any { it.name == name }

        when (action) {
            "create" -> {
                if (content.isNullOrBlank()) {
                    return@Tool err("missing_content", "create requires full SKILL.md content")
                }
                if (exists) {
                    return@Tool err(
                        "skill_exists",
                        "Skill '$name' already exists. Use action=patch to modify it, or pick another name."
                    )
                }
                skillManager.saveSkill(name, content)
                    ?: return@Tool err("save_failed", "could not write skill '$name' to disk (check the markdown has a '# Title' heading)")
                // Auto-enable for the current assistant, mirroring the install tools'
                // behavior so the skill is usable on the next turn without a manual toggle.
                runCatching {
                    val settings = settingsStore.settingsFlow.first()
                    val assistant = settings.getCurrentAssistant()
                    if (name !in assistant.enabledSkills) {
                        settingsStore.update { current ->
                            current.copy(
                                assistants = current.assistants.map { a ->
                                    if (a.id == assistant.id) a.copy(enabledSkills = a.enabledSkills + name) else a
                                }
                            )
                        }
                    }
                }
                ok("create", name, mapOf("detail" to "Skill created and enabled for the current assistant."))
            }

            "patch" -> {
                if (!exists) return@Tool err("skill_not_found", "no skill named '$name'")
                val find = params["find"]?.jsonPrimitive?.contentOrNull
                    ?: return@Tool err("missing_find", "patch requires 'find'")
                val replace = params["replace"]?.jsonPrimitive?.contentOrNull
                    ?: return@Tool err("missing_replace", "patch requires 'replace'")
                val current = skillManager.readSkillContent(name)
                    ?: return@Tool err("read_failed", "could not read SKILL.md of '$name'")
                val occurrences = current.split(find).size - 1
                if (occurrences == 0) {
                    return@Tool err("find_not_found", "'find' text does not occur in SKILL.md — re-read the skill and retry with exact text")
                }
                if (occurrences > 1) {
                    return@Tool err("find_ambiguous", "'find' text occurs $occurrences times; include more surrounding context so it matches exactly once")
                }
                val updated = current.replace(find, replace)
                // saveSkillFile writes SKILL.md in place, preserving sibling files —
                // saveSkill would atomically REPLACE the whole skill dir and drop them.
                if (!skillManager.saveSkillFile(name, "SKILL.md", updated)) {
                    return@Tool err("save_failed", "could not write patched SKILL.md")
                }
                ok("patch", name)
            }

            "write_file" -> {
                if (!exists) return@Tool err("skill_not_found", "no skill named '$name'")
                val path = params["path"]?.jsonPrimitive?.contentOrNull?.trim()
                    ?: return@Tool err("missing_path", "write_file requires 'path'")
                if (content == null) return@Tool err("missing_content", "write_file requires 'content'")
                if (path == "SKILL.md") {
                    return@Tool err("use_patch", "use action=patch (or create) for SKILL.md itself")
                }
                if (!skillManager.saveSkillFile(name, path, content)) {
                    return@Tool err("save_failed", "could not write '$path' (path may resolve outside the skill directory)")
                }
                ok("write_file", name, mapOf("path" to path))
            }

            "delete_file" -> {
                if (!exists) return@Tool err("skill_not_found", "no skill named '$name'")
                val path = params["path"]?.jsonPrimitive?.contentOrNull?.trim()
                    ?: return@Tool err("missing_path", "delete_file requires 'path'")
                if (path == "SKILL.md") {
                    return@Tool err("refused", "refusing to delete SKILL.md — use action=delete to remove the whole skill")
                }
                if (!skillManager.deleteSkillFile(name, path)) {
                    return@Tool err("delete_failed", "could not delete '$path' (missing, or outside the skill directory)")
                }
                ok("delete_file", name, mapOf("path" to path))
            }

            "delete" -> {
                if (!exists) return@Tool err("skill_not_found", "no skill named '$name'")
                if (!skillManager.deleteSkill(name)) {
                    return@Tool err("delete_failed", "could not delete skill '$name'")
                }
                ok("delete", name)
            }

            else -> err("unknown_action", "action must be one of create/patch/write_file/delete_file/delete")
        }
    },
)
