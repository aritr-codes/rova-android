package com.aritr.rova.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Bespoke System-D glyphs (ADR-0031 §5), authored from `board-3-semantic.html` on the 24-grid,
 * 1.9px monoline, round caps/joins. Placeholder colors are overridden by `Icon(tint = …)` in
 * [com.aritr.rova.ui.components.SemanticIcon]. This is the one home for bespoke `ImageVector`s
 * (the ex-`RecordChromeIcons` vectors folded in below; enforced by `checkRovaGlyphHome`, ADR-0031 §5).
 */
object RovaGlyphs {
    // Brush placeholder, overridden by `Icon(tint = …)`. MUST be declared BEFORE the glyph vals
    // below: object properties initialize top-to-bottom, so a glyph referencing a later-declared
    // `val PLACEHOLDER` would build its paths with a NULL brush → geometry present but nothing paints
    // (device-confirmed invisible Record FAB/nav, 2026-06-17). `SW` is a `const val` (compile-time
    // inlined) so its position is irrelevant; PLACEHOLDER is a runtime `val` and its position is not.
    private val PLACEHOLDER = SolidColor(Color.Black)
    private const val SW = 1.9f

    // Library — stacked frames (back = outline, top = accent). Un-collides Play (spec §8).
    val Library = RovaGlyph(
        outline = glyph { strokePath { roundRect(3.5f, 8f, 13f, 11f, 2f) } },
        accent = glyph { strokePath { roundRect(6f, 4.2f, 13f, 9.4f, 2f) } },
    )

    // Settings — soft 8-spoke gear (ring + spokes = outline, center dot = accent). spec §7.1.
    val Settings = RovaGlyph(
        outline = glyph {
            strokePath {
                circle(12f, 12f, 4.3f)
                seg(12f, 3.4f, 12f, 5.7f)
                seg(12f, 18.3f, 12f, 20.6f)
                seg(3.4f, 12f, 5.7f, 12f)
                seg(18.3f, 12f, 20.6f, 12f)
                seg(5.9f, 5.9f, 7.5f, 7.5f)
                seg(16.5f, 16.5f, 18.1f, 18.1f)
                seg(18.1f, 5.9f, 16.5f, 7.5f)
                seg(7.5f, 16.5f, 5.9f, 18.1f)
            }
        },
        accent = glyph { fillPath { circle(12f, 12f, 1.8f) } },
    )

    // Sort — state-free decreasing bars (top two = outline, short bottom = accent). spec §7.2.
    val Sort = RovaGlyph(
        outline = glyph {
            strokePath {
                seg(5f, 7f, 18f, 7f)
                seg(5f, 12f, 14f, 12f)
            }
        },
        accent = glyph { strokePath { seg(5f, 17f, 10f, 17f) } },
    )

    // Record — solid accent disc, the FAB capture action (mono). spec §8 / canonical map.
    val Record = RovaGlyph(
        outline = glyph { fillPath { circle(12f, 12f, 6.8f) } },
    )

    // ── Brand glyphs (spec Part B, board-3-semantic.html) ───────────────────

    // DualShot — P+L twin frames (outline) + accent capture core. board `ds_twin`.
    val DualShot = RovaGlyph(
        outline = glyph {
            strokePath { roundRect(3.4f, 5.6f, 8.6f, 12.8f, 2f) }
            strokePath { roundRect(11.6f, 9f, 9f, 9f, 2f) }
        },
        accent = glyph { fillPath { circle(11.9f, 12f, 2f) } },
    )

    // Vault — front frame (outline) + back frame & padlock (accent). board `vault_stack`.
    val Vault = RovaGlyph(
        outline = glyph { strokePath { roundRect(3.5f, 8f, 12.5f, 9f, 2f) } },
        accent = glyph {
            strokePath { roundRect(6f, 4f, 12.5f, 9f, 2f) }
            fillPath { roundRect(14f, 14.2f, 6.5f, 5.3f, 1.2f) }
            svgStroke("M15.3 14.2v-1.3a2 2 0 0 1 4 0v1.3")
        },
    )

    // Recovery — two rejoined bracket halves (outline) + dashed seam (accent). board `recov_rejoin`.
    val Recovery = RovaGlyph(
        outline = glyph {
            svgStroke("M10.3 5H6a2 2 0 0 0-2 2v10a2 2 0 0 0 2 2h4.3")
            svgStroke("M13.7 5H18a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2h-4.3")
        },
        accent = glyph { svgStroke("M12 4.3v3M12 10.5v3M12 16.7v3") },
    )

