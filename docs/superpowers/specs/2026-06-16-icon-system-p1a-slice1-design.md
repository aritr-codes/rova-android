# Icon System P1a — Slice 1: duotone glyph pipeline + 4 glyphs — design spec

Status: **Approved (design)** — 2026-06-16 (owner GO). Implementation pending.
Parent program: ADR-0031 §8 **P1** ("bespoke `RovaGlyphs` + accent/glass-chip palette wiring"), here decoupled from the theme engine (owner-sequenced 2026-06-16: glyphs first, engine as a separate later brainstorm).
Builds on: **P0** (merged `master d8c02c6`) — the `SemanticIcon` seam, `SemanticIconSpec` resolver (`IconRole`/`IconStatus`), and the `RovaIcons` alias map.
Source of glyph geometry: `.superpowers/brainstorm/1234-1781611237/content/board-3-semantic.html` (the final semantic glyph designs; **not** `board-v2.html`, whose Library still carried the rejected frame+play form). Boards are gitignored — exact path data is inlined into the implementation plan.

---

## 1 · Goal

Prove the **two-layer duotone glyph pipeline** end-to-end (author → seam → call-site → device) on a small, high-visibility set, so the remaining ~16 designed glyphs can be bulk-authored in Slice 2 against a verified pattern. Deliver the first genuinely visible System-D change without waiting on the Liquid Glass theme engine.

This is decoupled from the theme engine: the accent channel uses the already-existing `palette.accent`, so glyphs retint per theme today through the P0 seam.

---

## 2 · Scope (this slice)

**In:**
1. A `RovaGlyph` two-layer type (outline + optional accent `ImageVector`).
2. A `SemanticIcon(glyph: RovaGlyph, …)` overload that stacks the two layers (outline tinted by role/status, accent tinted by `palette.accent`).
3. Four glyphs authored as Compose `ImageVector`s from `board-3-semantic.html`:
   - **Library** → stacked-frames (mono; un-collides Play, spec §8).
   - **Settings** → soft 8-spoke gear (mono; resolves the sliders→gear collision, spec §7.1).
   - **Sort** → state-free decreasing bars (mono; spec §7.2).
   - **Record** → ring + **accent core** (the duotone proof case) for the FAB action; rounded-square for the stop state.
4. Repoint `RovaIcons.Library`, `RovaIcons.Settings`, `RovaIcons.Sort` (and a new `RovaIcons.Record`) at the new `RovaGlyph`s; update the existing call-sites (RecordChrome nav, LibraryTopBar sort, Record FAB lifecycle).
5. JVM structural tests + device smoke.

**Out (deferred to Slice 2+):**
- The remaining brand/action/status glyphs (DualShot, DualSight, Vault, Recovery, Background-record, Merge, Favorite, Share, Select, Play, Pause, Volume, View, status badges, Camera Flip).
- Full fold-in of `RecordChromeIcons` into a `RovaGlyphs` home **and** the `checkRovaGlyphHome` gate (ADR-0031 enforcement) — that gate asserts "bespoke `ImageVector`s only in `RovaGlyphs`", which can only go green once *all* bespoke vectors have moved. Adding it mid-fold-in would force-fail. Deferred to the fold-in slice.
- The Liquid Glass theme engine (separate program).
- Glass-chip active containers + animated states (P2).

---

## 3 · Architecture

### 3.1 `RovaGlyph` (new — `ui/theme/RovaGlyph.kt`)
```kotlin
@Immutable
data class RovaGlyph(val outline: ImageVector, val accent: ImageVector? = null)
```
- **outline** — the mono-safe layer. Carries the full meaning on its own (spec §10.3). Tinted by the role (or status) color.
- **accent** — optional duotone detail (the `.ac2` channel: record core, etc.). Tinted by `palette.accent`. Omitting it is legal and the glyph still reads.

### 3.2 `SemanticIcon(glyph: RovaGlyph, …)` overload (`ui/components/SemanticIcon.kt`)
```kotlin
@Composable
fun SemanticIcon(
    glyph: RovaGlyph,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    role: IconRole = IconRole.Default,
    status: IconStatus? = null,
) {
    val palette = LocalGlassEnvironment.current.palette
    val baseTint = if (status != null) SemanticIconSpec.statusTint(status)
                   else SemanticIconSpec.tint(palette, role)
    Box(modifier) {
        Icon(glyph.outline, contentDescription, tint = baseTint)
        glyph.accent?.let { acc ->
            // When the glyph carries a status meaning the whole mark is locked; otherwise the
            // accent layer is the retintable channel (palette.accent).
            val accentTint = if (status != null) baseTint else palette.accent
            Icon(acc, contentDescription = null, tint = accentTint)
        }
    }
}
```
- The existing `SemanticIcon(imageVector, …)` overload (stock Material glyphs) is unchanged — call-sites not yet migrated to `RovaGlyph` keep working.
- Both layers share the same `Box` bounds (24-grid viewport), so they register exactly.
- `contentDescription` lives on the outline; the accent layer is decorative (`null`) to avoid a double announcement.

