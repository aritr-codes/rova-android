# PR-4 Library Surface Icon Migration — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Route the Library surface's identity-action icons through the `SemanticIcon` seam using the bespoke PR-3 glyphs, formalize the destructive Delete via `IconStatus.Danger`, and make Favorite read as outline→filled-accent — no new glyphs, no nav/utility migration.

**Architecture:** Mechanical call-site migration following the established P0 + Slice-3 seam pattern. A new pure `LibraryIconSpec` holds the only state→glyph/status *decisions* (favorite on/off; delete danger) so they are JVM-testable; the rest are Compose call-site flips to `SemanticIcon(glyph = RovaIcons.X, role = …, status = …)`. The hero-card favorite is a deliberate seam-exception (media-safe `overlayText` tint over poster art). Two shared row primitives (`BatchAction`, the RovaGlyph `SheetRow`) gain glyph/role/status awareness.

**Tech Stack:** Kotlin, Jetpack Compose (Material3), the existing `SemanticIcon`/`SemanticIconSpec`/`RovaIcons`/`RovaGlyphs` seam, JUnit (JVM unit tests under `isReturnDefaultValues = true`).

## Global Constraints

- **Scope = identity glyphs only.** Migrate only the ~9 concepts with a bespoke PR-3 glyph. Leave nav/utility (Back, Close, Clear, Select-All, hero/sheet Play, empty-state, filter-chip Check, Details=stock Info) as stock `Icon(...)`.
- **Favorite tint = accent on solid surfaces, media-safe `overlayText` on the hero card.**
- **Delete danger only where destructive:** `IconStatus.Danger` at the item-sheet Delete; the batch-bar Delete stays neutral `IconRole.Default`.
- **Batch-bar Favorite stays neutral** (`IconRole.Default`) — it is an action verb there, not a state.
- **View toggle migrates only the grid affordance** to `RovaIcons.View`; the list affordance stays stock.
- **No new glyphs authored.** Consume only what PR-3 shipped.
- **46 static `check*` gates stay green; NEVER edit a `check*` to pass.** No new gate, no new user-facing strings (all labels already exist).
- **JVM unit tests only.** The one new test is `LibraryIconSpecTest` (pure). Compose migrations are verified by `assembleDebug` + device smoke.
- **No new `ThemeContrastTest` assertion** (favorite/select accent already covered; delete-danger is a locked shape-paired color — a 12-palette assertion would false-fail Daylight).
- **Commits are deferred.** The whole PR lands as ONE owner-gated commit (PR-3 style); each task ends at a green build/test checkpoint, NOT a per-task commit. Push/PR/merge only on owner GO.
- **Subagents are EDIT-ONLY; the controller runs all gradle, the final commit, and the device smoke.**
- **Build WARM** (`gradlew.bat`, warm ~1–3 min). Confirm `:app:packageDebug` shows **EXECUTED** + fresh APK mtime before `adb install -r`. Device = RZCYA1VBQ2H (Android 14); Library is **not** `FLAG_SECURE` (adb screencap can self-verify), but the owner gives the visual GO.
- **codex-review the seam wiring** (`mcp__codex__codex`) before the final commit.

## File structure

| File | Responsibility | Change |
|---|---|---|
| `app/src/main/java/com/aritr/rova/ui/library/components/LibraryIconSpec.kt` | Pure state→glyph/status decisions | **Create** |
| `app/src/test/java/com/aritr/rova/ui/library/components/LibraryIconSpecTest.kt` | JVM test of the above | **Create** |
| `.../ui/library/components/LibraryItemSheet.kt` | Per-item sheet rows | Extend RovaGlyph `SheetRow`; migrate Share/Select/Favorite/Edit/Delete |
| `.../ui/library/components/LibraryBatchBar.kt` | Batch action strip | `BatchAction` ImageVector→RovaGlyph; migrate Share/Vault/Favorite/Delete |
| `.../ui/library/components/LibraryTopBar.kt` | Glass top bar | Migrate Search/Vault(nav)/View(grid affordance) |
| `.../ui/library/components/LibrarySearchField.kt` | Inline search field | Migrate leading Search |
| `.../ui/library/components/LibraryGridCard.kt` | Grid tile selection chip | selected→`RovaIcons.Select` (Accent) |
| `.../ui/library/components/LibraryListRow.kt` | List row selection check | selected→`RovaIcons.Select` (Accent) |
| `.../ui/library/components/LibraryHeroCard.kt` | Hero favorite (over media) | bespoke star, media-safe tint |