    // DualSight — frame + 2nd lens (outline) + PiP inset (accent). board `dualsight`.
    val DualSight = RovaGlyph(
        outline = glyph {
            strokePath { roundRect(3.5f, 5.5f, 17f, 13f, 2.3f) }
            strokePath { circle(8.2f, 10.5f, 2.4f) }
        },
        accent = glyph { fillPath { roundRect(12.4f, 11.6f, 6.2f, 5.2f, 1.4f) } },
    )

    // Background-record — backgrounded-app card (outline) + rec dot (accent). board `bg_record`.
    val BackgroundRecord = RovaGlyph(
        outline = glyph {
            svgStroke("M8.5 4.5H18a1.8 1.8 0 0 1 1.8 1.8V15")
            strokePath { roundRect(4.2f, 8f, 11.6f, 11.5f, 2.2f) }
        },
        accent = glyph { fillPath { circle(10f, 13.7f, 2.6f) } },
    )

    // Merge — two streams converge (outline) + join node (accent). board `merge_stitch`.
    val Merge = RovaGlyph(
        outline = glyph {
            svgStroke("M4 7h4M4 17h4M8 7c4 0 4 5 8 5M8 17c4 0 4-5 8-5M16 12h4")
        },
        accent = glyph { fillPath { circle(16.5f, 12f, 1.9f) } },
    )

    // Single (Auto) — one capture frame (outline) + accent capture core. Mono-safe:
    // one frame reads as single-camera capture; the core dot is the duotone channel.
    // (No board source — authored here as the Auto sibling of DualShot's twin frames.)
    val Single = RovaGlyph(
        outline = glyph { strokePath { roundRect(5.5f, 6.5f, 13f, 11f, 2.4f) } },
        accent = glyph { fillPath { circle(12f, 12f, 2f) } },
    )

    // FollowDevice — phone + two auto-rotate arrows (outline) + speaker bar (accent).
    // The rotation arrows are its ONLY differentiator from Portrait, so they live in the
    // MONO outline layer — they must never wash out (ADR-0031 §1 mono-safe). The accent is
    // the speaker bar, matching Portrait/Landscape's duotone channel. (No board source.)
    val FollowDevice = RovaGlyph(
        outline = glyph {
            strokePath { roundRect(9f, 6f, 6f, 12f, 1.8f) }
            svgStroke("M14.5 4c3 0.6 5 2.6 5.6 5.6M20.1 9.6l0.1-2.6M20.1 9.6l-2.6 0.2")
            svgStroke("M9.5 20c-3-0.6-5-2.6-5.6-5.6M3.9 14.4l-0.1 2.6M3.9 14.4l2.6-0.2")
        },
        accent = glyph { strokePath { seg(10.5f, 8.4f, 13.5f, 8.4f) } },
    )

    // ── Orientation (phone outline; owner-chosen over a person glyph) ───────
    // Mono-safe: the outline alone reads as an upright vs rotated phone; the
    // accent speaker bar is the duotone channel. (No board source — authored here.)

    // Portrait — upright phone + top speaker bar.
    val OrientationPortrait = RovaGlyph(
        outline = glyph { strokePath { roundRect(8f, 3f, 8f, 18f, 2.2f) } },
        accent = glyph { strokePath { seg(10.6f, 5.4f, 13.4f, 5.4f) } },
    )

    // Landscape — rotated phone + speaker bar on the left short edge.
    val OrientationLandscape = RovaGlyph(
        outline = glyph { strokePath { roundRect(3f, 8f, 18f, 8f, 2.2f) } },
        accent = glyph { strokePath { seg(5.4f, 10.6f, 5.4f, 13.4f) } },
    )

    // ── FAB lifecycle glyphs (board-3-semantic.html FB row) ─────────────────

    // Record nav/disabled resting form — ring (outline) + accent core. board `rec_ring`.
    // The FAB Disabled state renders this (the action's resting silhouette).
    val RecordRing = RovaGlyph(
        outline = glyph { strokePath { circle(12f, 12f, 8f) } },
        accent = glyph { fillPath { circle(12f, 12f, 3.2f) } },
    )

    // Waiting — hourglass frame (outline) + accent sand (accent). board `waiting`.
    // Pending / scheduled / between intervals (FAB Waiting state).
    val Waiting = RovaGlyph(
        outline = glyph {
            svgStroke("M7 4h10")
            svgStroke("M7 20h10")
            svgStroke("M7.5 4c0 4 4.5 5 4.5 8 0-3 4.5-4 4.5-8")
            svgStroke("M7.5 20c0-4 4.5-5 4.5-8 0 3 4.5 4 4.5 8")
        },
        accent = glyph { svgFill("M12 12.4c-1.3 1.3-2.6 2.2-2.6 5.1h5.2c0-2.9-1.3-3.8-2.6-5.1z") },
    )

