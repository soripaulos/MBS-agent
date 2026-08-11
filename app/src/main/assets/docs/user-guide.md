# Omnitrix — User Guide

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

## 8. Prompt shaping: every concept explained

These features all shape what the model sees. Here is what each one is, when to use it,
and how they differ. (The assistant can create most of them for you from chat — see the
`prompt-shaping-guide` skill and `manage_prompt_shaping` tool.)

- **System prompt** (Assistant → Prompt): the assistant's core identity and standing
  instructions. Stable; sent every turn at the very top.
- **Conversation system prompt** (per-chat override): when the assistant has "allow
  conversation system prompt" enabled, an individual chat can carry its OWN system prompt
  that *replaces* the assistant's for that chat only. Use for one-off contexts ("this
  chat is a mock interview") without touching the persona.
- **Prompt injections** is the umbrella term for text force-inserted into the prompt at a
  chosen *position* (before/after system prompt, top/bottom of chat, or at a depth N
  messages back). Two kinds exist:
  - **Mode injections** — unconditional: injected every turn while attached to the
    assistant. Think of them as toggleable "modes": "answer only in Amharic", "reply in
    JSON", "stay in character". Attach/detach per assistant. Because they cost tokens
    every single turn, keep them short and detach when done.
  - **Regex/keyword injections (lorebooks)** — conditional: an entry is injected ONLY
    when one of its keywords appears in the recent conversation. A **lorebook** is a
    named collection of such entries. Use for large reference sets (characters, project
    glossaries, API notes) where only the relevant entry should ever load — near-zero
    cost until a keyword triggers it.
- **Message content template** (Assistant → advanced, `{{ message }}`): a wrapper applied
  to every user message before sending. `{{ message }}` is replaced by what you typed.
  Use to consistently frame input, e.g. `Translate to French: {{ message }}` for a
  translator assistant. Leave as plain `{{ message }}` normally.
- **Preset messages**: fake prior chat turns (user/assistant pairs) placed at the top of
  every conversation. The model treats them as things that already happened — the
  classic way to prime style or few-shot examples ("when I paste code, you respond with
  a review in this exact format: …").
- **Quick messages**: saved input snippets *for you*. Tapping one drops its text into the
  input box to send or edit. They do nothing on their own and cost nothing — pure typing
  shortcuts. Attach per assistant.
- **Message regexes** (Assistant → Regex): find/replace rules applied to user and/or
  assistant messages, optionally *visual-only* (changes what you see, not what the model
  sees). Use to strip boilerplate, censor content on screen, or normalize model output.
- **Custom requests = custom headers & custom bodies** (Assistant or Model settings):
  extra HTTP headers or JSON body fields added to every API request for that
  assistant/model. This is for provider-specific extras — e.g. a gateway API key header,
  or a vendor-specific body flag like `{"enable_thinking": true}`. If your provider's
  docs say "pass X-Foo: bar" or "set field foo in the request", this is where it goes.
  If you don't have such a requirement, leave them empty — wrong values cause 400s.

Rule of thumb: **quick message** = shortcut you trigger · **mode injection** = always-on
rule · **lorebook** = keyword-triggered reference · **preset messages** = style priming ·
**template** = input wrapper · **regex** = post-processing · **custom headers/bodies** =
API plumbing.

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

## 12. Files, backup, and restore

- **Browse the app's data from any file manager**: the app publishes its data folder
  (skills, uploads, exports) through the system file picker. In Files by Google /
  Material Files / similar, open the side drawer and pick the app's data root. You can
  read, edit, copy in/out, and delete — copying the tree out IS a valid manual backup.
  Workspace files are published as a second root.
- **Full backup**: Settings → Backup → Import/Export → Export. This zip contains
  EVERYTHING: settings (providers, assistants, MCP servers, all config), the entire
  database (conversations, memories, workflows, cron jobs, SSH hosts), skills, uploaded
  files, fonts, and every preference store. Restore picks a zip and restarts the app.
  Caveats: workspace Linux rootfs and local model weights are excluded (huge and
  re-downloadable), and encrypted credential stores (OAuth tokens) only survive restore
  on the SAME device — hardware-keystore keys cannot leave the device, so re-sign-in
  after moving to a new phone.
- **Cloud backup**: WebDAV and S3 tabs sync the same archive on a schedule.

## 13. Reliability: long tasks, token limits, auto-resume

- **Why long agent tasks used to die**: every step of a multi-step turn re-sends all
  previous steps' tool outputs, so context grew until the model's window overflowed. The
  app now compacts older tool outputs mid-turn automatically (recent steps stay
  verbatim; older ones become excerpts, with full shell outputs still on disk under
  /tool_outputs). Combined with per-assistant auto-compaction (§2) this keeps very long
  tasks inside the window.
- **Auto-resume**: if a turn dies on a transient error (network reset, timeout, provider
  5xx), the app retries it up to twice with backoff before showing an error. User stops
  are never auto-resumed. Per-assistant toggleable.
- **Scheduled tasks**: a cron run that fails gets one automatic re-run after a short
  backoff before recording failure.
- **Multiple sessions**: each conversation has its own independent generation job — you
  can start a task in one chat, switch assistants/chats, and start another; both run in
  parallel (plus sub-agents within a single chat via the Sub-agents tool).

## 14. Delegating to CLI coding agents (Claude Code, Kimi CLI)

For heavy coding work, delegate to a CLI agent installed in Termux. Enable the Termux
tool category and the bundled `cli-agent-bridge` skill — it teaches the assistant the
exact headless invocations (`claude -p …`, session resume, output capture) and the rules
(show the command first, summarize output, relay questions back to you). Kimi CLI
authenticates itself inside Termux, so its models are usable through delegation without
any Kimi API key in the app.

## 15. Delegating to Kimi Code CLI

The `kimi-delegate` skill hands heavy repo/coding work to Kimi Code CLI in Termux with a
proper written brief (goal, working directory, constraints, only the relevant context,
definition of done), then verifies the result and reports back. Enable the Termux tool
category and the skill. Kimi bills your own Kimi account, so delegating heavy loops is
usually cheaper than iterating in-app. The assistant relays any question Kimi asks back to
you rather than answering on your behalf.

## 16. Token spend: finding the leak

Settings/chat drawer → Statistics now shows **token spend by model** and **top
conversations by token spend**, each with the prompt/completion split. Use it to find the
one runaway chat or model that's burning your budget. Defaults that keep spend down:
auto-compaction is ON (compresses history past ~48k tokens), older tool outputs are
excerpted mid-turn, and the stable part of the system prompt is kept byte-identical so
providers can cache it. If a single task still balloons: delegate the noisy part to a
sub-agent (clean context) or to Kimi, and keep the assistant's enabled-tool list lean —
every enabled tool category adds its schema to every request.

## 17. Common "how do I…"

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