**Seam reference (do not modify):** `SemanticIcon(glyph: RovaGlyph, contentDescription, modifier, role = IconRole.Default, status: IconStatus? = null)` — `status` (locked `RovaSemantics`) suppresses the accent layer and wins over `role`; **callers MUST pass a size in `modifier`**. `IconRole { Default, Secondary, Disabled, Accent, OnAccent }`; `IconStatus { …, Danger }` (→ `RovaSemantics.error`). `RovaIcons.{Search, Share, Delete, Favorite, FavoriteOn, Select, Edit, View, Vault}` are bespoke `RovaGlyph`s already authored. `RovaIcons.Details` is a stock `RovaIcon(Icons.Outlined.Info)` and stays stock.

---

### Task 1: Pure `LibraryIconSpec` + JVM test

**Files:**
- Create: `app/src/main/java/com/aritr/rova/ui/library/components/LibraryIconSpec.kt`
- Test: `app/src/test/java/com/aritr/rova/ui/library/components/LibraryIconSpecTest.kt`

**Interfaces:**
- Produces: `LibraryIconSpec.favoriteGlyph(isFavorite: Boolean): RovaGlyph` (→ `RovaIcons.FavoriteOn` when true, `RovaIcons.Favorite` when false) and `LibraryIconSpec.deleteStatus(destructive: Boolean): IconStatus?` (→ `IconStatus.Danger` when true, `null` when false). Consumed by Tasks 2 (item-sheet favorite + delete) and 7 (hero favorite).

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/aritr/rova/ui/library/components/LibraryIconSpecTest.kt`:

```kotlin
package com.aritr.rova.ui.library.components

