---
name: cli-agent-bridge
description: Delegate hard or long coding/system tasks to CLI coding agents installed in Termux (Claude Code, Kimi CLI, opencode, aider). Explains headless invocation, output capture, session resume, and when delegating beats doing the work in-app.
---

# CLI agent bridge (Claude Code / Kimi CLI via Termux)

Delegate work to a coding agent installed in Termux when the task is a better fit for a
full CLI environment: multi-file code edits, long builds, repo-wide refactors, anything
needing git + toolchains. Requires the Termux tool category enabled and
`allow-external-apps=true` in `~/.termux/termux.properties`.

## When to delegate vs. do it yourself

- **Delegate**: repository work, multi-file edits, compile/test loops, tasks over ~10 min
  of shell work, anything the CLI agent's own tools (git, language servers) do better.
- **Do it in-app**: quick file reads, one-off commands, device actions, anything needing
  app tools (memory, workflows, MCP servers, device control).

## Claude Code (headless)

Run non-interactively with `-p` (print mode). Always set the working directory and
capture output to a file so you can read results even if the session is long:

    termux_run_command(command="cd ~/projects/myrepo && claude -p 'fix the failing tests in auth module' --output-format text > /data/data/com.termux/files/home/.cli-bridge-out.txt 2>&1; tail -c 4000 ~/.cli-bridge-out.txt")

- Add `--dangerously-skip-permissions` ONLY if the user explicitly allows it.
- Resume the previous session with `claude --continue -p '...'`.
- For long tasks, launch in a Termux session (termux_session_start) and poll the output
  file with `tail` instead of blocking.

## Kimi CLI

Same pattern; Kimi CLI authenticates itself inside Termux (its own login flow), so the
app needs no Kimi API key — delegation gets Kimi's models "for free":

    termux_run_command(command="cd ~/projects/myrepo && kimi -p 'refactor the parser' > ~/.cli-bridge-out.txt 2>&1; tail -c 4000 ~/.cli-bridge-out.txt")

(Confirm the exact flags with `kimi --help` on first use and update this skill via
skill_manage if they differ.)

## Rules

1. Show the user the exact command before running it (the approval card does this).
2. Never pipe secrets into a CLI agent's prompt.
3. Summarize the CLI agent's output back to the user; don't dump raw logs.
4. If the CLI agent asks a question in its output, relay it to the user with ask_user.
5. Record durable lessons (flag changes, working invocations) back into THIS skill
   with skill_manage(action="patch").
