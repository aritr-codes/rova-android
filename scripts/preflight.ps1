# preflight.ps1 — read-only git hygiene scan for Rova.
#
# REPORT-ONLY. Never commits, stashes, discards, or deletes anything.
# Exits 0 always (it is a report, not a gate). Suggested fix commands are
# PRINTED for you to run manually — the script never mutates repo state.
#
# Whitelists untracked `gradle_*.log` (intentional ephemeral build noise per
# CLAUDE.md "House conventions") so they never show as dirty.
#
# Usage (from repo root):  pwsh scripts/preflight.ps1
#
# Convention: run at the START of each task. Resolve every FLAG before
# branching for new work.

$ErrorActionPreference = 'Stop'
$base = 'origin/master'

function Section($t) { Write-Host "`n=== $t ===" -ForegroundColor Cyan }
function Flag($t)    { Write-Host "  [FLAG] $t" -ForegroundColor Yellow }
function Ok($t)      { Write-Host "  [ok]   $t" -ForegroundColor Green }

# Make sure remote ref is current (read-only).
git fetch --quiet origin 2>$null

$branch = (git rev-parse --abbrev-ref HEAD).Trim()

Section "Branch"
if ($branch -eq 'master') {
    Flag "On master. Branch before starting new work:  git switch -c feat/<name>"
} else {
    Ok "On $branch"
}

Section "Working tree"
# Porcelain lines, minus whitelisted gradle_*.log noise.
$dirty = git status --porcelain | Where-Object { $_ -notmatch 'gradle_.*\.log$' }
if ($dirty) {
    Flag "Uncommitted changes present (NOT auto-handled — decide commit/stash/discard):"
    $dirty | ForEach-Object { Write-Host "         $_" }
} else {
    Ok "Clean (whitelisted gradle_*.log ignored)"
}
$logCount = (git status --porcelain | Where-Object { $_ -match 'gradle_.*\.log$' }).Count
if ($logCount -gt 0) { Ok "$logCount gradle_*.log (intentional noise, ignored)" }

Section "Sync vs $base"
$counts = (git rev-list --left-right --count "$base...HEAD") -split '\s+'
$behind = [int]$counts[0]; $ahead = [int]$counts[1]
if ($behind -eq 0 -and $ahead -eq 0) {
    Ok "In sync with $base"
} elseif ($ahead -gt 0 -and $behind -eq 0) {
    Flag "$ahead local commit(s) unpushed.  Suggest:  git push"
} elseif ($behind -gt 0 -and $ahead -eq 0) {
    Flag "$behind commit(s) behind.  Suggest:  git pull --ff-only"
} else {
    Flag "DIVERGED ($behind behind / $ahead ahead) — ff-only will fail."
    Write-Host "         Suggest:  git stash (if dirty) ; git rebase $base ; git stash pop"
}

Section "Stale local branches (merged into $base)"
$stale = git branch --merged $base | ForEach-Object { $_.TrimStart('* ').Trim() } |
    Where-Object { $_ -and $_ -ne 'master' }
if ($stale) {
    Flag "Merged branches safe to delete:"
    $stale | ForEach-Object { Write-Host "         git branch -d $_" }
} else {
    Ok "None"
}

Section "Worktrees"
$wt = git worktree list
$wt | ForEach-Object { Write-Host "  $_" }
if (($wt | Measure-Object).Count -gt 1) {
    Flag "Extra worktree(s) present — confirm intentional (stale ones: git worktree remove <path>)"
}

Write-Host "`npreflight: report only — nothing was changed." -ForegroundColor DarkGray
exit 0
