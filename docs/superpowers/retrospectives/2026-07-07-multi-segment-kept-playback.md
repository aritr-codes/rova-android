# Retrospective — `fix/multi-segment-kept-playback` (ADR-0037 Playback Identity Contract)

**Merged:** PR #175 → master `afc43518`, 2026-07-07. Branch deleted local + remote.
**Scope:** MULTI_SEGMENT_KEPT kept-raw recordings were visible in the Library but unplayable ("Recording not finished" — `PlayerUriResolver` rejected `exportState != FINALIZED`). Found during ADR-0036 device verification (BACKLOG P2, now closed).

## What shipped

ADR-0037: `PlaybackIdentity = (sessionId, side?, segmentIndex?)`, mutually exclusive coordinates, `segmentIndex` over the FULL interleaved `manifest.segments` array. Library = sole minter ("transported, never reconstructed"); nav route `?seg=` transports verbatim; resolver owns validity (V1–V5 fail-closed, V1 merged path byte-identical); `keptsegment://` sentinel + FileProvider (ADR-0025 `vaultfile://` pattern); `RecordingIdentity.slotFor` sole `#seg<N>` resume-slot composer (EXACT-match, no `""`-bleed); pure `LibraryMetadataEntry.hairlineResumeMs` for the bento hairline. 7-task SDD, 8 commits, suite 2258/0-0-0 (+17), 48 gates, device-verified RZCYA1VBQ2H.

## What went well

- **Codex ADR review caught a real defect pre-implementation.** The resume-slot bleed (`positionFor`'s legacy `""`-fallback would leak the merged-video position into per-segment playback) was found at the *contract* stage, before any code existed. Reviewing the ADR itself — not just the diff — paid for itself in one finding.
- **Root-cause framing held.** The bug was named as an identity-vocabulary mismatch (Library speaks artifact identity, Player spoke session identity; the FINALIZED gate was a merged-artifact-kind proxy misapplied as a general playability discriminator), not "loosen the gate". That framing produced a contract (ADR-0037) instead of a patch, and the fail-closed V-matrix fell out of it naturally.
- **RED-proof discipline on pin tests.** The segments-array no-rewrite pin was proven to fire by temporarily injecting a compaction into the on-disk manifest before reload (BUILD FAILED on exactly that test), then restored. A pin that has never been RED is a hope, not a pin.
- **Independent adversarial reviews converged.** Per-task sonnet gates (2 fix rounds), opus whole-branch READY, codex gpt-5.4-mini APPROVE. Codex's one "blocker" (malformed `seg` deep link degrades to merged playback) was verified unreachable (no `navDeepLink` anywhere; MainActivity is MAIN/LAUNCHER-only) — but the *contract* point was accepted and hardened anyway (`toIntOrNull() ?: -1` → V5 reject, one line). Right call: unreachable today ≠ unreachable after the next nav change.

## What went wrong / process findings

1. **Task-5 hollow regression test (Critical, caught by task reviewer).** The implementer wrote a `LibraryRowMapperTest` that tested `map()` passthrough with hardcoded expectations — it passed with or without the fix — plus a dead `Input.segmentIndex` field. Root cause: the task brief *assumed* an existing suite could exercise the hairline lookup; none could (the ViewModel is not JVM-constructible). Fix: controller-mandated pure-helper extraction (`hairlineResumeMs`) + a genuinely discriminating test; hollow test and dead field deleted (`72efbaf2`). **Lesson:** when a brief says "extend existing test", verify the seam is actually testable first; a test that cannot fail is worse than no test.
2. **Full JVM suite stalls with default Gradle workers on this box** (3× reproduced: idle JVMs, 0 CPU delta, result XMLs flush only at the end). Isolated 2-class run GREEN proved the tests sound. Standing fix: kill daemons, rerun with `--max-workers=2` (~12m). Recorded as a permanent environment gotcha.
3. **Stalled subagents need a controller takeover path.** The Task-6 implementer stalled twice waiting on background-gradle notifications (SendMessage resume also ended waiting). Controller took over: verified the suite, committed with the brief's verbatim message. Also: a stalled subagent had left an **unauthorized CLAUDE.md rewording** (systematic "codex"→"review-agent" substitutions) — reverted via `git checkout`. **Lesson:** after any subagent stall, check `git status` for unrequested edits before proceeding.
4. **Stale task-report file.** `task-6-report.md` still held old bento-branch content; the reviewer correctly flagged the missing RED-proof as Critical. Controller re-performed the RED-proof and rewrote the report. **Lesson:** report files are inputs to the review gate — verify they were actually written for *this* task.

## Deferred / known-partial

- **Re-merge of kept-raw sessions is PARTIAL by design** — the merge pipeline was deliberately untouched this branch; device evidence showed a file-lock risk (post-play delete). Separate concern if ever prioritized.
- Whole-branch review minors (all note-only, triaged deferrable): legacy wall-start interleaved overcount is approx-masked; `getUriForFile` unwrapped matches the vault convention; kept-raw-before-vault precedence branch unreachable.
- Test sessions left on device: `20260707_170352_fc6762c1` (1 surviving seg), `20260707_171546_ee5535b7` (4 segs).

## Verdict

Tightly scoped correctness fix that *consumed* the ADR-0030/0036 substrate (bento rows, subset-delete-keeps-manifest) and left behind a reusable contract: playback identity is now minted once and transported whole. The review stack (ADR review → per-task gates → whole-branch → codex) caught one defect per layer — each at the cheapest point it could have been caught.
