---
name: prompt-shaping-guide
description: Decision guide for the prompt-shaping features - when to use memory vs skills vs lorebooks vs mode injections vs quick messages vs preset messages vs regex rules, and which tool creates each. Consult when the user shares reference material, wants a recurring behavior, or asks which feature fits.
---

# Prompt-shaping decision guide

Pick the right container for information or behavior. Wrong container = wasted tokens or
missed context.

| The thing | Right container | Why | Create with |
|---|---|---|---|
| Fact about the user or ongoing work | **Memory** (profile/preference/note) | Injected every turn, small | memory_tool |
| Reusable procedure ("how to do X") | **Skill** | Loaded only on demand | skill_manage |
| Large reference set used occasionally (characters, glossary, project docs) | **Lorebook** | Entries load ONLY when a keyword appears — near-zero idle cost | manage_prompt_shaping add_lorebook |
| Always-on behavior rule ("answer tersely", "STAY in character") | **Mode injection** | Forced into the prompt every turn while attached | manage_prompt_shaping add_mode_injection |
| Text the USER types often | **Quick message** | One-tap insert into the input box; costs nothing until used | manage_prompt_shaping add_quick_message |
| Persona/identity | **Assistant system prompt** (or auto_load skill) | The stable core of the prompt | set via assistant settings / create_assistant |

Heuristics:
- Bigger than ~20 lines and only sometimes relevant → lorebook, not memory or system prompt.
- Needed EVERY turn → mode injection or memory, never a skill.
- A procedure with steps → skill; a fact → memory.
- If the user pastes reference material and says "use this from now on": lorebook
  (keyword-triggered) if it's topical, memory note if it's one small fact.

Related features you don't create but should understand (see read_app_docs "prompt
shaping" for full explanations): preset messages (fake prior chat turns that prime
style), message templates ({{ message }} wrappers), regex rules (find/replace on
messages), conversation-level system prompt overrides, and custom headers/bodies
(per-request API extras).
