---
name: kimi-delegate
description: Hand a complex task to Kimi Code CLI running in Termux, with a proper context briefing (goal, constraints, relevant memories/skills, working directory) and a structured hand-back. Use for heavy coding, repo-wide work, or long build/test loops where Kimi's CLI environment beats in-app tools.
---

# Delegate to Kimi Code CLI

A two-way bridge: Omnitrix owns the conversation, memory, and device tools; Kimi owns the
repo/toolchain work. You write the brief, Kimi executes, you verify and report.

## Preconditions (check once, then remember)

1. Termux tool category enabled, `allow-external-apps=true` in `~/.termux/termux.properties`.
2. Kimi CLI installed and logged in inside Termux. Verify cheaply:
   `termux_run_command(command="command -v kimi && kimi --version")`
   If missing, tell the user how to install it in Termux — do not attempt an install yourself.
3. Store the outcome as a memory note ("kimi CLI available at ~/…, version X") so future
   sessions skip discovery.

## When to delegate

Delegate: multi-file code changes, repo-wide refactors, dependency/build debugging,
test-fix loops, anything needing git or a language toolchain.
Do NOT delegate: device actions, memory/skill/config changes, anything needing the user's
approval cards, or a task you can finish in one or two in-app tool calls.

## The brief (this is the whole trick)

Kimi starts with ZERO knowledge of this conversation. Write a self-contained brief to a
file, then point Kimi at it — far more reliable than a giant `-p` string:

    write_text_file(path="/data/data/com.termux/files/home/.omnitrix/brief.md", content="""
    # Goal
    <one sentence>

    # Repo / working directory
    <absolute path>

    # Constraints
    - <build command that must pass>
    - <style/architecture rules the user cares about>
    - Do not commit or push unless explicitly told.

    # Context you need
    <paste ONLY the relevant facts: file paths, error text, prior decisions from memory>

    # Definition of done
    <how Kimi knows it finished; e.g. "./gradlew assembleDebug passes">
    """)

Then run it, capturing output so long runs stay pollable:

    termux_run_command(command="cd <repo> && kimi -p \"$(cat ~/.omnitrix/brief.md)\" > ~/.omnitrix/kimi-out.txt 2>&1; tail -c 4000 ~/.omnitrix/kimi-out.txt")

For long work, start a session instead and poll:
`termux_session_start` → then `tail -c 2000 ~/.omnitrix/kimi-out.txt` every so often.

Relevant skills: if a local skill covers part of the task, inline the useful steps into the
brief (Kimi cannot read this app's skill files).

## Hand-back

1. Read the tail of the output file; if Kimi asks a question, relay it with `ask_user` —
   never invent an answer on the user's behalf.
2. Verify the definition-of-done yourself (run the build/test command via Termux).
3. Report to the user: what changed, what passed, what's left. Do not dump raw logs.
4. Save durable learnings (working flags, gotchas) with `skill_manage(action="patch")`
   into THIS skill so the next delegation is smoother.

## Cost note

Kimi's CLI bills against the user's Kimi account, not the app's provider — delegating
heavy work here is usually cheaper than looping in-app. Say so when it's relevant.