    // Processing — 270° arc, spun by the consumer. board `proc_arc`. Mono (single layer).
    val ProcArc = RovaGlyph(
        outline = glyph { svgStroke("M12 4a8 8 0 1 1-7.4 5") },
    )

    // Processing — reduced-motion static fallback: three dots. board `proc_dots`.
    val ProcDots = RovaGlyph(
        outline = glyph {
            fillPath { circle(5.5f, 12f, 1.8f) }
            fillPath { circle(12f, 12f, 1.8f) }
            fillPath { circle(18.5f, 12f, 1.8f) }
        },
    )

    // ── PR-3 everyday-action + status glyphs (board-3-semantic.html "shared small" + Part A #3) ──

    // Search — lens ring + handle (mono). board `search`.
    val Search = RovaGlyph(
        outline = glyph {
            strokePath { circle(10.8f, 10.8f, 5.9f) }
            svgStroke("M15.4 15.4 20 20")
        },
    )

    // Share — 2 neutral connectors (outline) + 3 accent nodes (accent). board `share`.
    val Share = RovaGlyph(
        outline = glyph {
            svgStroke("M8.4 10.9 15 7.1")
            svgStroke("M8.4 13.1 15 16.9")
        },
        accent = glyph {
            fillPath { circle(6.2f, 12f, 2.3f) }
            fillPath { circle(17.3f, 6f, 2.3f) }
            fillPath { circle(17.3f, 18f, 2.3f) }
        },
    )

    // Delete — trash can (mono). board `delete`. Destructive tint is a call-site concern:
    // the glyph is neutral; consume with `status = IconStatus.Danger` in destructive contexts.
    val Delete = RovaGlyph(
        outline = glyph {
            svgStroke("M5 7h14")
            svgStroke("M9 7V5.6A1.6 1.6 0 0 1 10.6 4h2.8A1.6 1.6 0 0 1 15 5.6V7")
            svgStroke("M7.2 7 8.1 19.4A1.6 1.6 0 0 0 9.7 21h4.6a1.6 1.6 0 0 0 1.6-1.6L16.8 7")
        },
    )

    // Favorite (off) — star outline. board `favorite` (single accent channel). Single-layer:
    // consume with IconRole.Accent so the whole star is the palette accent (NEVER a status — that
    // would recolor it). PR-4 toggles state by swapping to [FavoriteOn].
    val Favorite = RovaGlyph(
        outline = glyph { svgStroke("M12 4 14.5 9.1l5.5.8-4 3.9.95 5.5L12 17.6 7.05 19.3 8 13.8 4 9.9l5.5-.8z") },
    )

    // Favorite (on) — filled star. board `favorite_on`. Consume with IconRole.Accent.
    val FavoriteOn = RovaGlyph(
        outline = glyph { svgFill("M12 4 14.5 9.1l5.5.8-4 3.9.95 5.5L12 17.6 7.05 19.3 8 13.8 4 9.9l5.5-.8z") },
    )

    // Select — ring (outline) + accent check (accent). board `select`.
    val Select = RovaGlyph(
        outline = glyph { strokePath { circle(12f, 12f, 8f) } },
        accent = glyph { svgStroke("M8.4 12.2 10.8 14.7 15.6 9.6") },
    )

    // Pause — two filled bars (mono). board `pause`.
    val Pause = RovaGlyph(
        outline = glyph {
            fillPath { roundRect(7.4f, 6f, 3.3f, 12f, 1.4f) }
            fillPath { roundRect(13.3f, 6f, 3.3f, 12f, 1.4f) }
        },
    )

    // View — 2×2 grid (mono). board `view` (== rejected `set_grid`: grid is canonically View).
    val View = RovaGlyph(
        outline = glyph {
            strokePath { roundRect(4f, 4f, 6.4f, 6.4f, 1.6f) }
            strokePath { roundRect(13.6f, 4f, 6.4f, 6.4f, 1.6f) }
            strokePath { roundRect(4f, 13.6f, 6.4f, 6.4f, 1.6f) }
            strokePath { roundRect(13.6f, 13.6f, 6.4f, 6.4f, 1.6f) }
        },
    )

    // Edit — pencil body (outline) + accent nib (accent). board `edit`.
    val Edit = RovaGlyph(
        outline = glyph { svgStroke("M5 19h3l9-9-3-3-9 9z") },
        accent = glyph { svgStroke("M14 6 17 9") },
    )

    // Theme — ring (outline) + accent-filled half-disc (accent). board `theme`.
    val Theme = RovaGlyph(
        outline = glyph { strokePath { circle(12f, 12f, 8f) } },
        accent = glyph { svgFill("M12 4a8 8 0 0 1 0 16z") },
    )