### 3.3 Glyph authoring
Each glyph is an `ImageVector` built with the Compose `ImageVector.Builder` / `path { … }` DSL, transcribing the SVG path data from `board-3-semantic.html` on the 24×24 grid with `stroke = SolidColor(Color.Black)` placeholder (overridden by `Icon(tint=)`), `strokeLineWidth = 1.9f`, round cap/join. The accent paths (the board's `.ac2` group) become the `accent` layer; everything else is the `outline` layer. Filled marks (record disc, the accent core) use `fill` instead of stroke per the board.

### 3.4 `RovaIcons` repoint (`ui/theme/RovaIcons.kt`)
`RovaIcons` currently holds `RovaIcon(glyph: ImageVector, status)`. Add a parallel `RovaGlyph`-typed surface for the migrated concepts (e.g. `RovaIcons.LibraryGlyph: RovaGlyph`), or evolve `RovaIcon` to optionally carry a `RovaGlyph`. **Decision:** add `RovaGlyph`-typed vals (`Library`, `Settings`, `Sort`, `Record`) and keep the stock `RovaIcon` map for not-yet-authored concepts; the two coexist until Slice 2 unifies them. Exact shape finalized in the plan.

---

## 4 · Call-site changes

| Concept | Call-site (current) | Change |
|---|---|---|
| Library (nav) | `RecordChrome` NavItem → `RecordChromeIcons.library` via `SemanticIcon(imageVector,…)` | → `SemanticIcon(glyph = RovaIcons.Library, …)` |
| Settings (nav) | `RecordChrome` NavItem → `RecordChromeIcons.settings` | → `SemanticIcon(glyph = RovaIcons.Settings, …)` |
| Sort | `LibraryTopBar` → `Icons.AutoMirrored.Filled.Sort` | → `SemanticIcon(glyph = RovaIcons.Sort, …)` |
| Record (FAB) | `RecordChrome` RecordFab → `RecordChromeIcons.fabPlay` (Start), red square Box (Stop) | Start → `RovaIcons.Record` (ring+accent core); Stop → rounded-square glyph or kept Box (plan decides) |

**Visible change:** the FAB Start glyph moves from a play-triangle to the record ring/disc — the first System-D visible change. Owner-confirmed in scope.

`RecordChromeIcons.library/settings/fabPlay` become unused by these call-sites but **remain in the file** (other vectors there still used; full fold-in is a later slice). No gate change this slice.

---

## 5 · Testing

- **JVM (`RovaGlyphTest` / extend `RovaIconsTest`):** each new `RovaGlyph` has a non-null `outline`; `Record` has a non-null `accent` (duotone), the mono glyphs may omit it; `RovaIcons.{Library,Settings,Sort,Record}` resolve; viewport is 24×24. (ImageVector path correctness is not unit-assertable — covered by device smoke.)
- **No new static gate** this slice (the `RovaGlyph` type + seam are pure/JVM-tested; `checkRovaGlyphHome` waits for the fold-in slice).
- **Device smoke (RZCYA1VBQ2H):** the 4 glyphs render at their call-sites; the Record FAB shows the new ring/disc; the accent core picks up the theme accent and retints on a theme swap; mono glyphs read clearly at 20–24 dp over the camera surface.

---

## 6 · Risks

| Risk | Mitigation |
|---|---|
| Two-layer registration drift (outline vs accent misaligned) | Both layers use the same 24-grid viewport + same `Box` bounds; verify on device. |
| Hand-transcription errors from SVG → `ImageVector` | Transcribe exact path data from `board-3-semantic.html` into the plan; device-smoke each glyph. |
| FAB glyph change surprises users | Owner-confirmed; isolated to Record Start; reversible (one `RovaIcons.Record` edit). |
| `RecordChromeIcons` left half-used | Intentional; full fold-in + `checkRovaGlyphHome` is a dedicated later slice, called out here. |
| Accent on a status glyph double-tints | Seam suppresses the retintable accent when `status != null` (locks the whole mark). |

---

## 7 · Success criteria

The `RovaGlyph` two-layer type + `SemanticIcon` overload exist and are JVM-tested; the four glyphs render correctly at their existing call-sites on device, with the Record accent core retinting per theme; the duotone pipeline is proven, unblocking Slice 2's bulk authoring. No regression to the P0 seam or the 44 gates.
