package com.aritr.rova.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * Locked semantic colors (ADR-0028 §1.3) — identical across all 12 palettes.
 * Sourced from the warnings v2/v3 token set.
 */
@Immutable
object RovaSemantics {
    val success: Color = Color(0xFF34D399)
    val warning: Color = Color(0xFFFBBF24)
    val error: Color = Color(0xFFEF4444)
    val escalating: Color = Color(0xFFF97316)
    val rec: Color = Color(0xFFFF4D4D)
}

/**
 * A single named palette (ADR-0028 §1.3). Supplies only background + glass tint
 * + edges + accent gradient + text alphas + the pinned-dark accent companions.
 * Semantic colors live outside the palette in [RovaSemantics] (locked).
 */
@Immutable
data class RovaPalette(
    val id: ThemeSelection,
    val background: Brush,
    val glassTint: Color,
    val edge: Color,
    val edgeTop: Color,
    val accent: Color,
    val accent2: Color,
    val textHigh: Color,
    val textDim: Color,
    val textFaint: Color,
    val accentOnDark: Color,
    val accentContainerOnDark: Color,
    val isLight: Boolean,
)

// --- shared dark-base tokens (mockup `.t` defaults) ---
private val DarkTextHigh = Color(0xF0FFFFFF)
private val DarkTextDim = Color(0x9EFFFFFF)
private val DarkTextFaint = Color(0x66FFFFFF)
private val DarkGlass = Color(0x94121620)
private val DarkEdge = Color(0x1AFFFFFF)
private val DarkEdgeTop = Color(0x2EFFFFFF)

/** Build a standard dark palette; accent doubles as accentOnDark (legible on neutral-dark). */
private fun darkPalette(
    id: ThemeSelection,
    bgTop: Long,
    bgBottom: Long,
    accent: Color,
    accent2: Color,
): RovaPalette = RovaPalette(
    id = id,
    background = Brush.verticalGradient(listOf(Color(bgTop), Color(bgBottom))),
    glassTint = DarkGlass,
    edge = DarkEdge,
    edgeTop = DarkEdgeTop,
    accent = accent,
    accent2 = accent2,
    textHigh = DarkTextHigh,
    textDim = DarkTextDim,
    textFaint = DarkTextFaint,
    accentOnDark = accent,
    accentContainerOnDark = accent.copy(alpha = 0.22f),
    isLight = false,
)

// --- Wave 1 (Signature 6) ---
private val Aurora = darkPalette(ThemeSelection.AURORA, 0xFF2C3658, 0xFF141622, Color(0xFF5B9DFF), Color(0xFF7C5BFF))
private val Tide = darkPalette(ThemeSelection.TIDE, 0xFF16323A, 0xFF0E1A1F, Color(0xFF34D3C0), Color(0xFF2AA3FF))
private val Jade = darkPalette(ThemeSelection.JADE, 0xFF13322A, 0xFF0C1C18, Color(0xFF34D399), Color(0xFF059E6B))
private val Dusk = darkPalette(ThemeSelection.DUSK, 0xFF3A241C, 0xFF1F1310, Color(0xFFFF8A4C), Color(0xFFFF5FA2))

/**
 * Eclipse — pure-black OLED. #000 hides elevation, so its edges carry the depth (§1.3).
 * Accent = deep-twilight royal violet (#8B7CFF→#B56CFF): the lavender sky of totality
 * (web + codex, 2026-06-08). Distinct from Aurora's blue-start gradient — the round2
 * mockup shared Aurora's blue here, which collapsed the two on the themed mode chip.
 */
private val Eclipse = RovaPalette(
    id = ThemeSelection.ECLIPSE,
    background = Brush.verticalGradient(listOf(Color(0xFF050608), Color(0xFF000000))),
    glassTint = Color(0xD608090C),
    edge = Color(0x14FFFFFF),
    edgeTop = Color(0x1FFFFFFF),
    accent = Color(0xFF8B7CFF),
    accent2 = Color(0xFFB56CFF),
    textHigh = DarkTextHigh,
    textDim = DarkTextDim,
    textFaint = DarkTextFaint,
    accentOnDark = Color(0xFF8B7CFF),
    accentContainerOnDark = Color(0xFF8B7CFF).copy(alpha = 0.22f),
    isLight = false,
)