    // WarnTriangle — triangle + ! + dot (mono). board `warn_tri` (zero .ac2). A STATUS:
    // consume with `status = IconStatus.Warning` (locked amber, never a setting).
    val WarnTriangle = RovaGlyph(
        outline = glyph {
            svgStroke("M12 4.4 20.6 19.2H3.4z")
            svgStroke("M12 9.8v4.3")
            fillPath { circle(12f, 16.6f, 0.95f) }
        },
    )

    // NotifBell — bell (outline) + accent clapper (accent). board `notif_bell`. Chrome toggle
    // (a destination/setting), role-tinted — NOT a status.
    val NotifBell = RovaGlyph(
        outline = glyph { svgStroke("M6.5 10.5a5.5 5.5 0 0 1 11 0c0 4.5 2 5.5 2 5.5H4.5s2-1 2-5.5") },
        accent = glyph { svgStroke("M10.2 18.5a2 2 0 0 0 3.6 0") },
    )

    // NotifOff — bell-off (outline) + accent slash & clapper (accent). board `notif_off`.
    val NotifOff = RovaGlyph(
        outline = glyph {
            svgStroke("M6.5 10.5a5.5 5.5 0 0 1 8.4-4.7")
            svgStroke("M17.5 12.5c.2 2.4 1.5 3.5 1.5 3.5H8")
        },
        accent = glyph {
            svgStroke("M4.5 4.5 19.5 19.5")
            svgStroke("M10.2 18.5a2 2 0 0 0 3.6 0")
        },
    )

    // RecClipCheck — clip frame + side bar (outline) + accent check (accent). board `rec_clipcheck`.
    // Authored now; map entry + wiring land in PR-5 (recovered-clip-verified surface).
    val RecClipCheck = RovaGlyph(
        outline = glyph {
            strokePath { roundRect(3.5f, 6f, 12f, 11.5f, 2.3f) }
            svgStroke("M18.5 8.2v7")
        },
        accent = glyph { svgStroke("M12.6 16.8 15.1 19.3 19.6 14.3") },
    )

    // Interrupted — ring + top/bottom ticks (outline) + accent slash (accent). board `interrupted`.
    // A STATUS (locked amber). Authored now; map entry + status-encoding land in PR-5.
    val Interrupted = RovaGlyph(
        outline = glyph {
            strokePath { circle(12f, 12f, 8f) }
            svgStroke("M12 4v3M12 17v3")
        },
        accent = glyph { svgStroke("M8 8 16 16") },
    )

    // FlipCam — twin lens-orbit arcs (outline) + 2 arrowheads & centre core (accent). board `flip_cam`.
    // The two arcs read as a camera flip on their own (mono-safe); the arrowheads + core are the accent
    // channel. The record-screen flip control consumes this glyph (migrated in 5b-5).
    val FlipCam = RovaGlyph(
        outline = glyph {
            svgStroke("M5.5 9.5A7 6 0 0 1 18 7.5")
            svgStroke("M18.5 14.5A7 6 0 0 1 6 16.5")
        },
        accent = glyph {
            svgStroke("M18.3 4.5v3.2h-3.2")
            svgStroke("M5.7 19.5v-3.2h3.2")
            fillPath { circle(12f, 12f, 1.7f) }
        },
    )

    // Loop / Interval — repeat arrows (outline) + accent core. board `loop_interval`.
    val LoopInterval = RovaGlyph(
        outline = glyph {
            svgStroke("M8 6h8a4 4 0 0 1 0 8h-1")
            svgStroke("M16 18H8a4 4 0 0 1 0-8h1")
        },
        accent = glyph {
            svgStroke("M10.5 3.5 8 6l2.5 2.5")
            svgStroke("M13.5 20.5 16 18l-2.5-2.5")
            fillPath { circle(12f, 12f, 1.5f) }
        },
    )

    // Volume — filled speaker body (outline) + accent waves. board `volume`.
    val Volume = RovaGlyph(
        outline = glyph { svgFill("M4 9.4h3.2L11 6v12L7.2 14.6H4z") },
        accent = glyph {
            svgStroke("M14.4 9.2a4 4 0 0 1 0 5.6")
            svgStroke("M17 7a7.4 7.4 0 0 1 0 10")
        },
    )

    // ── Folded-in record-chrome vectors (ex-RecordChromeIcons, verbatim) ────
    // Single-layer, neutral white, tinted by the consuming Icon/SemanticIcon.
    // Kept at their original viewports/strokes — this was a structural move, not
    // a re-skin (System-D re-authoring is a later visual slice). Superseded
    // library/settings/flipCamera were dropped (duotone RovaGlyphs replace nav;
    // flip now uses the duotone RovaGlyphs.FlipCam — 5b-5).

