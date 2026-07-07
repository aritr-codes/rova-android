# ADR-0037: Playback identity contract

**Status:** Accepted (2026-07-07) — owner-approved; codex peer review (gpt-5.4-mini, 2026-07-07) APPROVE-WITH-CHANGES, all findings reconciled into this text (mutually exclusive coordinates; exact-match segment-slot lookup; append-only-at-tail stability wording)

## Context

Device verification of ADR-0036 (PR #174) surfaced a pre-existing playback
defect: `MULTI_SEGMENT_KEPT` sessions appear in the Library as per-segment
rows — thumbnailed, deletable, retention-managed — but tapping one shows
"Recording not finished." The bytes are valid, sha1-recorded, closed files
on disk (`videos/<sessionId>/segment_NNNN[_P|L].mp4`).

The visible cause is `PlayerUriResolver.resolve`, which rejects any manifest
whose `exportState != FINALIZED`. That gate protects a real invariant —
**never hand ExoPlayer an artifact that is missing, incomplete, or still
being written** — but it enforces the invariant through a proxy that only
describes ONE artifact kind: the merged, tier-published session artifact.
A `MULTI_SEGMENT_KEPT` session legitimately has no such artifact (the merge
never ran, or was classified unmergeable per ADR-0018), yet each of its
segment files independently satisfies the safety property: the session is
terminal (no future writer), each file was closed by the muxer at its
segment boundary, and each is addressable on disk.

The deeper defect is an **identity disagreement between the Library and the
Player**:

- The Library operates on **artifact identity**. `HistoryArtifactMapper`
  fans a `MULTI_SEGMENT_KEPT` session into one row per segment
  (`PerSegmentArtifact(sessionId, segmentIndex, …)`), and a DualShot session
  into one row per side. A Library row names one playable artifact.
- The Player operates on **session identity**. The nav route is
  `player/{sessionId}?side={side}`; `PlayerViewModel` receives
  `(sessionId, side)` and nothing else. A kept-raw row's `segmentIndex` is
  dropped at the navigation boundary — the Player structurally cannot
  address the artifact the user tapped.

The same mismatch poisons resume identity. Resume positions (ADR-0030-era
seam, PR #137; consumed by the PR #173 playback-progress hairline) are
stored in `positionsBySide` keyed by `RecordingIdentity.sideSlot(side)`
(`""` for single-mode, side name for P+L). All N kept-raw rows of a session
would share one slot: positions would bleed between distinct clips and the
hairline would render untruthfully — a direct regression against PR #173's
"UI truthfully represents playback" contract.

This ADR fixes the contract, not just the symptom. It defines what uniquely
identifies something that can be played, who owns that identity, and how
resume identity derives from it. It deliberately does **not** introduce a
`PlayableArtifact` model or a broader playback refactor (owner scope
decision, 2026-07-07): the smallest change that completely repairs the
contract is to extend the existing identity tuple and derive everything
else from it.

## Decision

> **Playback is driven entirely by artifact identity. Every independently
> playable artifact owns exactly one playback identity (§1), exactly one
> resolution path (§5), and exactly one resume identity (§4). The three are
> one contract: the identity names the artifact, resolution turns that name
> into a media source, and resume state is keyed by that same name — an
> artifact that cannot satisfy all three does not participate in playback.**

### 1. Playback identity

> **Playback consumes an identity that uniquely identifies one playable
> artifact. The Player never derives identity from storage assumptions.**

The playback identity is the tuple:

```
PlaybackIdentity := (sessionId, side?, segmentIndex?)      side ⊕ segmentIndex — mutually exclusive
```

- **`sessionId`** (required) — the canonical anchor, per the
  sessionId-canonical identity seam (`RecordingIdentity`, codex-reviewed
  2026-06-22). Names the session whose manifest resolution consults.
- **`side`** (optional, merged artifacts only) — selects the per-side
  artifact of a DualShot **merged** session (`VideoSide.PORTRAIT` /
  `LANDSCAPE`). Absent for single-mode. Already threaded today via the nav
  route.
- **`segmentIndex`** (optional, kept-raw artifacts only) — selects one
  kept-raw segment artifact of a `MULTI_SEGMENT_KEPT` session. Defined as
  the **index into the persisted `manifest.segments` array** — exactly the
  value `HistoryArtifactMapper.resolveArtifactsPerSegment` already emits
  (`mapIndexed`, HistoryArtifactMapper.kt:238). Stability invariant (codex
  re-review 2026-07-07): the array is **append-only at the tail and never
  reordered or compacted** — the recovery scanner may append validated
  orphan segments to any non-`COMPLETED` manifest (`RecoveryScanner`
  append gate + `SessionStore.appendSegment`, tail append), and ADR-0036
  subset deletes keep the manifest without touching the segments array. An
  existing index therefore never re-points at a different record; new tail
  records simply become new rows on the next fan-out. (Implementation pins
  this weaker invariant — no reorder/compaction — with a test.)

**The two coordinates are mutually exclusive** (codex review 2026-07-07).
`side` is a coordinate of the *merged* fan-out only. For kept-raw rows the
index is into the full interleaved segments list (a DualShot session's P
and L records share one array), so `segmentIndex` alone is already unique
across sides; the segment's own side is a persisted fact of its
`SegmentRecord` (`SegmentRecord.side`), derived at resolution — carrying it
in the identity would be redundant state that can only disagree with the
manifest. An identity carrying both coordinates is malformed (V4b, §5).

This is the **minimal complete identity**: every playable artifact the
Library can list today is uniquely named by it, and no smaller tuple names
them all —

| Artifact kind | Identity |
|---|---|
| Single-mode merged | `(sessionId)` |
| DualShot merged, one side | `(sessionId, side)` |
| Kept-raw segment (single-mode or DualShot) | `(sessionId, segmentIndex)` |

No new type is introduced. The tuple travels as the three existing values
(nav-route arguments → `PlayerViewModel` factory → resolver parameters),
exactly as `side` travels today. Introducing a data class for it is a
permitted future refactor, not a requirement of this contract.

### 2. Stable identity vs implementation detail

**Stable (identity):** `sessionId`, `side`, `segmentIndex`. All three are
persisted manifest facts (`sessionId`; `SegmentRecord.side`; the segment's
position in `manifest.segments`). They survive export-tier differences,
vault moves, SAF re-targeting, and app reinstall-with-restore.

**Implementation detail (never identity):** everything resolution-time —
export tier, `pendingUri` / `publicTargetPath` / `safTargetDocUri` /
`vaultFilePath`, vault state, `exportState`, file paths, URI schemes. A
caller that encodes any of these into "what to play" is violating the
contract (this is precisely the pre-#137 drift bug class the
sessionId-canonical seam eliminated; this ADR extends the same rule to the
artifact coordinate).

**One deliberate instability:** a successful manual re-merge (ADR-0018
`CANT_MERGE` retry) transitions a session from kept-raw to merged. The
per-segment identities cease to exist (their rows disappear from the
Library; the merged row appears). This is correct — identities name
artifacts, and the artifacts themselves are consumed by the merge. Resume
slots recorded under dead segment identities become inert orphans (a few
bytes in the sidecar; see §4).

### 3. Ownership boundaries

> **Invariant (transport, never reconstruct): no subsystem other than the
> Library may synthesize a playback identity.** The Library owns artifact
> fan-out and is the sole minting authority. Every other subsystem —
> navigation routes, ViewModels, deep links, share/export surfaces —
> **transports** identities verbatim; the resolver **resolves** them; the
> Player **consumes** them. A component that rebuilds an identity from
> partial knowledge (a file path, a URI, a "current session" guess) is
> violating this ADR, whatever its intentions — that reconstruction is
> precisely the storage-assumption drift this contract exists to prevent.

- **The Library mints playback identity.** `HistoryViewModel` /
  `HistoryArtifactMapper` are the only components that know the
  session→artifact fan-out (per-side, per-segment). A Library row carries
  the complete identity of its artifact, and navigation passes it
  **verbatim and complete** to the Player. The Player never reconstructs,
  guesses, or defaults any coordinate of it.
- **`PlayerUriResolver` owns playback resolution.** It is the single pure
  function from `(manifest, side, segmentIndex)` to a playable media source
  (`PlayerUiState`), including all validity judgment (§5) and all
  storage-kind dispatch (tier, vault, SAF, kept-raw file). No other
  component maps identity → URI. The existing sentinel-scheme pattern
  (ADR-0025 `vaultfile://`) is the precedent for app-private files that
  need a `FileProvider` round-trip in the Android wrapper; kept-raw
  segments live under the same app-private `videos/` root and use the same
  mechanism.
- **`RecordingIdentity` owns resume identity.** The resume slot is a pure
  function of the playback identity (§4). No caller composes slot strings
  by hand. The app-scope resume seam (`RovaApp.readResumePosition` /
  `writeResumePosition`) and the Library's hairline lookup
  (`HistoryViewModel`) today accept only `side`; they extend to accept the
  full artifact coordinate and delegate slot derivation to
  `RecordingIdentity.slotFor` — a caller passing a hand-built slot string
  is a contract violation.

### 4. Resume identity

> **Every independently playable artifact owns an independent resume
> position. No playback state bleeds between distinct playable artifacts.**

The resume slot inside a session's metadata entry is derived from the
playback identity's artifact coordinate:

```
slotFor(side, segmentIndex) :=
    segmentIndex == null → sideSlot(side)          // "" | "PORTRAIT" | "LANDSCAPE"  (unchanged)
    segmentIndex != null → "#seg" + segmentIndex   // "#seg3"  (side never applies — §1 exclusivity)
```

Properties:

- **Injective over playback identities within a session** — distinct
  artifacts, distinct slots (`segmentIndex` is unique across the full
  segments array, sides included). This is the invariant; the string shape
  is an implementation detail behind `RecordingIdentity.slotFor`.
- **Byte-identical back-compat** — every existing slot key (`""`, side
  names) is produced unchanged for merged artifacts. No sidecar migration,
  no dual-read.
- **Collision-free by construction** — `#` cannot occur in a `VideoSide`
  name, so no legacy value can equal a segment slot.
- **Exact-match lookup for segment slots** (codex review 2026-07-07,
  BLOCKING finding). `LibraryMetadataEntry.positionFor` grace-falls any
  non-empty slot back to the `""` slot (a P+L side inheriting a pre-seam
  single-slot position). That fallback MUST NOT apply to segment slots: a
  kept-raw clip inheriting the session-level position is exactly the
  cross-artifact bleed this contract forbids. Segment slots (`#seg…`)
  resolve by exact match only — absent means absent.
- The PR #173 hairline reads the same slots (`positionsBySide[slot]`), so
  per-tile progress on kept-raw rows becomes truthful automatically once
  the Library-side lookup uses `slotFor` with the row's `segmentIndex`.
- Orphaned segment slots after a re-merge (§2) are tolerated, not migrated:
  they are unreachable (no identity maps to them), cost bytes only, and the
  existing durable-prune path may collect them later. Migrating segment
  positions into a merged-timeline position is explicitly out of scope
  (wrong semantics: a per-clip offset is not a merged-timeline offset).

### 5. Validity — the resolver's judgment

`PlayerUriResolver.resolve(manifest, side, segmentIndex)` accepts exactly
two artifact-kind shapes and **fails closed** on everything else
(`PlayerUiState.Unavailable`; never a fallback guess):

| # | Identity shape | Manifest state | Verdict |
|---|---|---|---|
| V1 | `segmentIndex == null` | `exportState == FINALIZED` | Resolve merged artifact — **byte-identical to today** (vault precedence, tier dispatch, per-side pointers, segment-timeline math) |
| V2 | `segmentIndex != null`, `side == null` | `terminated == MULTI_SEGMENT_KEPT` and index names an existing `SegmentRecord` | Resolve that segment's file as a single-clip source (segment's own `SegmentRecord.side` is a resolution-time fact, not an input) |
| V3 | `segmentIndex == null` | not FINALIZED | Unavailable ("Recording not finished") — unchanged |
| V4 | `segmentIndex != null` | not `MULTI_SEGMENT_KEPT` (incl. FINALIZED) | **Rejected — invalid playback identity** |
| V4b | `segmentIndex != null && side != null` | any | **Rejected — malformed identity** (coordinates are mutually exclusive, §1) |
| V5 | `segmentIndex != null` | `MULTI_SEGMENT_KEPT` but index out of range | Unavailable (fail closed) |

**Why V4 rejects rather than ignores.** A playback identity names exactly
one artifact; `(sessionId, seg N)` against a FINALIZED session names a
merge *input*, not a published artifact. Post-merge segment files are
transient bytes the cleanup path is entitled to remove
(`ExportCleanupPredicate`) — playing them would resurrect the storage
assumption this ADR forbids, and "ignore the index, play the merged file"
would silently play a *different artifact* than the identity names (minutes
of footage away from the one clip the caller asked for), with the wrong
resume slot. Malformed identities are caller bugs (or stale deep links);
the resolver's precedent is already fail-closed (the P+L null-side
defensive gate: "fail closed rather than coin-flip"). Owner concurrence
2026-07-07.

