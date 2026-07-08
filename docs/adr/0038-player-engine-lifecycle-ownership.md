# ADR-0038 — PlayerEngine lifecycle ownership (player lifetime decoupled from navigation)

**Status**: Accepted (2026-07-08) — shipped PR #176

## Context
The in-app Player's `ExoPlayer` lived inside a `NavBackStackEntry`-scoped
`PlayerViewModel`. Every `Library→Player` navigation built a fresh player and
every `Player→Library` synchronously `release()`d it on the main thread — the
teardown landing squarely on the pop-transition frame (measured back-nav clear
~31 ms median / ~61 ms max on RZCYA1VBQ2H, contributing to the player→library
transition jank filed in BACKLOG). Player lifetime was coupled to navigation
lifetime with no seam between them.

Media3 1.4.1 constrains the fix: all `ExoPlayer` API is main-thread, `release()`
blocks the application thread, and no `releaseAsync()` exists at any version.

## Decision
Introduce an **app-scoped lifecycle holder** — `PlayerEngine` (+ pure
`PlayerEngineLedger` state machine) — that owns **player lifetime ONLY**:
acquisition, ownership tokens, detach, parking hygiene, and destruction. It owns
**no playback behavior**. The `PlayerViewModel` remains the sole owner of media
item, prepare/`playWhenReady`, listener registration, seeking, resume, speed,
scrub — all feature logic. This boundary is a **standing invariant**: playback
logic must never migrate into the holder; new player behavior routes through the
VM, gated by the lease.

Lifecycle states are explicit and framework-agnostic: `DESTROYED → ACTIVE ⇄
PARKED`. The player **ceases to be reusable** (→ DESTROYED) under three
conditions, not one callback: (1) the host UI is genuinely going away
(activity finishing), (2) platform memory pressure while PARKED, (3) an
unrecoverable engine fault. `MainActivity.onDestroy(isFinishing)` /
`Application.onTrimMemory` are merely today's *mapping* of those conditions, not
the contract.

**Device-driven amendment (RZCYA1VBQ2H, 2026-07-07):** the original design reused
one `ExoPlayer` instance across leases. Rapid `Library→Player→Library→Player`
reproducibly wedged the reused video codec to black output on this Exynos
(`MediaCodec.setOutputSurface` unreliable on the OMX decoder; sticky across
leases; 3/3 repro, pixel-verified). The shipped shape therefore **leases a fresh
player per acquire** on a shared, warm playback `HandlerThread`
(`setPlaybackLooper` + `setLooper(main)`): a fresh codec + fresh surface makes
the wedge structurally impossible, while build stays cheap (~10 ms warm) and
`release()` never joins a thread exit. `park()` does cheap hygiene
(clearVideoSurface→stop→clearMediaItems, ~2 ms) on the nav path and **defers the
expensive `release()` 400 ms** via a main `Handler` so it lands on an idle
Library frame, not the pop transition. Release stays on the main thread —
deferring, not off-threading, is the only Media3-legal move.

**Stale-owner safety:** Compose Navigation does not order an outgoing entry's
`ViewModelStore.clear()` against the incoming entry's VM init. `acquire()`
takes over from a still-attached owner (snapshotting its position first, handed
back exactly once on the stale token's `detach`). Every player-mutating VM call
is lease-guarded via `ownedPlayer()` (`engine.isOwner(token)`), so a stale VM's
late `ON_STOP`/callbacks can neither pause the new owner nor corrupt an ADR-0037
resume slot.

## Consequences
- **No behavioral change**: playback, resume (ADR-0037), identity, and nav are
  untouched. This branch moves *where the player lives*, not *what it does*.
- Measured on RZCYA1VBQ2H (identical probe, 10× loop): back-nav clear
  31→2.1 ms median; warm attach 10.5→8.8 ms; cold first-entry 146→117 ms. The
  briefed 210/450 ms stalls did not reproduce at that magnitude on 10 s clips.
- **No new `check*` gate** (gate count stays **48**). Like ADR-0036, the
  protection is the pure `PlayerEngineLedger` state machine + its JVM tests
  (acquire/detach/destroy/takeover/A-B-A stale-token/isOwner) plus the
  `ownedPlayer()` lease-guard tests. The ownership-boundary invariant ("no
  playback logic in the holder") is documented, owner-mandated, and enforced by
  code review, not a regex gate.
- The deferred-release window puts two live players on the one shared playback
  looper for ≤400 ms (retired one stopped+cleared/idle, new one active) — a
  reviewed, device-verified tradeoff (see PlayerEngine KDoc). `destroy()`
  flushes pending releases immediately (`removeCallbacks` + `release`) and quits
  the shared thread; the release callback captures its specific retired instance
  by identity, so it can never touch a newly-acquired player.
