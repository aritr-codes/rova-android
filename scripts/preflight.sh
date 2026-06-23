#!/usr/bin/env bash
# preflight.sh — read-only git hygiene scan for Rova (POSIX/CI sibling of preflight.ps1).
#
# REPORT-ONLY. Never commits, stashes, discards, or deletes. Exits 0 always.
# Suggested fix commands are PRINTED for you to run manually.
#
# Whitelists untracked `gradle_*.log` (intentional build noise per CLAUDE.md).
#
# Usage (from repo root):  bash scripts/preflight.sh
# Convention: run at the START of each task; resolve every FLAG before branching.

set -u
base='origin/master'

section() { printf '\n=== %s ===\n' "$1"; }
flag()    { printf '  [FLAG] %s\n' "$1"; }
ok()      { printf '  [ok]   %s\n' "$1"; }

git fetch --quiet origin 2>/dev/null || true

branch=$(git rev-parse --abbrev-ref HEAD)

section "Branch"
if [ "$branch" = "master" ]; then
    flag "On master. Branch before new work:  git switch -c feat/<name>"
else
    ok "On $branch"
fi

section "Working tree"
dirty=$(git status --porcelain | grep -v 'gradle_.*\.log$' || true)
if [ -n "$dirty" ]; then
    flag "Uncommitted changes (NOT auto-handled — decide commit/stash/discard):"
    printf '%s\n' "$dirty" | sed 's/^/         /'
else
    ok "Clean (whitelisted gradle_*.log ignored)"
fi
log_count=$(git status --porcelain | grep -c 'gradle_.*\.log$' || true)
[ "$log_count" -gt 0 ] && ok "$log_count gradle_*.log (intentional noise, ignored)"

section "Sync vs $base"
read -r behind ahead < <(git rev-list --left-right --count "$base...HEAD")
if [ "$behind" -eq 0 ] && [ "$ahead" -eq 0 ]; then
    ok "In sync with $base"
elif [ "$ahead" -gt 0 ] && [ "$behind" -eq 0 ]; then
    flag "$ahead local commit(s) unpushed.  Suggest:  git push"
elif [ "$behind" -gt 0 ] && [ "$ahead" -eq 0 ]; then
    flag "$behind commit(s) behind.  Suggest:  git pull --ff-only"
else
    flag "DIVERGED ($behind behind / $ahead ahead) — ff-only will fail."
    printf '         Suggest:  git stash (if dirty) ; git rebase %s ; git stash pop\n' "$base"
fi

section "Stale local branches (merged into $base)"
stale=$(git branch --merged "$base" | sed 's/^[* ]*//' | grep -vx 'master' || true)
if [ -n "$stale" ]; then
    flag "Merged branches safe to delete:"
    printf '%s\n' "$stale" | sed 's/^/         git branch -d /'
else
    ok "None"
fi

section "Worktrees"
git worktree list | sed 's/^/  /'
[ "$(git worktree list | wc -l)" -gt 1 ] && \
    flag "Extra worktree(s) — confirm intentional (stale: git worktree remove <path>)"

printf '\npreflight: report only — nothing was changed.\n'
exit 0