import com.aritr.rova.ui.theme.IconStatus
import com.aritr.rova.ui.theme.RovaIcons
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class LibraryIconSpecTest {

    @Test fun favorite_set_is_the_filled_star() {
        assertSame(RovaIcons.FavoriteOn, LibraryIconSpec.favoriteGlyph(true))
    }

    @Test fun favorite_unset_is_the_outline_star() {
        assertSame(RovaIcons.Favorite, LibraryIconSpec.favoriteGlyph(false))
    }

    @Test fun delete_in_a_destructive_context_is_danger() {
        assertEquals(IconStatus.Danger, LibraryIconSpec.deleteStatus(destructive = true))
    }

    @Test fun delete_as_a_neutral_batch_action_has_no_status() {
        assertNull(LibraryIconSpec.deleteStatus(destructive = false))
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `gradlew.bat :app:testDebugUnitTest --tests "com.aritr.rova.ui.library.components.LibraryIconSpecTest"`
Expected: FAIL — `Unresolved reference: LibraryIconSpec`.

- [ ] **Step 3: Write the minimal implementation**

Create `app/src/main/java/com/aritr/rova/ui/library/components/LibraryIconSpec.kt`:

```kotlin
package com.aritr.rova.ui.library.components

import com.aritr.rova.ui.theme.IconStatus
import com.aritr.rova.ui.theme.RovaGlyph
import com.aritr.rova.ui.theme.RovaIcons

/**
 * Pure state→glyph/status choices for the Library icon migration (ADR-0031, UI Phase 2 PR-4).
 * Kept Compose/Android-free so the decisions are JVM-unit-testable under `isReturnDefaultValues`,
 * mirroring the [com.aritr.rova.ui.screens.captureGlyphFor] picker-glyph pattern.
 */
internal object LibraryIconSpec {

    /** Favorite toggle: the filled star when set, the outline star when unset. */
    fun favoriteGlyph(isFavorite: Boolean): RovaGlyph =
        if (isFavorite) RovaIcons.FavoriteOn else RovaIcons.Favorite

    /**
     * Delete is the locked danger red ([IconStatus.Danger] → `RovaSemantics.error`) only in a
     * genuinely destructive context (the item sheet's quarantined Danger row). In the batch
     * action strip it is a neutral action among peers (`role = Default`), so the status is null.
     */
    fun deleteStatus(destructive: Boolean): IconStatus? =
        if (destructive) IconStatus.Danger else null
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `gradlew.bat :app:testDebugUnitTest --tests "com.aritr.rova.ui.library.components.LibraryIconSpecTest"`
Expected: PASS (4 tests).

---

### Task 2: Migrate `LibraryItemSheet` rows

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/library/components/LibraryItemSheet.kt`

**Interfaces:**
- Consumes: `LibraryIconSpec.favoriteGlyph` (Task 1); `SemanticIcon(glyph, …, role, status)`; `RovaIcons.{Share, Select, Edit, Delete, Vault, Details}`; `IconRole.Accent`; `IconStatus.Danger`.
- Produces: extended private `SheetRow(glyph: RovaGlyph, label, enabled = true, reason = null, role: IconRole = Default, status: IconStatus? = null, onClick)`.

- [ ] **Step 1: Extend the RovaGlyph `SheetRow` overload (lines 208–245)**

Replace the existing RovaGlyph `SheetRow` body with role + status support and a danger-aware label color:

```kotlin
@Composable
private fun SheetRow(
    glyph: RovaGlyph,
    label: String,
    enabled: Boolean = true,
    reason: String? = null,
    role: IconRole = IconRole.Default,
    status: IconStatus? = null,
    onClick: () -> Unit,
) {
    val baseColor = MaterialTheme.colorScheme.onSurface
    val labelColor = when {
        status == IconStatus.Danger -> MaterialTheme.colorScheme.error
        !enabled -> baseColor.copy(alpha = 0.38f)
        else -> baseColor
    }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .semantics {
                role = Role.Button
                if (!enabled) disabled()
            }
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        SemanticIcon(
            glyph = glyph,
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            role = if (!enabled) IconRole.Disabled else role,
            status = status,
        )
        Spacer(Modifier.width(20.dp))
        Column {
            Text(label, color = labelColor)
            if (!enabled && reason != null) {
                Text(reason, style = MaterialTheme.typography.bodySmall, color = labelColor)
            }
        }
    }
}
```

Note: there is a name clash between the `role` parameter and the `role` semantics property — the existing code already writes `role = Role.Button` inside `semantics { … }` where `role` resolves to the semantics receiver property, so this compiles unchanged. The parameter `role` is only read in the `SemanticIcon(role = …)` argument.

- [ ] **Step 2: Simplify the ImageVector `SheetRow` overload (lines 181–201)**

Delete and a now-unused `danger` parameter is removed (Delete moves to the RovaGlyph overload; only Play + Details use this overload now, both neutral). Replace lines 181–201 with:

```kotlin
@Composable
private fun SheetRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    // Explicit content colour: the floating sheet is a GlassSurface (no Material Surface to seed
    // LocalContentColor), so rows state their own colour. Used by the stock-Material rows (Play,
    // Details=Info) that have no bespoke glyph yet.
    val contentColor = MaterialTheme.colorScheme.onSurface
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .clickable(onClick = onClick)
            .semantics { role = Role.Button }
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        Icon(icon, contentDescription = null, tint = contentColor)
        Spacer(Modifier.width(20.dp))
        Text(label, color = contentColor)
    }
}
```

- [ ] **Step 3: Migrate the row call-sites (lines 122–142)**

Replace the row block inside the `Column` with:

```kotlin
                // ── Primary ──
                SheetRow(Icons.Filled.PlayArrow, playLabel) { onPlay() }
                SheetRow(RovaIcons.Share, shareLabel) { onShare() }
                HorizontalDivider(color = libraryColors.cardEdge)
                // ── Secondary ──
                SheetRow(RovaIcons.Select, selectLabel) { onSelect() }
                SheetRow(
                    glyph = LibraryIconSpec.favoriteGlyph(isFavorite),
                    label = if (isFavorite) unfavoriteLabel else favoriteLabel,
                    role = IconRole.Accent,
                ) { onToggleFavorite() }
                SheetRow(RovaIcons.Edit, renameLabel) { onRename() }
                SheetRow(RovaIcons.Details.glyph, viewSettingsLabel) { onViewSettings() }
                SheetRow(
                    glyph = RovaIcons.Vault,
                    label = vaultLabel,
                    enabled = movable,
                    reason = vaultUnavailableReason,
                ) { onMoveToVault() }
                HorizontalDivider(color = libraryColors.cardEdge)
                // ── Danger ──
                SheetRow(
                    glyph = RovaIcons.Delete,
                    label = deleteLabel,
                    status = LibraryIconSpec.deleteStatus(destructive = true),
                ) { onDelete() }
```

(`SheetRow(Icons.Filled.PlayArrow, …)` and `SheetRow(RovaIcons.Details.glyph, …)` resolve to the ImageVector overload — `RovaIcons.Details.glyph` is an `ImageVector`; every other row's first arg is a `RovaGlyph` → the RovaGlyph overload. No call is ambiguous.)

- [ ] **Step 4: Fix imports**

Remove the now-unused Material icon imports (lines 19–25): `Checklist`, `Delete`, `Edit`, `Share`, `Star`, `StarBorder`. Keep `PlayArrow` (line 22). Add an import for `IconStatus`:

```kotlin
import com.aritr.rova.ui.theme.IconStatus
```

(`RovaGlyph`, `RovaIcons`, `IconRole`, `SemanticIcon` are already imported; `LibraryIconSpec` is in the same package — no import. `ImageVector` stays — the ImageVector `SheetRow` + Details use it. `Icon` stays — used by the ImageVector overload.)

- [ ] **Step 5: Compile-check**

Run: `gradlew.bat :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL (no unresolved references, no unused-import errors).

---

### Task 3: Migrate `LibraryBatchBar` actions

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/library/components/LibraryBatchBar.kt`

**Interfaces:**
- Consumes: `SemanticIcon(glyph, …, role)`; `RovaIcons.{Share, Vault, FavoriteOn, Delete}`; `IconRole.{Default, Disabled}`.
- Produces: private `BatchAction(glyph: RovaGlyph, label, enabled, onClick, role: IconRole = Default, disabledDescription = null, modifier = Modifier)`.

- [ ] **Step 1: Migrate the four action call-sites (lines 63–72)**

Replace the four `BatchAction(...)` calls with glyph-typed ones (all neutral `Default` per the design — favorite is an action verb here, delete's destruction is gated downstream):

```kotlin
            BatchAction(RovaIcons.Share, shareLabel, enabled = true, onClick = onShare)
            BatchAction(
                RovaIcons.Vault,
                vaultLabel,
                enabled = vaultEnabled,
                disabledDescription = vaultDisabledLabel,
                onClick = onVault,
            )
            BatchAction(RovaIcons.FavoriteOn, favoriteLabel, enabled = true, onClick = onFavorite)
            BatchAction(RovaIcons.Delete, deleteLabel, enabled = true, onClick = onDelete)
```

- [ ] **Step 2: Change the `BatchAction` primitive (lines 77–110)**

Replace the `icon: ImageVector` parameter with `glyph: RovaGlyph` + an optional `role`, and render the icon through `SemanticIcon` while the label keeps its own computed color:

```kotlin
@Composable
private fun BatchAction(
    glyph: RovaGlyph,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    role: IconRole = IconRole.Default,
    disabledDescription: String? = null,
    modifier: Modifier = Modifier,
) {
    val labelColor =
        if (enabled) MaterialTheme.colorScheme.onSurface
        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
    val a11y = if (enabled) label else (disabledDescription ?: label)
    Column(
        modifier
            .width(72.dp)
            .selectable(
                selected = false,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .semantics { role = Role.Button; contentDescription = a11y }
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        SemanticIcon(
            glyph = glyph,
            contentDescription = null,
            modifier = Modifier.size(LibraryDimens.actionIcon),
            role = if (enabled) role else IconRole.Disabled,
        )
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = labelColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.clearAndSetSemantics {},
```

(Leave the rest of the `Column` body — the `Text(...)` continuation and closing braces — unchanged. Only the `val tint` line, the `Icon(...)` line, and the `color = tint` argument change; the icon line becomes `SemanticIcon(...)` and the label's `color = tint` becomes `color = labelColor`.)

- [ ] **Step 2b: Repoint the label color**

The `Text(label, …, color = tint, …)` at the original line 106 must read `color = labelColor` (the `tint` val is gone). Ensure the only color references in `BatchAction` are `labelColor`.

- [ ] **Step 3: Fix imports**

Remove `Icons.Filled.Delete`, `Icons.Filled.Lock`, `Icons.Filled.Share`, `Icons.Filled.Star` (lines 13–16), `androidx.compose.material3.Icon` (line 17), and `androidx.compose.ui.graphics.vector.ImageVector` (line 23). Add:

```kotlin
import com.aritr.rova.ui.components.SemanticIcon
import com.aritr.rova.ui.theme.IconRole
import com.aritr.rova.ui.theme.RovaGlyph
import com.aritr.rova.ui.theme.RovaIcons
```

(`Icons` base import line 12 is now unused too — remove `import androidx.compose.material.icons.Icons`.)

- [ ] **Step 4: Compile-check**

Run: `gradlew.bat :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

---

### Task 4: Migrate `LibraryTopBar` (Search, Vault-nav, View)

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/library/components/LibraryTopBar.kt`

**Interfaces:**
- Consumes: `SemanticIcon(glyph, …, role)`; `RovaIcons.{Search, Vault, View}`; `IconRole.Secondary` (matches the existing Sort sibling at line 93). Back + the list affordance stay stock.

- [ ] **Step 1: Migrate the Vault-nav button (lines 73–81)**

```kotlin
            if (onOpenVault != null) {
                IconButton(onClick = onOpenVault) {
                    SemanticIcon(
                        glyph = RovaIcons.Vault,
                        contentDescription = vaultLabel,
                        role = IconRole.Secondary,
                        modifier = Modifier.size(LibraryDimens.navIcon),
                    )
                }
            }
```

- [ ] **Step 2: Migrate the Search button (lines 82–90)**

```kotlin
            if (onOpenSearch != null) {
                IconButton(onClick = onOpenSearch) {
                    SemanticIcon(
                        glyph = RovaIcons.Search,
                        contentDescription = searchLabel,
                        role = IconRole.Secondary,
                        modifier = Modifier.size(LibraryDimens.navIcon),
                    )
                }
            }
```

- [ ] **Step 3: Migrate the grid affordance of the view toggle (lines 101–107)**

Only the grid-target (shown when NOT in grid mode) becomes the bespoke glyph; the list-target stays stock:

```kotlin
            IconButton(onClick = onToggleView) {
                if (viewMode == LibraryViewMode.GRID) {
                    Icon(
                        Icons.AutoMirrored.Filled.ViewList,
                        contentDescription = listLabel,
                        modifier = Modifier.size(LibraryDimens.navIcon),
                    )
                } else {
                    SemanticIcon(
                        glyph = RovaIcons.View,
                        contentDescription = gridLabel,
                        role = IconRole.Secondary,
                        modifier = Modifier.size(LibraryDimens.navIcon),
                    )
                }
            }
```

- [ ] **Step 4: Fix imports**

Remove `Icons.Filled.GridView` (line 11), `Icons.Filled.Lock` (line 12), `Icons.Filled.Search` (line 13). Keep `Icons.AutoMirrored.Filled.ArrowBack` (Back) and `Icons.AutoMirrored.Filled.ViewList` (list affordance). `SemanticIcon`, `IconRole`, `RovaIcons` are already imported (lines 22, 26, 27). `Icon` stays (Back + ViewList).

- [ ] **Step 5: Compile-check**

Run: `gradlew.bat :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

---

### Task 5: Migrate `LibrarySearchField` leading icon

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/library/components/LibrarySearchField.kt`

**Interfaces:**
- Consumes: `SemanticIcon(glyph, …, role)`; `RovaIcons.Search`; `IconRole.Secondary` (a field-leading hint adornment). The trailing Clear stays stock.

- [ ] **Step 1: Migrate the leading icon (line 46)**

```kotlin
        leadingIcon = {
            SemanticIcon(
                glyph = RovaIcons.Search,
                contentDescription = null,
                role = IconRole.Secondary,
                modifier = Modifier.size(24.dp),
            )
        },
```

- [ ] **Step 2: Fix imports**

Remove `import androidx.compose.material.icons.filled.Search` (line 9). Keep `Icons` (line 7) and `Icons.Filled.Clear` (line 8 — trailing Clear stays stock) and `androidx.compose.material3.Icon` (line 10 — Clear uses it). Add:

```kotlin
import com.aritr.rova.ui.components.SemanticIcon
import com.aritr.rova.ui.theme.IconRole
import com.aritr.rova.ui.theme.RovaIcons
```

- [ ] **Step 3: Compile-check**

Run: `gradlew.bat :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

---

### Task 6: Migrate selection toggles (`LibraryGridCard` + `LibraryListRow`)

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/library/components/LibraryGridCard.kt`
- Modify: `app/src/main/java/com/aritr/rova/ui/library/components/LibraryListRow.kt`

**Interfaces:**
- Consumes: `SemanticIcon(glyph, …, role)`; `RovaIcons.Select`; `IconRole.Accent`. The unselected `RadioButtonUnchecked` stays stock.

**HIGHEST-VISUAL-CHANGE TASK** — this swaps the *selected* indicator from a filled `CheckCircle` (primary) to the bespoke `Select` glyph (outline circle + accent check). Device smoke must look at the selection UI specifically; this task is the easiest single revert if the owner dislikes it.

- [ ] **Step 1: Migrate the grid selection chip (`LibraryGridCard.kt` lines 166–171)**

```kotlin
                if (isSelected) {
                    SemanticIcon(
                        glyph = RovaIcons.Select,
                        contentDescription = null,
                        role = IconRole.Accent,
                        modifier = Modifier.size(22.dp),
                    )
                } else {
                    Icon(
                        imageVector = Icons.Outlined.RadioButtonUnchecked,
                        contentDescription = null,
                        tint = libraryColors.overlayText,
                        modifier = Modifier.size(22.dp),
                    )
                }
```

- [ ] **Step 2: Fix `LibraryGridCard` imports**

Remove `import androidx.compose.material.icons.filled.CheckCircle` (line 21). Keep `Icons.Outlined.RadioButtonUnchecked` (line 22) and `Icon` (line 23 — unselected uses it). Add:

```kotlin
import com.aritr.rova.ui.components.SemanticIcon
import com.aritr.rova.ui.theme.IconRole
import com.aritr.rova.ui.theme.RovaIcons
```

- [ ] **Step 3: Migrate the list selection check (`LibraryListRow.kt` lines 94–101)**

```kotlin
            if (isSelectionMode) {
                if (isSelected) {
                    SemanticIcon(
                        glyph = RovaIcons.Select,
                        contentDescription = null,
                        role = IconRole.Accent,
                        modifier = Modifier.padding(end = 8.dp).size(24.dp),
                    )
                } else {
                    Icon(
                        imageVector = Icons.Outlined.RadioButtonUnchecked,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(end = 8.dp),
                    )
                }
            }
```

- [ ] **Step 4: Fix `LibraryListRow` imports**

Remove `import androidx.compose.material.icons.filled.CheckCircle` (line 20). Keep `Icons.Outlined.RadioButtonUnchecked` (line 21) and `Icon` (line 22). Add:

```kotlin
import com.aritr.rova.ui.components.SemanticIcon
import com.aritr.rova.ui.theme.IconRole
import com.aritr.rova.ui.theme.RovaIcons
```

- [ ] **Step 5: Compile-check**

Run: `gradlew.bat :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

---

### Task 7: Migrate `LibraryHeroCard` favorite (media-safe)

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/library/components/LibraryHeroCard.kt`

**Interfaces:**
- Consumes: `LibraryIconSpec.favoriteGlyph` (Task 1); `RovaGlyphs.{Favorite, FavoriteOn}.outline` (both single-layer, `accent = null`). This is the deliberate **seam-exception**: rendered as a plain `Icon(...)` tinted `libraryColors.overlayText` (media-legible token, not a palette role). `overlayText` is not a `Color.` literal → `checkSemanticIconNoRawAlpha` stays green.

- [ ] **Step 1: Migrate the favorite star (lines 134–138)**

```kotlin
            val favGlyph = LibraryIconSpec.favoriteGlyph(row.favorite)
            Icon(
                favGlyph.outline,
                contentDescription = if (row.favorite) unfavoriteLabel else favoriteLabel,
                tint = libraryColors.overlayText,
            )
```

(`favoriteGlyph(true) = RovaIcons.FavoriteOn` whose `.outline` is the **filled** star; `favoriteGlyph(false) = RovaIcons.Favorite` whose `.outline` is the **outline** star — so the set/unset visual is preserved, just in the bespoke System-D shape, tinted media-safe.)

- [ ] **Step 2: Fix imports**

Remove `import androidx.compose.material.icons.filled.Star` (line 21) and `import androidx.compose.material.icons.outlined.StarBorder` (line 22). Keep `Icons.Filled.PlayArrow` (line 20 — the center Play glyph stays stock) and `Icon` (line 23). `LibraryIconSpec` is in the same package — no import. (`RovaGlyphs` is reached transitively via `LibraryIconSpec.favoriteGlyph(...).outline`, so no `RovaGlyphs`/`RovaIcons` import is needed in this file.)

- [ ] **Step 3: Compile-check**

Run: `gradlew.bat :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

---

### Task 8: Full verification, codex review, device smoke

**Files:** none (verification only).

- [ ] **Step 1: Full gate + test build**

Run: `gradlew.bat :app:assembleDebug`
Expected: BUILD SUCCESSFUL — all **46** `check*` gates pass (esp. `checkSemanticIconNoRawAlpha`, `checkStatusColorLocked`, `checkRovaGlyphHome`), `:app:packageDebug` shows **EXECUTED**, fresh APK in `app/build/outputs/apk/debug/`.

Run: `gradlew.bat :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL — full JVM suite green including `LibraryIconSpecTest` (4 new tests).

If `compileDebugKotlin` reports UP-TO-DATE despite edits (stale incremental cache), re-run with `--rerun-tasks`. On a kotlinc/MD5 fault: `gradlew.bat --stop` + delete `app/build/kotlin` and `app/build/intermediates/built_in_kotlinc`, then rebuild.

- [ ] **Step 2: codex review of the seam wiring**

Invoke `mcp__codex__codex` with the diff of `LibraryIconSpec.kt`, the `BatchAction`/`SheetRow` signature changes, and the hero seam-exception. Ask it to flag: ambiguous overload resolution, any call-site that still raw-tints an icon, a `RovaSemantics.*.copy(` leak, or a row whose label/icon color diverged unintentionally. Fold findings or note disagreement.

- [ ] **Step 3: Device smoke (controller)**

Confirm `:app:packageDebug` EXECUTED + fresh APK mtime, then:

```
adb -s RZCYA1VBQ2H install -r app/build/outputs/apk/debug/app-debug.apk
```

(If the device is `unauthorized`, ask the owner to re-accept the USB-debugging prompt. If a release-signed build is installed, `adb uninstall com.aritr.rova` first — debug APK is debug-signed.)

Verify on-device (Library is not `FLAG_SECURE` → `adb -s RZCYA1VBQ2H exec-out screencap -p > smoke_pr4.png` can self-verify, owner gives final GO):
1. Top bar: Search + Sort + grid/list toggle render as bespoke System-D glyphs (Search = the PR-3 magnifier; grid affordance = the 2×2 `View`).
2. Search field leading glyph = the bespoke magnifier.
3. Long-press an item → item sheet: Share/Select/Edit are bespoke glyphs; Favorite is **filled-accent** when set / outline-accent when unset; Delete is **danger-red** and visually quarantined; Play + Details stay stock.
4. Hero card favorite (top-right) = bespoke star in **media-safe white** (NOT accent), still subordinate.
5. Enter selection mode → grid chip + list row show the bespoke `Select` (accent outline+check) when selected; the unselected empty circle is unchanged. **(Look here first — highest visual change.)**
6. Batch bar (selection mode): Share/Vault/Favorite/Delete are bespoke glyphs, uniform neutral tint; Vault greyed when a DualShot item is in the selection.

- [ ] **Step 4: Owner GO → single commit + docs refresh**

On owner visual GO, the controller: refreshes `HANDOFF.md` + `docs/BACKLOG.md` + `memory/project_icon_glyph_system.md` (PR-4 entry), then makes ONE commit (spec + plan + 8 source/test files + docs) and opens the PR — push/PR/merge only on owner GO.

---

## Self-review

**1. Spec coverage:**
- Identity-only depth → Tasks 2–7 migrate exactly the 9 concepts; nav/utility untouched (verified per-file import notes keep Back/Clear/Close/Play/Details stock). ✓
- Favorite accent-on-surfaces / media-safe-on-hero → Task 2 (sheet, Accent) + Task 7 (hero, overlayText). ✓
- Delete danger only destructive → Task 2 (`deleteStatus(true)`) + Task 3 (batch Default). ✓
- Batch favorite neutral, view-toggle grid-only → Task 3 + Task 4. ✓
- Pure helper + JVM test → Task 1. ✓
- No new gate/strings; no false-failing contrast test → Global Constraints + Task 8 gate run. ✓
- codex review + device smoke → Task 8. ✓

**2. Placeholder scan:** No TBD/TODO/"handle edge cases"/"similar to". Every code step shows full code. ✓

**3. Type consistency:** `LibraryIconSpec.favoriteGlyph(Boolean): RovaGlyph` and `deleteStatus(Boolean): IconStatus?` are defined in Task 1 and consumed with matching signatures in Tasks 2 + 7. `SheetRow(glyph, label, enabled, reason, role, status, onClick)` defined in Task 2 Step 1 and called in Task 2 Step 3 with named `role`/`status`. `BatchAction(glyph, label, enabled, onClick, role, disabledDescription, modifier)` defined in Task 3 Step 2 and called in Task 3 Step 1. `SemanticIcon(glyph, contentDescription, modifier, role, status)` matches the seam signature read from `SemanticIcon.kt`. ✓