    // Flash — single lightning bolt, re-authored to System-D (board `flash`). Mono (single
    // outline layer): the bolt silhouette carries the whole meaning with no accent channel, so
    // it reads with accent removed. Consumed as a raw `ImageVector` via `Icon(tint = …)` in
    // RecordChrome (hardware-state yellow when flash is ON) — keep the `ImageVector` type. The
    // 24-grid stroke (SW, round caps/joins) replaces the legacy folded 13×15 fill body.
    val FlashBolt: ImageVector = glyph { svgStroke("M13 3.5 6.5 13.2 11 13.2 11 20.5 17.5 10.8 13 10.8z") }

    /** `.settings-arrow` chevron — points up (expand sheet). */
    val ChevronUp: ImageVector =
        ImageVector.Builder(
            name = "RovaGlyphChevronUp",
            defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        ).apply {
            path(stroke = SolidColor(Color.White), strokeLineWidth = 2.2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(18f, 15f); lineTo(12f, 9f); lineTo(6f, 15f)
            }
        }.build()

    /** Play triangle (ex-fabPlay) — used as RovaIcons.Play. Board `play` fill path. */
    val Play: ImageVector = glyph { svgFill("M8 6.3 17.4 12 8 17.7z") }

    // ── 5b-1 new concepts (Settings / Warnings / Onboarding surfaces) ────────
    // Authored on the 24-grid, 1.9px monoline, round caps/joins; .ac2 accent only
    // where it adds meaning; every glyph mono-safe (meaning survives accent removal).
    // Board round-trip: each has a `G={}` entry + E1 MAP row in board-3-semantic.html.

    // Thermal — thermometer stem + bulb (outline) + accent rising mercury level.
    // Mono-safe: the stem+bulb silhouette is a thermometer on its own; the accent is
    // the fill level (the "how hot" channel). Used for thermal-autostop status.
    val Thermal = RovaGlyph(
        outline = glyph {
            svgStroke("M10 5.5a2 2 0 0 1 4 0v8.2a4 4 0 1 1-4 0z")
            svgStroke("M16 7.5h3M16 11h2.2")
        },
        accent = glyph {
            svgStroke("M12 11v4.4")
            fillPath { circle(12f, 16.8f, 2.2f) }
        },
    )

    // Storage — disk-platter stack (outline) + accent used-capacity bar.
    // Mono-safe: the two stacked discs read as a drive/disk stack; the accent is the
    // fill-level bar (the "how full" channel).
    val Storage = RovaGlyph(
        outline = glyph {
            svgStroke("M5 7.5c0 1.4 3.1 2.5 7 2.5s7-1.1 7-2.5S15.9 5 12 5 5 6.1 5 7.5")
            svgStroke("M5 7.5v9c0 1.4 3.1 2.5 7 2.5s7-1.1 7-2.5v-9")
        },
        accent = glyph { svgStroke("M8 16.5h5.5") },
    )

    // BatteryLow — battery body + terminal (outline) + accent low charge + warn notch.
    // Mono-safe: the cell body + nub reads as a battery; the accent is the low charge
    // bar plus the "!" warn mark (the alert channel).
    val BatteryLow = RovaGlyph(
        outline = glyph {
            strokePath { roundRect(3.5f, 7.5f, 15f, 9f, 2f) }
            svgStroke("M20 10.5v3")
        },
        accent = glyph {
            fillPath { roundRect(5.5f, 9.5f, 3f, 5f, 0.8f) }
            svgStroke("M12.5 9.8v3.2")
            fillPath { circle(12.5f, 15.2f, 0.85f) }
        },
    )

    // BatterySaver — battery body + terminal (outline) + accent eco leaf.
    // Mono-safe: the cell body reads as a battery; the accent leaf is the "saver/eco"
    // channel. Sibling of BatteryLow (same body geometry, different accent meaning).
    val BatterySaver = RovaGlyph(
        outline = glyph {
            strokePath { roundRect(3.5f, 7.5f, 15f, 9f, 2f) }
            svgStroke("M20 10.5v3")
        },
        accent = glyph {
            svgStroke("M11 14.5c-2.4 0-4-1.6-4-4 2.4 0 4 1.6 4 4z")
            svgStroke("M11 14.5c1.3-1.2 2.4-1.8 4-2")
        },
    )

    // PowerMode — power glyph: ring with a top break + vertical bar (mono).
    // Single-layer: the universal power symbol carries the whole meaning, no accent.
    val PowerMode = RovaGlyph(
        outline = glyph {
            svgStroke("M8.3 6.7a8 8 0 1 0 7.4 0")
            svgStroke("M12 4v6.5")
        },
    )

