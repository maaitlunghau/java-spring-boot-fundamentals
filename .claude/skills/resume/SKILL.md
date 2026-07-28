---
name: resume
description: Use when starting a session on this repo (java-spring-ecosystem-fundamentals), or when the user runs /resume, to load already-known project context instead of re-exploring the codebase from scratch.
disable-model-invocation: true
argument-hint: "(optional) what to focus on this session"
---

# resume

## What this does

Loads `.claude/skills/resume/PROJECT_STATE.md` — accumulated knowledge about this repo's structure, sub-project status, known issues, and project 09's auth internals — so you don't have to re-derive it by reading files or running the whole repo-analysis process again.

## Steps

1. Read `.claude/skills/resume/PROJECT_STATE.md` in full.
2. Compare the `Last synced commit` hash at the top of that file against current `git rev-parse HEAD`.
3. If HEAD has moved, run `git log --oneline <last-synced>..HEAD`. For any commits touching areas the state file covers (project 09 auth/CORS/session code, root README, sub-project structure, test files), skim `git show --stat` for those commits and patch just the affected section(s) of `PROJECT_STATE.md` — don't regenerate the whole file. Update the `Last synced commit` line to the new HEAD hash and save.
4. If the user passed arguments to `/resume`, treat them as what this session will focus on and say so explicitly.
5. Reply with a short confirmation of what's loaded and anything that changed since the last sync. Don't paste the whole file back — the user already knows what's in it.

## Keeping it fresh between /resume calls

This skill only runs when invoked — it can't watch for changes on its own. `.claude/rules/project-state-sync.md` (loaded into every session automatically via this project's CLAUDE.md conventions) instructs Claude to patch `PROJECT_STATE.md` directly whenever it ships an auth fix, adds/removes a sub-project, or resolves/discovers a Known Issue — so the next `/resume` is a fast diff, not a full catch-up.
