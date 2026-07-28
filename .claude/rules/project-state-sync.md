# Keeping /resume Project State in Sync

`.claude/skills/resume/PROJECT_STATE.md` is the persisted context the `/resume` skill loads so Claude doesn't have to re-analyze this repo from scratch each session.

After doing any of the following in this repo, update the relevant section of that file directly — don't wait to be asked:

- Shipping or reverting a fix in project 09's auth flow (login/register/CSRF/session handling)
- Adding, removing, or restructuring a sub-project under `projects/`
- Resolving, or newly discovering, an item in that file's Known Issues / architecture-smell / TODO lists
- Anything that would make a future `/resume` hand out stale advice

Update the `Last synced commit` line at the top of `PROJECT_STATE.md` to the current `git rev-parse HEAD` each time you touch it. Keep edits surgical — patch the specific section that changed, don't regenerate the whole file.