    // AlarmOff — clock + bell-feet (outline) + accent slash.
    // Mono-safe: the round clock with bell-mount feet reads as an alarm; the accent
    // slash is the "off/disabled" channel. (Alarm permission/schedule disabled.)
    val AlarmOff = RovaGlyph(
        outline = glyph {
            strokePath { circle(12f, 13f, 6.2f) }
            svgStroke("M12 10v3l2 1.5")
            svgStroke("M4.5 7.5 7.5 4.5M19.5 7.5 16.5 4.5")
        },
        accent = glyph { svgStroke("M5 5 19 21") },
    )

    // CameraOff — video frame + lens (outline) + accent slash.
    // Mono-safe: the frame+lens reads as the camera; the accent slash is the
    // "off/blocked" channel. Reuses the camera frame+lens family geometry.
    val CameraOff = RovaGlyph(
        outline = glyph {
            strokePath { roundRect(3.5f, 6.5f, 13f, 11f, 2.3f) }
            svgStroke("M16.5 9.2 20.5 7v10l-4-2.2")
            strokePath { circle(8.8f, 12f, 2.6f) }
        },
        accent = glyph { svgStroke("M4.5 4.5 19.5 19.5") },
    )

    // CameraPermission — video frame + lens, NO slash ("needs access"). Mono (single layer).
    // The frame+lens reads as the camera; absence of a slash distinguishes "grant access"
    // from CameraOff. Single-layer so the whole glyph reads neutrally.
    val CameraPermission = RovaGlyph(
        outline = glyph {
            strokePath { roundRect(3.5f, 6.5f, 13f, 11f, 2.3f) }
            svgStroke("M16.5 9.2 20.5 7v10l-4-2.2")
            strokePath { circle(8.8f, 12f, 2.6f) }
        },
    )

    // MicOff — capsule + stem/base (outline) + accent slash.
    // Mono-safe: the capsule + stem + base reads as a microphone; the accent slash is
    // the "off/muted" channel.
    val MicOff = RovaGlyph(
        outline = glyph {
            svgStroke("M9.4 5.4a2.6 2.6 0 0 1 5.2 0v5.2a2.6 2.6 0 0 1-5.2 0z")
            svgStroke("M6.5 11a5.5 5.5 0 0 0 11 0")
            svgStroke("M12 16.5V20M9 20h6")
        },
        accent = glyph { svgStroke("M5 4.5 19 18.5") },
    )

    // DarkMode — crescent moon (mono). Theme half-disc family, but a moon.
    // Single-layer: the crescent reads as "dark/night mode" alone. Sibling of Theme's
    // half-disc, carved into a moon instead of split.
    val DarkMode = RovaGlyph(
        outline = glyph { svgStroke("M19 14.4A8 8 0 1 1 9.6 5a6.4 6.4 0 0 0 9.4 9.4z") },
    )

    // Language — globe + meridians (outline) + accent equator/active-meridian.
    // Mono-safe: the circle + lat/long lines reads as a globe; the accent is the
    // highlighted meridian (the "active locale" channel). Reuses globe geometry.
    val Language = RovaGlyph(
        outline = glyph {
            strokePath { circle(12f, 12f, 8f) }
            svgStroke("M4 12h16")
            svgStroke("M12 4c2.6 2.2 4 4.9 4 8s-1.4 5.8-4 8c-2.6-2.2-4-4.9-4-8s1.4-5.8 4-8")
        },
        accent = glyph { svgStroke("M12 4v16") },
    )

    // Quality — capture frame (outline) + accent stacked resolution bars ("HD" abstraction).
    // Mono-safe: the frame reads as video; the accent rising bars are the "resolution/
    // quality level" channel.
    val Quality = RovaGlyph(
        outline = glyph { strokePath { roundRect(4f, 6f, 16f, 12f, 2.4f) } },
        accent = glyph {
            fillPath { roundRect(8f, 12.5f, 1.8f, 2f, 0.6f) }
            fillPath { roundRect(11.1f, 10.5f, 1.8f, 4f, 0.6f) }
            fillPath { roundRect(14.2f, 8.5f, 1.8f, 6f, 0.6f) }
        },
    )

    // Timer — clock face + top stem (outline) + accent sweeping hand.
    // Mono-safe: the round face + crown stem reads as a stopwatch/timer; the accent
    // hand is the "elapsed/sweep" channel.
    val Timer = RovaGlyph(
        outline = glyph {
            strokePath { circle(12f, 13.5f, 6.5f) }
            svgStroke("M10 4h4M12 4v3")
        },
        accent = glyph { svgStroke("M12 13.5 15.4 11") },
    )