/** Daylight — the only light palette; ink-on-light text + bright glass. */
private val Daylight = RovaPalette(
    id = ThemeSelection.DAYLIGHT,
    background = Brush.verticalGradient(listOf(Color(0xFFE3E9F5), Color(0xFFF4F1EA))),
    glassTint = Color(0xA8FFFFFF),
    edge = Color(0x12141A28),
    edgeTop = Color(0xD9FFFFFF),
    accent = Color(0xFF5B7FFF),
    accent2 = Color(0xFF7C5BFF),
    textHigh = Color(0xF2141A28),
    textDim = Color(0x99141A28),
    textFaint = Color(0x6B141A28),
    accentOnDark = Color(0xFF5B7FFF),
    accentContainerOnDark = Color(0xFF5B7FFF).copy(alpha = 0.22f),
    isLight = true,
)

// --- Wave 2 (Extended 6) — defined now, exposed in a later PR ---
private val Blossom = darkPalette(ThemeSelection.BLOSSOM, 0xFF3A2238, 0xFF1E1220, Color(0xFFFF8FC7), Color(0xFFFF6FAE))
private val Coral = darkPalette(ThemeSelection.CORAL, 0xFF3A2820, 0xFF1F1510, Color(0xFFFFB24D), Color(0xFFFF6A8B))
private val Meadow = darkPalette(ThemeSelection.MEADOW, 0xFF22301A, 0xFF12190E, Color(0xFF9AE65C), Color(0xFF34D3C0))
private val Cobalt = darkPalette(ThemeSelection.COBALT, 0xFF1C2350, 0xFF0E1230, Color(0xFF6F8CFF), Color(0xFF3A45C4))
private val Orchid = darkPalette(ThemeSelection.ORCHID, 0xFF3A2030, 0xFF1E1019, Color(0xFFFB7185), Color(0xFFA855F7))
private val Graphite = darkPalette(ThemeSelection.GRAPHITE, 0xFF1A1C20, 0xFF0E0F12, Color(0xFFD2D6DE), Color(0xFF8A8F99))

/** The full registry — every concrete [ThemeSelection] maps to exactly one palette. */
val rovaPalettes: Map<ThemeSelection, RovaPalette> = mapOf(
    ThemeSelection.AURORA to Aurora,
    ThemeSelection.TIDE to Tide,
    ThemeSelection.JADE to Jade,
    ThemeSelection.DUSK to Dusk,
    ThemeSelection.ECLIPSE to Eclipse,
    ThemeSelection.DAYLIGHT to Daylight,
    ThemeSelection.BLOSSOM to Blossom,
    ThemeSelection.CORAL to Coral,
    ThemeSelection.MEADOW to Meadow,
    ThemeSelection.COBALT to Cobalt,
    ThemeSelection.ORCHID to Orchid,
    ThemeSelection.GRAPHITE to Graphite,
)

/** Resolve a selection (Follow-System included) to a concrete palette. */
fun resolvePalette(selection: ThemeSelection, systemDark: Boolean): RovaPalette =
    rovaPalettes.getValue(selection.resolveConcrete(systemDark))

/**
 * Shared cinematic neutral-dark base for pinned camera/media routes
 * (Record/Player/Onboarding). ADR-0028 §2.4: pinned routes never adopt the
 * active palette's surface colors — only its dark-safe accents (see
 * [PinnedGlassEnvironment]). Glass tint/edges encode the shipped record-chrome
 * panel values (RecordChromeTokens: Black@0.40 fills, White@0.09 strokes) so
 * GlassSurface(role=RecordChrome) over this base reproduces today's airy look.
 * The accent fields are placeholders — ALWAYS overwritten per-route with the
 * active palette's dark-safe accents by [PinnedGlassEnvironment.forPinnedRoute].
 */
internal val NeutralDarkRecordPalette = RovaPalette(
    id = ThemeSelection.AURORA, // identity slot only; never theme-derived on pinned routes
    background = Brush.verticalGradient(listOf(Color(0xFF0B0E14), Color(0xFF05070B))),
    glassTint = Color(0x66000000),          // black @ 0.40 — matches RecordChromeTokens panels
    edge = Color.White.copy(alpha = 0.09f),
    edgeTop = Color.White.copy(alpha = 0.12f),
    accent = Color(0xFF5B9DFF),             // overwritten per-route
    accent2 = Color(0xFF7C5BFF),            // overwritten per-route
    textHigh = Color.White.copy(alpha = 0.93f),
    textDim = Color.White.copy(alpha = 0.65f),
    textFaint = Color.White.copy(alpha = 0.50f),
    accentOnDark = Color(0xFF5B9DFF),       // overwritten per-route
    accentContainerOnDark = Color(0xFF5B9DFF).copy(alpha = 0.22f), // overwritten per-route
    isLight = false,
)
