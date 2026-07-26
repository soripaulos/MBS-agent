# MBS-Agent — User Guide

This guide explains every major feature, what it's for, and how the pieces fit together.
It is also readable by the assistant itself: ask in chat "how do I use X" or "what's the
difference between quick messages and mode injections" and it will answer from this guide
(via the `read_app_docs` tool).

---

## 1. Core concepts at a glance

| Concept | What it is | When to use it |
|---|---|---|
| **Assistant** | A saved persona = system prompt + model + enabled tools + skills + memory settings. | Make one per *kind* of work (Journal, Coder, Research) instead of retoggling settings. |
| **Skill** | A markdown procedure the assistant loads on demand to do something consistently. | Repeatable multi-step tasks ("post to my blog", "morning briefing"). |
| **Memory** | Long-term facts the assistant remembers across every chat. | Preferences, your profile, ongoing project state. |
| **Quick message** | A saved snippet you insert into the input with one tap. | Prompts you retype often. |
| **Mode injection** | Extra text force-added to the prompt every turn while active. | A temporary "mode" ("reply only in JSON", "stay in character"). |
| **Lorebook (world book)** | Keyword-triggered snippets injected only when the keyword appears. | Large reference sets where only the relevant entry should load (characters, docs). |
| **Workspace** | A Linux rootfs the assistant can run shell commands and read/write files in. | Coding, scripting, anything needing a real filesystem + shell. |

---

## 2. Assistants

An **assistant** bundles everything: which model, which local tools, which skills, memory
on/off, plan mode, smart mode, temperature, chat background. Switch assistants from the
picker at the top of the chat.

**Recommendation:** don't overload one assistant. Make focused ones. You can now say in
chat *"make me a journaling assistant with memory on"* and it will create one for you
(see Smart mode + self-configuration below).

Key per-assistant settings (Assistant → Basic / Local tools / Skills):
- **System prompt** — the persona/instructions. This is your `SOUL.md` equivalent.
- **Memory** — see §4.
- **Plan mode** — the assistant presents a numbered plan and waits for your approval
  before running anything that changes state (shell, file writes, sending messages,
  device control). Turn on for risky/destructive work; leave off for quick chat.
- **Auto-compact context** — when the conversation grows past a token threshold, older
  messages are automatically summarized so you don't blow the context window on long
  sessions. Threshold is configurable.
- **Smart mode** — see §3.
- **Gradient background + colors** — cosmetic; pick up to 4 colors for the animated chat
  background.

---

## 3. Smart mode (autopilot)

Turn on **Smart mode** (Assistant → Basic) to make the assistant proactive and
context-aware instead of a passive responder. With it on, the assistant will, on its own:

1. **Classify what you're doing** before acting (a task? journaling? a question? a
   preference?).
2. **Recall before re-deriving** — search memory, your skills, and past conversations for
   prior work on the same thing instead of burning tokens re-figuring it out. If it finds
   a matching skill or past solution, it reuses it.
3. **File information automatically** — decides what belongs in long-term memory vs. a
   short-lived note, captures journal entries and to-dos from what you say, without you
   having to tell it "remember this" or "this is a task".
4. **Persist reusable procedures as skills** — when it solves something non-obvious and
   repeatable, it saves a skill so next time is instant.
5. **Self-configure** — enables the tools/skills a task needs (with your approval), or
   offers to create a dedicated assistant when it spots a recurring pattern.
6. **Ask when genuinely unsure** — it uses the `ask_user` question card rather than
   guessing on decisions only you can make.

Smart mode is a *policy*, not full autonomy: anything that changes state still respects
tool approvals, and it asks before big or ambiguous actions. It pairs best with **Memory
on**, and the **Session search**, **Skill import**, and **Agent config** tools enabled —
Smart mode will offer to enable these for you if they're off.

**Example — journaling:** with Smart mode + memory on, just talk. "Today was rough, the
demo slipped to Friday and I still need to finish the deck." The assistant recognizes a
journal entry, stores the dated reflection, files "demo → Friday" as a task, and notes
"working on a deck" as project state — no commands from you.

---

## 4. Memory

Memory is long-term knowledge the assistant carries across all chats (shown to it inside a
`<memories>` block each turn). Enable it per assistant (Assistant → Basic → Memory).