    // Schedule — calendar grid (outline) + accent marked day.
    // Mono-safe: the box + header + hanging tabs reads as a calendar; the accent dot is
    // the "scheduled day" channel.
    val Schedule = RovaGlyph(
        outline = glyph {
            strokePath { roundRect(4f, 5.5f, 16f, 14f, 2.4f) }
            svgStroke("M4 9.5h16")
            svgStroke("M8 3.5v4M16 3.5v4")
        },
        accent = glyph { fillPath { roundRect(10.5f, 12f, 3f, 3f, 0.8f) } },
    )

    // Lock — shackle + body (mono). Reuses the vault padlock geometry, simplified.
    // Single-layer: the closed shackle over a lock body reads as "locked" with no accent
    // (consume with role tint; status would mis-tint it).
    val Lock = RovaGlyph(
        outline = glyph {
            fillPath { roundRect(5.5f, 11f, 13f, 8.5f, 1.6f) }
            svgStroke("M8 11V8.5a4 4 0 0 1 8 0V11")
        },
    )

    // Vibration — phone body (outline) + accent motion waves both sides.
    // Mono-safe: the phone body reads as the device; the accent waves are the "vibrate/
    // haptic" channel. Reuses the orientation phone-body family.
    val Vibration = RovaGlyph(
        outline = glyph { strokePath { roundRect(8.5f, 5f, 7f, 14f, 1.8f) } },
        accent = glyph {
            svgStroke("M5 9c-1 1.8-1 4.2 0 6")
            svgStroke("M19 9c1 1.8 1 4.2 0 6")
        },
    )

    // Device — phone body + screen + home indicator (mono). Reuses orientation phone family.
    // Single-layer: the phone body with screen line + indicator reads as "this device".
    val Device = RovaGlyph(
        outline = glyph {
            strokePath { roundRect(7f, 3.5f, 10f, 17f, 2.2f) }
            svgStroke("M7 16.5h10")
            svgStroke("M10.6 5.5h2.8")
        },
    )

    // GridLayout — reuse of View's 2×2 grid (board `view`/`set_grid`). Alias to avoid a
    // near-duplicate; mono single-layer (no accent). See [View].
    val GridLayout = View

    // Video — film frame (outline) + accent play notch.
    // Mono-safe: the frame reads as a video; the accent play triangle is the "playable
    // footage" channel. (board `lib_play` family, simplified frame.)
    val Video = RovaGlyph(
        outline = glyph { strokePath { roundRect(3.5f, 6f, 17f, 12f, 2.4f) } },
        accent = glyph { svgFill("M10 9.2 15.4 12 10 14.8z") },
    )

    // Folder — folder body (outline) + accent raised tab.
    // Mono-safe: the body reads as a folder; the accent tab is the duotone emphasis.
    val Folder = RovaGlyph(
        outline = glyph { svgStroke("M4 8.5v9a1.8 1.8 0 0 0 1.8 1.8h12.4A1.8 1.8 0 0 0 20 17.5V8.5z") },
        accent = glyph { svgStroke("M4 8.5V7a1.8 1.8 0 0 1 1.8-1.8h3.4L11.4 7.4H20") },
    )

    // Cleanup — broom head + handle (outline) + accent swept particles.
    // Mono-safe: the angled broom reads as "sweep/clean"; the accent particles are the
    // "debris being cleared" channel.
    val Cleanup = RovaGlyph(
        outline = glyph {
            svgStroke("M18 4 11 11")
            svgStroke("M7.5 11.5 12.5 16.5 9.8 19.2a3.4 3.4 0 0 1-4.8 0 3.4 3.4 0 0 1 0-4.8z")
        },
        accent = glyph { svgStroke("M15.5 14.5h2.5M14 18h3M17 17.5v2.5") },
    )

    // DeleteAll — trash can (outline) + accent stacked-item sweep lines.
    // Mono-safe: the trash can reads as delete; the accent stacked lines distinguish
    // "delete ALL/clear" from a single Delete. Reuses the trash geometry from [Delete].
    val DeleteAll = RovaGlyph(
        outline = glyph {
            svgStroke("M5 7h14")
            svgStroke("M9 7V5.6A1.6 1.6 0 0 1 10.6 4h2.8A1.6 1.6 0 0 1 15 5.6V7")
            svgStroke("M7.2 7 8.1 19.4A1.6 1.6 0 0 0 9.7 21h4.6a1.6 1.6 0 0 0 1.6-1.6L16.8 7")
        },
        accent = glyph { svgStroke("M10 10.5v6.5M14 10.5v6.5") },
    )