**Segment resolution shape (V2).** The resolver stays pure JVM: it emits a
sentinel-scheme URI naming the app-private segment file (ADR-0025
`vaultfile://` precedent — same `FileUriExposedException` constraint, same
`FileProvider` detour in `PlayerViewModel.attachExoPlayer`). The
`PlayerUiState.Ready` timeline degenerates to a single clip: `totalClips =
1`, `segmentDurationsMs = [that segment's durationMs]`, wall start from
`SegmentRecord.startedAtWallClock` (exact) or synthesized (approx mask),
consistent with ADR-0032. File-existence failure surfaces through the
existing ExoPlayer error path (and the Library already filters listings to
`exists() && length > 0`), matching the merged path's behavior.

### 6. What "playable" means (contract summary)

A recording is legitimately playable iff its playback identity resolves
under V1 or V2 — i.e. it names a **terminal, muxer-closed, addressable
artifact**:

- merged artifacts prove this via `exportState == FINALIZED` (the merge
  pipeline's own completion proof);
- kept-raw segment artifacts prove it via `terminated ==
  MULTI_SEGMENT_KEPT` plus the manifest's `SegmentRecord` (terminal session
  ⇒ no future writer; segment-boundary muxer stop ⇒ closed file).

`exportState` remains the correct discriminator **for the merged artifact
kind only**. It is not, and never was, a general playability discriminator.

