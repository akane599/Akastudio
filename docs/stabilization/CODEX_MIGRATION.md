# Codex project migration

Date: 2026-10-26. Scope: project instructions, preserving the Claude Code setup and operational assets. Current runtime, machine ownership, check results and remaining work live in [HANDOVER.md](HANDOVER.md).

## Result and source boundary

[Root AGENTS.md](../../AGENTS.md) is the concise Codex entry point. It preserves project architecture, Kotlin/Compose conventions, behavior verification, Gradle log/exit handling, resource coordination and shared-device safeguards. It replaces source-specific orchestration with the requested GPT-6.1 Sol/high policy for main, implementation and review. xhigh is reserved for a critical unresolved question backed by explicit evidence. Sidequest remains OFF in Codex instructions.

The source instructions were reviewed manually: `CLAUDE.md`, all four `.claude/live-rules/rules/` files, the codebase map and the Gradle helper. Provider-specific routing, compulsory ticket workflows, slash-command invocation and automatic rule injection were not carried into `AGENTS.md`. Relevant neutral conventions are stated directly there. Existing map files remain useful factual references, but their older workflow and machine claims must be checked against the handover and the current environment.

| Source surface | Treatment |
| --- | --- |
| `CLAUDE.md`, `.claude/live-rules/` | Preserved as source; neutral project rules manually adapted into `AGENTS.md` |
| `.claude/.codebase-info/`, `PROGRESS.md` | Referenced for architecture and decisions; no bulk copies |
| `.claude/kit/gradle-check.sh`, `resource-policy.py`, Android bug checklist | Reused by path; no helper/installer rewrite |
| `.claude/settings.json`, local/global config, hooks and MCP configuration | Preserved; no conversion or installation |
| 3 project skills, 12 project commands | Inventoried only; no bulk skill/slash migration |
| Project subagents | None detected by the source scan; no agent configuration generated |
| Plugins, marketplaces, kit and installer | Preserved; no installation or update |

No `.codex/`, `.agents/`, hook, agent, skill or MCP artifacts were generated as part of this instruction adaptation. The requested role policy is guidance, not proof that a process selected a model; actual runtime route evidence belongs in the handover.

## Discovery and validation

The installed `migrate-to-codex` skill and `references/differences.md` were read. Its read-only source scan completed successfully and reported `CLAUDE.md`, 3 project skills (`audit-remediate`, `compose-design`, `full-repo-audit`), 12 commands and no project subagents. The CLI has no instructions-only selector, so its broad write/replace flow was deliberately outside the selected scope. The two Markdown artifacts were reviewed directly for source paths, migrated policy, secret-free content and unwanted source-specific routing; no Gradle/device check is needed to establish their instruction semantics.

The migration reference was dated 2026-04-20. The [official AGENTS.md guidance](https://learn.chatgpt.com/docs/agent-configuration/agents-md) was refreshed on 2026-10-26: Codex discovers global/project instructions in precedence order at session start, and the project document budget defaults to 32 KiB. This project uses a small regular root file and references existing detailed artifacts. A later user/client instruction turn supplied this project AGENTS.md body as the active project rules after an accidental interruption, confirming current-session instruction delivery. A separate fresh CLI launch was not performed; direct reading alone would not prove startup discovery.

Installed files were distinguished from session-exposed skills. Both `~/.codex/skills/grill-me/SKILL.md` and `grilling/SKILL.md` exist: `grill-me` is a compatibility wrapper pointing to `grilling`. `handoff/SKILL.md` also exists on disk, although neither `grill-me` nor `handoff` appeared in this session's skill catalog. Their presence does not prove native invocation support. The handoff instructions were read; they require a temporary-directory handoff that references existing artifacts and redacts sensitive data. No compatibility wrapper or skill was installed or rewritten.

## Preservation and limits

The initial workspace included a modified `.claude/settings.json` and untracked `.claude/worktrees/`, `.serena/`, `AUDIT_REPORT.md`, and a project audit-script `__pycache__/` directory. These were outside this instruction migration's ownership. No pull, branch change, reset, clean, stash or source/global configuration mutation was performed for this work.

The preserved Claude hooks and live-rule injection have not become Codex runtime enforcement. `AGENTS.md` carries the relevant behavioral rules, while Gradle capacity remains advisory and device access still relies on the real owner-host lock. No screenshot reference rewrite, test deletion/disablement, dependency/toolchain upgrade, Gradle execution or device operation was performed by this instruction migration. Overall product verification is recorded separately in the handover.