    // Privacy — shield (outline) + accent eye-off (slashed eye) ("private/hidden").
    // Mono-safe: the shield reads as "protection/privacy"; the accent eye-off is the
    // "hidden from view" channel. Reuses the shield geometry (board `rec_shield`).
    val Privacy = RovaGlyph(
        outline = glyph { svgStroke("M12 3.4 19 6v5c0 4.4-2.9 7.6-7 9-4.1-1.4-7-4.6-7-9V6z") },
        accent = glyph {
            svgStroke("M8.4 11.8c1-1.6 2.2-2.4 3.6-2.4s2.6.8 3.6 2.4c-1 1.6-2.2 2.4-3.6 2.4s-2.6-.8-3.6-2.4z")
            svgStroke("M8.8 8.6 15.2 15")
        },
    )

    // Info — ring (outline) + accent "i" (dot + stem).
    // Mono-safe: the ring + central mark reads as info; the accent "i" is the duotone
    // emphasis. Reuses the ring geometry (board `select`/`theme` circle).
    val Info = RovaGlyph(
        outline = glyph { strokePath { circle(12f, 12f, 8f) } },
        accent = glyph {
            fillPath { circle(12f, 8.2f, 1f) }
            svgStroke("M12 11.4v5")
        },
    )

    // CameraAccess — affirmative camera, NO slash, onboarding (mono). = [CameraPermission]
    // geometry. Aliased to avoid a near-duplicate; one frame+lens reads as "camera".
    val CameraAccess = CameraPermission

    // MicAccess — affirmative mic, NO slash, onboarding (mono).
    // Single-layer: capsule + stem + base reads as a microphone with no "off" slash —
    // the affirmative onboarding sibling of [MicOff].
    val MicAccess = RovaGlyph(
        outline = glyph {
            svgStroke("M9.4 5.4a2.6 2.6 0 0 1 5.2 0v5.2a2.6 2.6 0 0 1-5.2 0z")
            svgStroke("M6.5 11a5.5 5.5 0 0 0 11 0")
            svgStroke("M12 16.5V20M9 20h6")
        },
    )

    // ── authoring helpers ───────────────────────────────────────────────────
    // (PLACEHOLDER + SW are declared at the TOP of the object — see the init-order note there.)

    private fun glyph(build: ImageVector.Builder.() -> Unit): ImageVector =
        ImageVector.Builder(
            defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        ).apply(build).build()

    private fun ImageVector.Builder.strokePath(block: PathBuilder.() -> Unit) {
        path(
            stroke = PLACEHOLDER, strokeLineWidth = SW,
            strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
            pathBuilder = block,
        )
    }

    private fun ImageVector.Builder.fillPath(block: PathBuilder.() -> Unit) {
        path(fill = PLACEHOLDER, pathBuilder = block)
    }

    /** Verbatim SVG `<path d=…>` stroke (round caps/joins, System-D weight). */
    private fun ImageVector.Builder.svgStroke(d: String) {
        addPath(
            pathData = addPathNodes(d),
            stroke = PLACEHOLDER, strokeLineWidth = SW,
            strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
        )
    }

    /** Verbatim SVG `<path d=…>` fill. */
    private fun ImageVector.Builder.svgFill(d: String) {
        addPath(pathData = addPathNodes(d), fill = PLACEHOLDER)
    }

    /** A straight segment (SVG `M x1 y1 L x2 y2`). */
    private fun PathBuilder.seg(x1: Float, y1: Float, x2: Float, y2: Float) {
        moveTo(x1, y1); lineTo(x2, y2)
    }

    /**
     * A full circle as two semicircle arcs (SVG `<circle cx cy r>`). Each arc is exactly 180° →
     * `isMoreThanHalf = false`. (large-arc-flag `true` requests an arc >180° and is renderer-dependent
     * at exactly 180° — codex-flagged; matters for the filled disc + gear ring.)
     */
    private fun PathBuilder.circle(cx: Float, cy: Float, r: Float) {
        moveTo(cx, cy - r)
        arcToRelative(r, r, 0f, false, true, 0f, 2 * r)
        arcToRelative(r, r, 0f, false, true, 0f, -2 * r)
        close()
    }

    /** A rounded rect (SVG `<rect x y width height rx>`). */
    private fun PathBuilder.roundRect(x: Float, y: Float, w: Float, h: Float, rx: Float) {
        moveTo(x + rx, y)
        lineTo(x + w - rx, y); arcToRelative(rx, rx, 0f, false, true, rx, rx)
        lineTo(x + w, y + h - rx); arcToRelative(rx, rx, 0f, false, true, -rx, rx)
        lineTo(x + rx, y + h); arcToRelative(rx, rx, 0f, false, true, -rx, -rx)
        lineTo(x, y + rx); arcToRelative(rx, rx, 0f, false, true, rx, -rx)
        close()
    }
}