## Consequences

- `MULTI_SEGMENT_KEPT` rows become playable, watchable in-app, with
  truthful per-clip resume and hairline — closing the BACKLOG P2 filed from
  the ADR-0036 device verification.
- The Player's nav route gains an optional `seg` argument; absence
  reproduces today's behavior byte-identically. FINALIZED-session playback,
  vault, SAF, DualShot per-side dispatch: all unchanged (V1 is the existing
  code path).
- Resolution stays read-only and mints no state: no file deletion
  (`checkRecoveryNoDeletion` posture), no terminal writes
  (`checkCompletedWriteOnlyFromPerformMerge` untouched), no `ExportState`
  transitions.
- Future capture modes (e.g. FrontBack PiP, ADR-0029 δ) inherit a contract
  instead of a special case: define the artifact fan-out in the Library,
  extend the identity coordinate if genuinely new, satisfy §6, and the
  resolver dispatches.
- No new static gate. The protection is the pure-resolver validity-matrix
  tests + `RecordingIdentity.slotFor` injectivity/back-compat tests + a
  recovery-path journey test (same posture as ADR-0036's deferred gate).
- Explicitly out of scope: playing a kept-raw session as one continuous
  N-clip timeline (that is what merge is for — the manual re-merge path
  already exists); any `PlayableArtifact` abstraction; resume-slot
  migration on re-merge.