- **Assistant memory vs. Global memory:** assistant memory is private to that persona;
  global memory (toggle "use global memory") is shared across every assistant — use global
  for facts about *you* (your name, timezone, preferences); use assistant memory for
  persona-specific context.
- **Kinds:** each memory is a **profile** (who you are), a **preference** (how you like
  things), or a **note** (everything else — plans, project state, journal facts). The
  assistant classifies these automatically; they render in grouped sections.
- **Curation:** the assistant merges duplicates and drops stale entries over time. You can
  view/edit all memories in Assistant → Memory.

**Memory vs. Skills:** memory is *what it knows*; skills are *how it does things*. A fact
("I use pnpm, not npm") is memory. A procedure ("how to cut a release") is a skill.

---

## 5. Skills

A **skill** is a markdown file (`SKILL.md` + optional support files) with a name and
description. The assistant sees the list of enabled skills and loads a skill's full
instructions on demand when your request matches (progressive disclosure — it doesn't pay
tokens for skills it isn't using).

Ways skills get created/added:
- **Automatically (recommended):** with Smart mode / the `skill_manage` tool, the
  assistant writes and refines its own skills from experience. You don't have to author
  them.
- **Import:** `skill_install_from_url` / `skill_install_from_text` pull a skill from a URL
  or pasted text (supports native, OpenClaw, and Hermes formats).
- **Manually:** Extensions → Skills to create/edit by hand.
- **Auto-load skills:** a skill with `auto_load: true` in its frontmatter is injected into
  the system prompt every turn (use for a core persona / `SOUL.md`-style skill).

Enable skills per assistant in Assistant → Skills. The **Skill import** and **JS skills**
tool toggles control whether the assistant may install/run skills itself.

---

## 6. Local tools (device + system capabilities)

Assistant → Local tools is a big menu of capabilities, grouped. You only enable what a
given assistant needs. Highlights:

- **Shell / dev:** `termux` (run any Termux CLI — this is how Claude Code / opencode run),
  `ssh` (remote servers), `files`, `eval_javascript`, `web_fetch`, `browser`.
- **Device:** camera, microphone, SMS, contacts, call log, sensors, NFC, torch, brightness,
  volume, wallpaper, notifications, screen automation (tap/swipe/scroll), app launcher.
- **Agent infra:** `sub_agents` (delegate parallel work), `cron_jobs` (schedule tasks),
  `workflows` (trigger→condition→action automations), `telegram_bot`, `cost_guards`
  (token budget), `session_search` (search past chats), `mcp_control` (manage MCP servers),
  `agent_config` (let the assistant change its own settings from chat), `app_docs` (let it
  answer how-to questions from this guide).

**Approvals:** side-effecting tools ask before running. "Allow for this chat" grants for
the current conversation; "Always allow" grants permanently (Settings → Tool approvals).
Some dangerous shell patterns are hard-blocked regardless.

---

## 7. MCP servers (incl. OAuth)

Settings → MCP connects external Model Context Protocol servers (extra tools). Streamable
HTTP and SSE with header auth are supported, and **OAuth MCP**: for a server like Frappe
Assistant Core, just enter the URL, toggle OAuth on, and sign in through the browser —
same as a ChatGPT/Claude desktop connector. The app handles discovery, dynamic client
registration, PKCE, and token refresh.

---

## 8. Prompt shaping: quick messages, mode injections, lorebooks

These three are often confused. The difference:

- **Quick messages** are *for you*: saved input snippets. Tapping one drops its text into
  the input box so you can send/edit it. They do nothing on their own. Use for prompts you
  type a lot. (Assistant → Quick messages.)
- **Mode injections** are *always-on while active*: extra instructions force-added to the
  prompt every turn. Think of them as toggleable "modes" ("answer only in Amharic",
  "stay terse"). Enable/disable per assistant. They fire every turn regardless of content.
- **Lorebooks (world books)** are *conditional*: entries injected only when their keyword
  appears in the conversation. Use for large reference libraries where loading everything
  would waste tokens — only the relevant entry loads. (E.g. an entry keyed on a character
  or project name.)

Rule of thumb: **quick message** = shortcut you trigger; **mode injection** = always-on
rule; **lorebook** = auto-loaded reference triggered by keywords.

---

## 9. Token efficiency

Built-in ways the app keeps costs down, and how to help:
- **Auto-compact** (§2) summarizes old history automatically.
- **Prompt caching:** the stable part of the system prompt (persona + tools) is kept
  byte-identical so providers cache it; volatile parts (memory, recent chats) sit after
  it. Toggling settings mid-conversation costs a cache miss for one turn.
- **Recall over re-derivation:** Smart mode + `session_search` reuse prior work instead of
  recomputing. Enable them.
- **Fast-path router:** deterministic intents (e.g. "what's my battery") run the tool
  directly, skipping the LLM entirely.
- **Cost guards:** set a token budget per assistant; `check_token_usage` lets the model
  self-stop.
- **Tips:** keep one focused assistant per task (smaller tool list = smaller prompt);
  disable tools you don't use; use lorebooks instead of stuffing everything into the
  system prompt.

---

## 10. Self-management cookbook (for the agent and the curious)

The assistant can build and configure almost everything itself. The exact verb map:

| You want | The agent calls | Notes |
|---|---|---|
| New skill from this session's work | `skill_manage` action=create | Auto-enabled after creation |
| Fix a wrong skill | `skill_manage` action=patch | Exact find/replace in SKILL.md |
| Import a skill from a URL or pasted text | `skill_install_from_url` / `skill_install_from_text` | Approval card shows source + name; "Always allow" eligible |
| New assistant/persona | `create_assistant` | Name + prompt + tool categories |
| Toggle its own tools/skills/settings | `get_agent_config` → `set_agent_config` | Diff shown on the approval card |
| Remember something | `memory_tool` | kind = profile / preference / note |
| Recurring automation with a trigger | `workflow_create` (+ other `workflow_*`) | Trigger → condition → action |
| Scheduled/recurring task | `schedule_job` | Cron-style, natural language OK |
| Add/test an MCP server | `mcp_add`, `mcp_test`, `mcp_set_enabled` | OAuth servers: add URL + enable OAuth |
| Search past conversations | `session_search` / `session_get` | FTS over every chat |
| Answer "how does this app work" | `read_app_docs` | Reads this guide |

If the assistant says it can't do one of these, the usual cause is the tool *category*
being disabled for that assistant (Assistant → Local tools): Skill import, Workflows,
Cron jobs, MCP control, Self-configuration, Session search, In-app help. Enable them —
or just ask the assistant to enable them itself (needs Self-configuration on).

Approval philosophy: the agent should *attempt* the action and let the approval card be
your checkpoint. You can grant "Always allow" per tool in Settings → Tool approvals to
remove friction for verbs you trust (skill import and MCP add are eligible).

---

## 11. Omnitrix identity

The app's identity is the **Omnitrix**. Four dial styles are available: **Original**,
**Alien Force**, **Ultimatrix**, and **Omniverse** (default).

- **Variant** — Settings → Preferences → UI → Omnitrix → Variant. Themes the assistant
  selector dial art.
- **Omnitrix selector** — the assistant picker is a dial: assistants orbit the dial,
  tap one to rotate it into position, then hit **Transform** to switch. Toggle back to
  the plain list in the same settings section.
- **App icon** — Settings → Preferences → UI → App icon: each choice is one of the four
  Omnitrix editions (splash icon follows on Android 12+).
- **Splash** — animated Omnitrix constellation on Android 12+.
- **Custom art** — the bundled dials are placeholders. Drop in your own artwork by
  replacing the drawable files (same names) — see `docs/omnitrix-assets.md` in the
  repository for the exact list and specs.

---

## 12. Common "how do I…"

- **Change a setting without leaving chat:** just ask ("turn on plan mode", "enable ssh
  tools", "raise auto-compact to 100k"). With the `agent_config` tool enabled, the
  assistant applies it after you approve.
- **Make a task-specific assistant:** ask ("create a research assistant with web fetch and
  session search"). It builds and saves it.
- **Have it remember something:** just tell it; with memory on it decides where the info
  belongs. To force it: "remember that …".
- **Recall past work:** "did we set up the X deploy before?" — it searches past sessions.
- **Teach it a repeatable procedure:** do it once and say "save that as a skill", or let
  Smart mode persist it automatically.
