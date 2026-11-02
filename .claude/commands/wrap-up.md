---
description: End of a session or story. Turns what was corrected and decided into rules, CLAUDE.md facts and decision-log lines (only the ones you approve), records leftover side issues, refreshes the codebase map
---

Wrap up this session. Nothing is written without the user's yes.

1. **Collect** from this session only (the conversation, and this session's file under `.claude/compact-checkpoints/` if it was compacted):
   - corrections the user gave you or a worker, and anything they had to say twice;
   - build, SDK or tooling quirks you hit, and what fixed them;
   - decisions with a reason (why X over Y);
   - side issues that came up but weren't done.

2. **Sort** each into one place:
   - a convention or guardrail every session and worker should follow → a line in the matching `.claude/rules/*.md` (one concern per line; extend an existing line rather than adding a near-duplicate). If Codex should follow it too, mirror it in `AGENTS.md`;
   - a project fact (command, module, quirk, environment detail) → CLAUDE.md, in Commands or the STACK block; keep the file under 200 lines;
   - a decision → PROGRESS.md decision log: `- YYYY-MM-DD: X over Y, because …`;
   - a side issue → PROGRESS.md "Open issues", recorded and not worked;
   - a personal preference that isn't about this project → auto memory; if it wasn't saved, the user can say "remember that …";
   - a one-off → drop it.
   Skip anything the code, git history or an existing rule already says.

3. **Propose** a numbered list, most valuable first: where it goes, then the exact line. Wait for the user to pick.

4. **Apply** only the picked items. If integrated code changed module structure, entry points or patterns this session, update the affected pages under `.claude/.codebase-info/` (a Sonnet subagent can do it from the diff).

5. **Report** what changed, briefly. If CLAUDE.md or the rules haven't been audited in a few weeks, suggest a prompt audit for stale or contradicting instructions.
