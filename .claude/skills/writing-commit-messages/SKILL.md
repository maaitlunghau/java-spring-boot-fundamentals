---
name: writing-commit-messages
description: Use when creating a git commit in this project - covers checking repo history before committing, conventional commit message format enforced by commit-msg hook, and what to never include
---

# Writing Commit Messages

## Overview

Commits in this project are validated by a Husky `commit-msg` hook (`.husky/commit-msg`, bash — no commitlint package installed, the hook itself is the enforcement). Messages must be short, single-line, all lowercase, and follow the type(scope) format. Never add co-author trailers — the hook rejects any commit with more than one non-blank line, so a trailer makes the commit fail outright.

## Before Committing — Always

Run these first, every time:

```bash
git status              # what's staged/unstaged/untracked
git diff                # unstaged changes
git diff --staged       # staged changes
git log --oneline -10   # recent commit style, scopes used
```

Use `git log` to match scopes/wording conventions already used in the project (e.g. `feat(bank): ...`, `fix(admin): ...`).

## Message Format

```
type(scope): subject
```

- One line only. No body, no footer, no blank-line-separated paragraphs — the hook counts non-blank lines and rejects anything but exactly 1.
- `scope` is optional but use one if recent commits in that area use one (check `git log`).

### Rules (enforced by `.husky/commit-msg`)

- `type` must be one of: `feat fix docs style refactor perf test chore revert ci`
- `scope`, if present, lowercase alphanumeric/hyphen only
- entire subject line must be lowercase — no uppercase letters anywhere, not even mid-word
- whole header (the single line) must be **≤ 60 characters**
- no body, no footer, no blank-line-separated paragraphs — one line, period
- no trailing period on the subject

These are hard limits — the commit is rejected, not just linted.

### Type meanings

* `feat`: new feature
* `fix`: bug fix
* `docs`: documentation changes
* `style`: code formatting changes that do not affect logic
* `refactor`: code refactoring that is neither a feature nor a bug fix
* `perf`: performance improvements
* `test`: adding or updating tests
* `chore`: build process, tooling, or dependency changes
* `revert`: reverting a previous commit
* `ci`: CI/CD configuration changes


## Never Do This

- **Never** add a `Co-Authored-By:` line or any "Generated with Claude" trailer.
- Never write a multi-line commit body — keep it to one line.
- Never use uppercase anywhere in the message — `type`, `scope`, and `subject` are all lowercase, start to end.
- Never end the subject with a period.
- Never exceed 60 characters total.

## If Unclear

If you're not sure what type/scope fits, or the change mixes multiple concerns (e.g. both a fix and a refactor), stop and ask the user how they want it framed/split — don't guess.

## Example

```
fix(bank): correct balance calculation for topup
feat(admin): add export button to transaction list
chore: bump laravel to 12.5
```