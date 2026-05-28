# Notification Redesign — Phase 3 (Mockup Alignment) — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Drop the redundant rail + chip from the Phase 2 RemoteViews; add a clip-dots row; switch MergeComplete from `setColorized(true)` to a dark glass card + green title; apply a translucent surface drawable as the custom-area background for the "modern glass" feel. All within the existing `DecoratedCustomViewStyle` + per-state copy/icon contract.

**Architecture:** Layouts shrink to title + body + dots-row + (progress). One new pure helper (`NotificationDotsRow.kt`) emits a `DotsPlan` per `NotificationState`; `NotificationBindPlan` gains `dots`, `titleColor`, `surfaceRes` and loses `chipContentDescriptionRes`. Service binder simplifies — no rail/chip wiring, adds dots-row + title-color + surface binding.

**Tech Stack:** Kotlin 2.2.10, AGP 9.2.1, Compose BOM 2025.01.01, JUnit 4, `androidx.core:core-ktx` `NotificationCompat` + `DecoratedCustomViewStyle`, RemoteViews, shape drawables.

**Spec:** [`docs/superpowers/specs/2026-05-28-notification-redesign-phase3-design.md`](../specs/2026-05-28-notification-redesign-phase3-design.md)

**Mockup ref (gitignored):** `mockups/new_uiux/10-notification-phase3.html`

**Branch:** `feat/notification-redesign-v1` (folds into the M5 PR alongside Phase 1 + Phase 2)

**Last commit:** *(filled by Task 0)*

---

## File map

| Path | Disposition |
|---|---|
| `app/src/main/res/values/strings.xml` | **modify** — delete 4 `notification_chip_cd_*` entries (dead refs) |
| `app/src/main/res/drawable/notif_accent_rail.xml` | **delete** (rail dropped) |
| `app/src/main/res/drawable/notif_chip_bg.xml` | **delete** (chip dropped) |
| `app/src/main/res/drawable/notif_surface.xml` | **new** translucent rounded surface (default) |
| `app/src/main/res/drawable/notif_surface_complete.xml` | **new** translucent rounded surface w/ green border |
| `app/src/main/res/drawable/notif_dot_pill.xml` | **new** 4dp pill shape (runtime-tinted) |
| `app/src/main/res/layout/notif_collapsed.xml` | **rewrite** — strip rail+chip; add root surface bg |
| `app/src/main/res/layout/notif_expanded.xml` | **rewrite** — strip rail+chip; add dots-row + root surface bg |
| `app/src/main/java/com/aritr/rova/service/notification/NotificationDotsRow.kt` | **new** pure helper |
| `app/src/test/java/com/aritr/rova/service/notification/NotificationDotsRowTest.kt` | **new** pure-JVM tests |
| `app/src/main/java/com/aritr/rova/service/notification/NotificationRenderer.kt` | **modify** — drop `chipContentDescriptionRes`; add `dots`, `titleColor`, `surfaceRes` |
| `app/src/test/java/com/aritr/rova/service/notification/NotificationRendererTest.kt` | **modify** — drop chip-CD assertions; add new-field assertions |
| `app/src/main/java/com/aritr/rova/service/RovaRecordingService.kt` | **modify** — drop rail/chip bind; add dots/title-color/surface bind; drop `setColorized` |

No Phase 1 helper changes. `NotificationCopy.kt`, `NotificationIconRes.kt`, `NotificationChannelConfig.kt`, `NotificationActionSpec.kt` stay untouched.

---

## Task 0: Bootstrap — verify Phase 2 baseline

**Files:** none modified

- [ ] **Step 0.1: Confirm branch + tip**

```powershell
git log --oneline -1
git rev-parse --abbrev-ref HEAD
```

Expected:
- tip: most recent Phase 3 spec commit
- branch: `feat/notification-redesign-v1`

- [ ] **Step 0.2: Verify green baseline**

```powershell
./gradlew :app:lintDebug :app:testDebugUnitTest :app:assembleDebug
```

Expected: `BUILD SUCCESSFUL`. Test summary `1300 tests completed, 0 failed`. All `check*` tasks pass.

If anything fails, STOP and triage before touching Phase 3 code.

---

## Task 1: Add new drawables (surface + dot pill)

**Files:**
- Create: `app/src/main/res/drawable/notif_surface.xml`
- Create: `app/src/main/res/drawable/notif_surface_complete.xml`
- Create: `app/src/main/res/drawable/notif_dot_pill.xml`

- [ ] **Step 1.1: Write `notif_surface.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<!-- M5 Phase 3 §4.4 — translucent dark surface for the custom content area.
     Sits INSIDE the system-owned notification card. Provides the "modern
     glass" feel against Android 12+ blurred shade. Hairline border only. -->
<shape xmlns:android="http://schemas.android.com/apk/res/android"
    android:shape="rectangle">
    <solid android:color="#B81C1E26" />
    <stroke android:width="1dp" android:color="#11FFFFFF" />
    <corners android:radius="14dp" />
</shape>
```

- [ ] **Step 1.2: Write `notif_surface_complete.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<!-- M5 Phase 3 §3.2 + §4.4 — MergeComplete variant. Same fill, green
     hairline border. Replaces the legacy setColorized(true) full-green
     card; carries the celebratory signal via dots + title + border. -->
<shape xmlns:android="http://schemas.android.com/apk/res/android"
    android:shape="rectangle">
    <solid android:color="#B81C1E26" />
    <stroke android:width="1dp" android:color="#3334D399" />
    <corners android:radius="14dp" />
</shape>
```

- [ ] **Step 1.3: Write `notif_dot_pill.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<!-- M5 Phase 3 §4.3 — single dot pill in the clip-progress row. White
     by default; the service tints via setColorFilter at runtime per
     dot state (done = solid accent, current = translucent accent,
     todo = white at low alpha). -->
<shape xmlns:android="http://schemas.android.com/apk/res/android"
    android:shape="rectangle">
    <solid android:color="#FFFFFFFF" />
    <corners android:radius="2dp" />
</shape>
```

- [ ] **Step 1.4: Verify drawables compile**

```powershell
./gradlew :app:assembleDebug
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 1.5: Commit**

```powershell
git add app/src/main/res/drawable/notif_surface.xml app/src/main/res/drawable/notif_surface_complete.xml app/src/main/res/drawable/notif_dot_pill.xml
git commit -m "feat(notif): add Phase 3 surface + dot-pill shape drawables

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Task 2: Add `NotificationDotsRow.kt` + tests (TDD)

**Files:**
- Create: `app/src/main/java/com/aritr/rova/service/notification/NotificationDotsRow.kt`
- Create: `app/src/test/java/com/aritr/rova/service/notification/NotificationDotsRowTest.kt`

The dots-row helper is pure: it consumes a `NotificationState` and emits a `DotsPlan`. No Android calls.

- [ ] **Step 2.1: Write the failing tests**

Create `app/src/test/java/com/aritr/rova/service/notification/NotificationDotsRowTest.kt`:

```kotlin
package com.aritr.rova.service.notification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M5 Phase 3 §7 — pure-JVM tests for [NotificationDotsRow.toDotsPlan].
 * No RemoteViews, no Context — DotsPlan is plain data.
 */
class NotificationDotsRowTest {

    // ----- ClipRecording -----

    @Test fun `ClipRecording with total=6 current=2 yields 6 pills DONE-CURRENT-todo`() {
        val plan = NotificationState.ClipRecording(current = 2, total = 6).toDotsPlan()
        assertTrue(plan.visible)
        assertEquals(6, plan.pills.size)
        assertEquals(DotState.Kind.DONE, plan.pills[0].kind)
        assertEquals(DotState.Kind.CURRENT, plan.pills[1].kind)
        assertEquals(DotState.Kind.TODO, plan.pills[2].kind)
        assertEquals(NotificationChannelConfig.ACCENT_RECORDING, plan.accent)
        assertEquals("Clip 2 of 6", plan.contentDescription)
    }

    @Test fun `ClipRecording with total=null is invisible (unknown total)`() {
        val plan = NotificationState.ClipRecording(current = 1, total = null).toDotsPlan()
        assertFalse(plan.visible)
        assertTrue(plan.pills.isEmpty())
    }

    // ----- GapWaiting -----

    @Test fun `GapWaiting with total=6 nextNumber=3 yields DONE-DONE-todo`() {
        val plan = NotificationState.GapWaiting(
            nextNumber = 3, nextInLabel = "4:42", total = 6
        ).toDotsPlan()
        assertTrue(plan.visible)
        assertEquals(6, plan.pills.size)
        assertEquals(DotState.Kind.DONE, plan.pills[0].kind)
        assertEquals(DotState.Kind.DONE, plan.pills[1].kind)
        assertEquals(DotState.Kind.TODO, plan.pills[2].kind)
        assertEquals(NotificationChannelConfig.ACCENT_RECORDING, plan.accent)
    }

    @Test fun `GapWaiting with total=null is invisible`() {
        val plan = NotificationState.GapWaiting(
            nextNumber = 2, nextInLabel = "1:00", total = null
        ).toDotsPlan()
        assertFalse(plan.visible)
    }

    // ----- Merging -----

    @Test fun `Merging with done=4 total=6 yields done-done-done-done-CURRENT-todo`() {
        val plan = NotificationState.Merging(done = 4, total = 6).toDotsPlan()
        assertTrue(plan.visible)
        assertEquals(6, plan.pills.size)
        assertEquals(DotState.Kind.DONE, plan.pills[3].kind)
        assertEquals(DotState.Kind.CURRENT, plan.pills[4].kind)
        assertEquals(DotState.Kind.TODO, plan.pills[5].kind)
        assertEquals(NotificationChannelConfig.ACCENT_RECORDING, plan.accent)
        assertEquals("Merging, 4 of 6 done", plan.contentDescription)
    }

    @Test fun `Merging with done=6 total=6 yields all DONE`() {
        val plan = NotificationState.Merging(done = 6, total = 6).toDotsPlan()
        assertTrue(plan.pills.all { it.kind == DotState.Kind.DONE })
        assertEquals(6, plan.pills.size)
    }

    // ----- MergeComplete -----

    @Test fun `MergeComplete with clipCount=6 yields 6 DONE green pills`() {
        val plan = NotificationState.MergeComplete(clipCount = 6).toDotsPlan()
        assertTrue(plan.visible)
        assertEquals(6, plan.pills.size)
        assertTrue(plan.pills.all { it.kind == DotState.Kind.DONE })
        assertEquals(NotificationChannelConfig.ACCENT_COMPLETE, plan.accent)
        assertEquals("All 6 clips complete", plan.contentDescription)
    }

    @Test fun `MergeComplete with clipCount=1 yields 1 DONE pill`() {
        val plan = NotificationState.MergeComplete(clipCount = 1).toDotsPlan()
        assertEquals(1, plan.pills.size)
        assertEquals(DotState.Kind.DONE, plan.pills[0].kind)
        assertEquals("All 1 clip complete", plan.contentDescription)
    }

    @Test fun `MergeComplete with clipCount=0 is invisible`() {
        val plan = NotificationState.MergeComplete(clipCount = 0).toDotsPlan()
        assertFalse(plan.visible)
        assertTrue(plan.pills.isEmpty())
    }

    // ----- Large N cap policy (§3.1) -----

    @Test fun `ClipRecording total=10 current=4 yields 7 state pills plus COUNT_PILL +3`() {
        val plan = NotificationState.ClipRecording(current = 4, total = 10).toDotsPlan()
        assertEquals(8, plan.pills.size)
        assertEquals(DotState.Kind.DONE, plan.pills[0].kind)
        assertEquals(DotState.Kind.CURRENT, plan.pills[3].kind)
        assertEquals(DotState.Kind.TODO, plan.pills[4].kind)
        assertEquals(DotState.Kind.COUNT_PILL, plan.pills[7].kind)
        assertEquals("+3", plan.pills[7].countLabel)
    }

    @Test fun `MergeComplete clipCount=50 yields 7 DONE pills plus COUNT_PILL +43`() {
        val plan = NotificationState.MergeComplete(clipCount = 50).toDotsPlan()
        assertEquals(8, plan.pills.size)
        assertTrue(plan.pills.take(7).all { it.kind == DotState.Kind.DONE })
        assertEquals(DotState.Kind.COUNT_PILL, plan.pills[7].kind)
        assertEquals("+43", plan.pills[7].countLabel)
        assertEquals("All 50 clips complete", plan.contentDescription)
    }

    // ----- Boundary: total = 8 — exactly fits, no count pill -----

    @Test fun `ClipRecording total=8 fits exactly without COUNT_PILL`() {
        val plan = NotificationState.ClipRecording(current = 3, total = 8).toDotsPlan()
        assertEquals(8, plan.pills.size)
        assertFalse(plan.pills.any { it.kind == DotState.Kind.COUNT_PILL })
    }
}
```

- [ ] **Step 2.2: Run, confirm fail**

```powershell
./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.service.notification.NotificationDotsRowTest"
```

Expected: compile failure — `NotificationDotsRow`, `DotsPlan`, `DotState`, `toDotsPlan` unresolved.

- [ ] **Step 2.3: Create `NotificationDotsRow.kt`**

```kotlin
package com.aritr.rova.service.notification

import androidx.annotation.ColorInt

/**
 * M5 Phase 3 §7 — single pill in the clip-progress row.
 * [countLabel] is non-null only when [kind] is COUNT_PILL.
 */
data class DotState(val kind: Kind, val countLabel: String? = null) {
    enum class Kind { DONE, CURRENT, TODO, COUNT_PILL }
}

/**
 * M5 Phase 3 §7 — dots-row bind plan. Pure data; the service consumes
 * it to build the RemoteViews LinearLayout. [visible]=false means the
 * row is hidden entirely (unknown total / empty MergeComplete).
 *
 * §3.1 cap policy: up to 8 entries. For total > 8 the row is 7 state
 * pills + 1 trailing COUNT_PILL("+M") where M = total - 7.
 *
 * Spec: docs/superpowers/specs/2026-05-28-notification-redesign-phase3-design.md
 */
data class DotsPlan(
    val pills: List<DotState>,
    @ColorInt val accent: Int,
    val contentDescription: String,
    val visible: Boolean
) {
    companion object {
        const val MAX_VISIBLE_PILLS = 8
        const val STATE_PILLS_WHEN_CAPPED = 7
    }
}

fun NotificationState.toDotsPlan(): DotsPlan = when (this) {
    is NotificationState.ClipRecording -> buildPlanFromActive(
        accent = NotificationChannelConfig.ACCENT_RECORDING,
        total = total,
        currentIndex = current - 1,
        contentDescription = if (total != null) "Clip $current of $total" else ""
    )
    is NotificationState.GapWaiting -> buildPlanFromActive(
        accent = NotificationChannelConfig.ACCENT_RECORDING,
        total = total,
        currentIndex = nextNumber - 1,
        contentDescription = if (total != null) "Waiting, $nextNumber of $total next" else ""
    )
    is NotificationState.Merging -> buildPlanFromActive(
        accent = NotificationChannelConfig.ACCENT_RECORDING,
        total = total,
        currentIndex = done,
        contentDescription = "Merging, $done of $total done"
    )
    is NotificationState.MergeComplete -> buildPlanForComplete(
        accent = NotificationChannelConfig.ACCENT_COMPLETE,
        clipCount = clipCount
    )
}

/**
 * Build a plan for an in-progress state. `currentIndex` is 0-based:
 * pills 0..currentIndex-1 = DONE, currentIndex = CURRENT,
 * currentIndex+1..end = TODO. For ClipRecording the "current" pill is
 * the clip being recorded right now; for GapWaiting it is the next
 * upcoming clip (which is logically still TODO but visually CURRENT to
 * signal "you are here"); for Merging it is the segment being merged.
 */
private fun buildPlanFromActive(
    @ColorInt accent: Int,
    total: Int?,
    currentIndex: Int,
    contentDescription: String
): DotsPlan {
    if (total == null || total <= 0) {
        return DotsPlan(pills = emptyList(), accent = accent, contentDescription = "", visible = false)
    }
    val pills = if (total <= DotsPlan.MAX_VISIBLE_PILLS) {
        List(total) { i ->
            DotState(
                kind = when {
                    i < currentIndex -> DotState.Kind.DONE
                    i == currentIndex -> DotState.Kind.CURRENT
                    else -> DotState.Kind.TODO
                }
            )
        }
    } else {
        val stateSlots = DotsPlan.STATE_PILLS_WHEN_CAPPED
        val statePills = List(stateSlots) { i ->
            DotState(
                kind = when {
                    i < currentIndex -> DotState.Kind.DONE
                    i == currentIndex -> DotState.Kind.CURRENT
                    else -> DotState.Kind.TODO
                }
            )
        }
        val remainder = total - stateSlots
        statePills + DotState(kind = DotState.Kind.COUNT_PILL, countLabel = "+$remainder")
    }
    return DotsPlan(pills = pills, accent = accent, contentDescription = contentDescription, visible = true)
}

private fun buildPlanForComplete(@ColorInt accent: Int, clipCount: Int): DotsPlan {
    if (clipCount <= 0) {
        return DotsPlan(pills = emptyList(), accent = accent, contentDescription = "", visible = false)
    }
    val cd = if (clipCount == 1) "All 1 clip complete" else "All $clipCount clips complete"
    val pills = if (clipCount <= DotsPlan.MAX_VISIBLE_PILLS) {
        List(clipCount) { DotState(kind = DotState.Kind.DONE) }
    } else {
        val statePills = List(DotsPlan.STATE_PILLS_WHEN_CAPPED) { DotState(kind = DotState.Kind.DONE) }
        val remainder = clipCount - DotsPlan.STATE_PILLS_WHEN_CAPPED
        statePills + DotState(kind = DotState.Kind.COUNT_PILL, countLabel = "+$remainder")
    }
    return DotsPlan(pills = pills, accent = accent, contentDescription = cd, visible = true)
}
```

- [ ] **Step 2.4: Run, confirm pass**

```powershell
./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.service.notification.NotificationDotsRowTest"
```

Expected: 12 tests pass / 0 failed.

`NotificationState` data classes already carry the fields the helper needs (verified at plan time, `app/src/main/java/com/aritr/rova/service/notification/NotificationCopy.kt:31-55`):
- `ClipRecording.total: Int?`, `current: Int`
- `GapWaiting.total: Int?`, `nextNumber: Int`
- `Merging.total: Int` (non-null), `done: Int`
- `MergeComplete.clipCount: Int`

No state-class changes required.

- [ ] **Step 2.5: Commit**

```powershell
git add app/src/main/java/com/aritr/rova/service/notification/NotificationDotsRow.kt app/src/test/java/com/aritr/rova/service/notification/NotificationDotsRowTest.kt
git commit -m "feat(notif): add NotificationDotsRow pure dots-plan helper

M5 Phase 3 §7. Emits DotsPlan per NotificationState — pill list per
segment with cap policy at 8 visible (7 state pills + COUNT_PILL +M
for total > 8). Hidden when total is unknown.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Task 3: Rewrite layouts (drop rail+chip, add surface + dots row)

**Files:**
- Modify: `app/src/main/res/layout/notif_collapsed.xml`
- Modify: `app/src/main/res/layout/notif_expanded.xml`
- Delete: `app/src/main/res/drawable/notif_accent_rail.xml`
- Delete: `app/src/main/res/drawable/notif_chip_bg.xml`

Both layouts gain a root id (`@+id/notif_root`) + the new surface drawable as background. Both lose the rail `ImageView` + chip `FrameLayout`. The expanded layout adds the dots-row `LinearLayout` of 8 ImageView slots (pre-allocated; visibility toggled at bind time).

- [ ] **Step 3.1: Rewrite `notif_collapsed.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<!-- M5 Phase 3 §4.1 — collapsed (48dp). Single row: title + tail.
     Surface background applied at root; service swaps to the complete
     variant via setBackgroundResource for MergeComplete. -->
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:id="@+id/notif_root"
    android:layout_width="match_parent"
    android:layout_height="48dp"
    android:orientation="horizontal"
    android:gravity="center_vertical"
    android:paddingHorizontal="14dp"
    android:background="@drawable/notif_surface">

    <TextView
        android:id="@+id/notif_title"
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        android:layout_weight="1"
        android:layout_marginEnd="8dp"
        android:singleLine="true"
        android:ellipsize="end"
        style="@style/TextAppearance.Compat.Notification.Title" />

    <TextView
        android:id="@+id/notif_tail"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:singleLine="true"
        android:ellipsize="end"
        android:alpha="0.7"
        style="@style/TextAppearance.Compat.Notification" />

</LinearLayout>
```

- [ ] **Step 3.2: Rewrite `notif_expanded.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<!-- M5 Phase 3 §4.2 + §4.3 + §4.4 — expanded layout.
     Vertical stack: title, body, dots row (8 pre-allocated pill slots,
     visibility toggled at bind time), progress (optional).
     Surface background applied at root; complete variant swapped at runtime. -->
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:id="@+id/notif_root"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:orientation="vertical"
    android:paddingHorizontal="14dp"
    android:paddingVertical="10dp"
    android:background="@drawable/notif_surface">

    <TextView
        android:id="@+id/notif_title"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:singleLine="true"
        android:ellipsize="end"
        style="@style/TextAppearance.Compat.Notification.Title" />

    <TextView
        android:id="@+id/notif_body"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_marginTop="2dp"
        android:singleLine="true"
        android:ellipsize="end"
        style="@style/TextAppearance.Compat.Notification" />

    <!-- Dots row. 8 pre-allocated slots; service toggles visibility +
         tint per state. The row itself is hidden via the wrapping
         LinearLayout's visibility when plan.dots.visible == false. -->
    <LinearLayout
        android:id="@+id/notif_dots_row"
        android:layout_width="match_parent"
        android:layout_height="4dp"
        android:layout_marginTop="10dp"
        android:orientation="horizontal"
        android:weightSum="8">

        <ImageView android:id="@+id/notif_dot_0" android:layout_width="0dp" android:layout_height="match_parent" android:layout_weight="1" android:layout_marginEnd="3dp" android:background="@drawable/notif_dot_pill" android:importantForAccessibility="no" />
        <ImageView android:id="@+id/notif_dot_1" android:layout_width="0dp" android:layout_height="match_parent" android:layout_weight="1" android:layout_marginEnd="3dp" android:background="@drawable/notif_dot_pill" android:importantForAccessibility="no" />
        <ImageView android:id="@+id/notif_dot_2" android:layout_width="0dp" android:layout_height="match_parent" android:layout_weight="1" android:layout_marginEnd="3dp" android:background="@drawable/notif_dot_pill" android:importantForAccessibility="no" />
        <ImageView android:id="@+id/notif_dot_3" android:layout_width="0dp" android:layout_height="match_parent" android:layout_weight="1" android:layout_marginEnd="3dp" android:background="@drawable/notif_dot_pill" android:importantForAccessibility="no" />
        <ImageView android:id="@+id/notif_dot_4" android:layout_width="0dp" android:layout_height="match_parent" android:layout_weight="1" android:layout_marginEnd="3dp" android:background="@drawable/notif_dot_pill" android:importantForAccessibility="no" />
        <ImageView android:id="@+id/notif_dot_5" android:layout_width="0dp" android:layout_height="match_parent" android:layout_weight="1" android:layout_marginEnd="3dp" android:background="@drawable/notif_dot_pill" android:importantForAccessibility="no" />
        <ImageView android:id="@+id/notif_dot_6" android:layout_width="0dp" android:layout_height="match_parent" android:layout_weight="1" android:layout_marginEnd="3dp" android:background="@drawable/notif_dot_pill" android:importantForAccessibility="no" />

        <!-- Last slot doubles as count-pill carrier: ImageView for tinted
             pill background; TextView overlay added via FrameLayout would
             complicate RemoteViews. Instead we keep two separate views
             stacked horizontally and toggle visibility per plan. -->
        <FrameLayout
            android:id="@+id/notif_dot_7_container"
            android:layout_width="0dp"
            android:layout_height="match_parent"
            android:layout_weight="1">

            <ImageView
                android:id="@+id/notif_dot_7"
                android:layout_width="match_parent"
                android:layout_height="match_parent"
                android:background="@drawable/notif_dot_pill"
                android:importantForAccessibility="no" />

            <TextView
                android:id="@+id/notif_dot_count_label"
                android:layout_width="match_parent"
                android:layout_height="match_parent"
                android:gravity="center"
                android:singleLine="true"
                android:textSize="8sp"
                android:textColor="#8AFFFFFF"
                android:visibility="gone"
                android:importantForAccessibility="no" />
        </FrameLayout>
    </LinearLayout>

    <ProgressBar
        android:id="@+id/notif_progress"
        style="?android:attr/progressBarStyleHorizontal"
        android:layout_width="match_parent"
        android:layout_height="4dp"
        android:layout_marginTop="8dp"
        android:visibility="gone" />

</LinearLayout>
```

The count-pill TextView sits at 8sp inside a 4dp-tall row — at OS scale 1× this is barely legible. Owner accepted this tradeoff at mockup review: the "+N" tag is a visual hint, not a primary readout; the title already says "Clip N of M". If smoke shows it's unreadable, bump the row height to 12dp and rerun smoke. Document in the post-smoke marker commit if you adjust.

- [ ] **Step 3.3: Delete obsolete drawables**

```powershell
git rm app/src/main/res/drawable/notif_accent_rail.xml
git rm app/src/main/res/drawable/notif_chip_bg.xml
```

- [ ] **Step 3.4: Verify layouts compile**

```powershell
./gradlew :app:assembleDebug
```

Expected: AAPT fails because `RovaRecordingService.renderRemoteView` still references `R.id.notif_rail`, `R.id.notif_chip_bg`, `R.id.notif_chip_icon` from Phase 2 — those IDs are gone. This compile failure is **expected** — Task 4 fixes the renderer + Task 5 fixes the service binder. Do NOT commit Step 3 alone; Task 3 + Task 4 + Task 5 land as a compile-gate split (RED → … → GREEN).

If you accidentally got to a GREEN compile after step 3.4, something is wrong — the renderer + service binder still reference the dropped IDs. Diagnose before proceeding.

- [ ] **Step 3.5: HOLD on commit until Task 5 compile-gate passes**

Stage the layout changes but do **not commit** yet:

```powershell
git add app/src/main/res/layout/notif_collapsed.xml app/src/main/res/layout/notif_expanded.xml
# do NOT commit yet — wait for Task 5 to land
```

---

## Task 4: Update `NotificationRenderer.kt` + tests

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/service/notification/NotificationRenderer.kt`
- Modify: `app/src/test/java/com/aritr/rova/service/notification/NotificationRendererTest.kt`

Renderer LOSES `chipContentDescriptionRes` and GAINS `dots`, `titleColor`, `surfaceRes`.

- [ ] **Step 4.1: Update `NotificationRenderer.kt`**

Replace the entire file body with:

```kotlin
package com.aritr.rova.service.notification

import androidx.annotation.ColorInt
import androidx.annotation.DrawableRes
import androidx.annotation.LayoutRes
import com.aritr.rova.R

/**
 * M5 Phase 3 §7 — bind-plan emitted by [toBindPlan]. Pure data; the
 * service consumes it to inflate + bind a real RemoteViews tree. No
 * Android RemoteViews / Context calls in this file.
 *
 * Phase 3 deltas vs Phase 2:
 *   - REMOVED chipContentDescriptionRes (chip dropped from layouts)
 *   - ADDED dots: DotsPlan (clip-progress row, per spec §4.3)
 *   - ADDED titleColor: Int? (null = use Compat.Notification.Title default,
 *                              non-null = MergeComplete green)
 *   - ADDED surfaceRes: DrawableRes (notif_surface vs notif_surface_complete)
 *
 * Spec: docs/superpowers/specs/2026-05-28-notification-redesign-phase3-design.md
 */
data class NotificationBindPlan(
    @LayoutRes val layoutCollapsedRes: Int,
    @LayoutRes val layoutExpandedRes: Int,
    val title: String,
    val body: String,
    val collapsedTail: String?,
    @ColorInt val accent: Int,
    @DrawableRes val iconRes: Int,
    val progress: NotificationProgress?,
    val isComplete: Boolean,
    val dots: DotsPlan,
    @ColorInt val titleColor: Int?,
    @DrawableRes val surfaceRes: Int
)

fun NotificationState.toBindPlan(): NotificationBindPlan {
    val copy = toCopy()
    val complete = this is NotificationState.MergeComplete
    return NotificationBindPlan(
        layoutCollapsedRes = R.layout.notif_collapsed,
        layoutExpandedRes = R.layout.notif_expanded,
        title = copy.title,
        body = copy.body,
        collapsedTail = collapsedTailFor(this),
        accent = toAccent(),
        iconRes = toIconRes(),
        progress = toProgress(),
        isComplete = complete,
        dots = toDotsPlan(),
        titleColor = if (complete) NotificationChannelConfig.ACCENT_COMPLETE else null,
        surfaceRes = if (complete) R.drawable.notif_surface_complete else R.drawable.notif_surface
    )
}

private fun collapsedTailFor(state: NotificationState): String? = when (state) {
    is NotificationState.ClipRecording -> state.etaSecondsRemaining?.let { "${formatMmSsForTail(it)} remaining" }
    is NotificationState.GapWaiting -> state.nextInLabel
    is NotificationState.Merging -> state.mergeProgressPercent?.let { "$it%" } ?: "${state.done} of ${state.total}"
    is NotificationState.MergeComplete -> if (state.clipCount == 1) "1 clip" else "${state.clipCount} clips"
}

private fun formatMmSsForTail(totalSeconds: Int): String {
    val s = totalSeconds.coerceAtLeast(0)
    return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
}
```

Notes:
- `chipCdFor` helper is deleted entirely.
- The dependency on `toAccent()`, `toIconRes()`, `toProgress()`, `toCopy()` (Phase 1 helpers) is unchanged.

- [ ] **Step 4.2: Update `NotificationRendererTest.kt`**

The Phase 2 tests assert on `chipContentDescriptionRes`. Drop those + add the new-field assertions.

Open `app/src/test/java/com/aritr/rova/service/notification/NotificationRendererTest.kt` and:

- **Remove** the 4 tests that mention `chipContentDescriptionRes`:
  - `ClipRecording binds blue accent, recording icon, recording chip CD`
  - `GapWaiting binds blue accent, waiting icon, waiting chip CD`
  - `Merging binds blue accent, merging icon, merging chip CD`
  - `MergeComplete binds green accent, complete icon, complete chip CD, isComplete true`

  Replace each with a slimmer test that asserts only `accent`, `iconRes`, and `isComplete` (no chip CD).

- **Add** these new tests at the end of the class:

```kotlin
    // ----- M5 Phase 3 §7 — new fields -----

    @Test fun `all four states delegate dots to toDotsPlan`() {
        val states: List<NotificationState> = listOf(
            NotificationState.ClipRecording(current = 2, total = 6),
            NotificationState.GapWaiting(nextNumber = 2, nextInLabel = "1:00", total = 6),
            NotificationState.Merging(done = 0, total = 6),
            NotificationState.MergeComplete(clipCount = 6)
        )
        states.forEach { s ->
            val plan = s.toBindPlan()
            val direct = s.toDotsPlan()
            assertEquals(direct.pills.size, plan.dots.pills.size)
            assertEquals(direct.accent, plan.dots.accent)
            assertEquals(direct.visible, plan.dots.visible)
        }
    }

    @Test fun `MergeComplete carries green title color, other states null`() {
        assertNull(NotificationState.ClipRecording(current = 1).toBindPlan().titleColor)
        assertNull(NotificationState.GapWaiting(nextNumber = 1, nextInLabel = "1:00").toBindPlan().titleColor)
        assertNull(NotificationState.Merging(done = 0, total = 6).toBindPlan().titleColor)
        assertEquals(
            NotificationChannelConfig.ACCENT_COMPLETE,
            NotificationState.MergeComplete(clipCount = 1).toBindPlan().titleColor
        )
    }

    @Test fun `MergeComplete uses notif_surface_complete, other states notif_surface`() {
        assertEquals(R.drawable.notif_surface, NotificationState.ClipRecording(current = 1).toBindPlan().surfaceRes)
        assertEquals(R.drawable.notif_surface, NotificationState.GapWaiting(nextNumber = 1, nextInLabel = "1:00").toBindPlan().surfaceRes)
        assertEquals(R.drawable.notif_surface, NotificationState.Merging(done = 0, total = 6).toBindPlan().surfaceRes)
        assertEquals(R.drawable.notif_surface_complete, NotificationState.MergeComplete(clipCount = 1).toBindPlan().surfaceRes)
    }
```

- [ ] **Step 4.3: Run notification tests in isolation**

```powershell
./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.service.notification.NotificationRendererTest" --tests "com.aritr.rova.service.notification.NotificationDotsRowTest"
```

Expected: tests compile and pass on the renderer + dots-row side, BUT `:app:compileDebugKotlin` for the main source set still fails because `RovaRecordingService.renderRemoteView` references removed fields/IDs. Don't panic — Task 5 closes the loop.

If renderer-side tests fail because of `chipContentDescriptionRes` leftovers in the test file: re-check Step 4.2 — every reference to that field must be gone.

- [ ] **Step 4.4: HOLD on commit until Task 5 compile-gate passes**

```powershell
git add app/src/main/java/com/aritr/rova/service/notification/NotificationRenderer.kt app/src/test/java/com/aritr/rova/service/notification/NotificationRendererTest.kt
# do NOT commit yet — wait for Task 5
```

---

## Task 5: Refactor `RovaRecordingService.kt` — drop rail/chip, add dots + surface + title color, drop setColorized

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/service/RovaRecordingService.kt`

This task lands as one atomic commit-gate alongside Task 3 + Task 4. After this step, `:app:compileDebugKotlin` MUST be green.

- [ ] **Step 5.1: Update imports**

Open `RovaRecordingService.kt` and ensure these imports are present (most are from Phase 2; add `Color` if missing):

```kotlin
import android.graphics.Color
import android.view.View
import android.widget.RemoteViews
import com.aritr.rova.service.notification.DotState
import com.aritr.rova.service.notification.DotsPlan
import com.aritr.rova.service.notification.NotificationBindPlan
import com.aritr.rova.service.notification.toBindPlan
```

- [ ] **Step 5.2: Replace `createNotification(state, sessionId)` body**

The Phase 2 body still calls `.setColorized(plan.isComplete)`. Phase 3 drops that line + drops the rail/chip RemoteViews wiring. Replace the function body with:

```kotlin
private fun createNotification(state: NotificationState, sessionId: String?): Notification {
    val plan = state.toBindPlan()
    val channelId = state.toChannelId()
    val ongoing = !state.isDismissible()
    val autoCancel = state.isDismissible()

    val openPendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    val collapsed = renderRemoteView(plan, expanded = false)
    val expanded = renderRemoteView(plan, expanded = true)

    // M5 Phase 3 §3.2: drop setColorized — title color + dots + green
    // border on notif_surface_complete carry the celebratory signal.
    val builder = NotificationCompat.Builder(this, channelId)
        .setContentTitle(plan.title)
        .setContentText(plan.body)
        .setSmallIcon(plan.iconRes)
        .setColor(plan.accent)
        .setContentIntent(openPendingIntent)
        .setOngoing(ongoing)
        .setAutoCancel(autoCancel)
        .setOnlyAlertOnce(!plan.isComplete)
        .setVisibility(
            if (plan.isComplete) NotificationCompat.VISIBILITY_PRIVATE
            else NotificationCompat.VISIBILITY_PUBLIC
        )
        .setShowWhen(plan.isComplete)
        .setStyle(NotificationCompat.DecoratedCustomViewStyle())
        .setCustomContentView(collapsed)
        .setCustomBigContentView(expanded)

    state.toActionSpecs().forEach { spec ->
        val intent = buildActionIntent(spec, sessionId)
        val pendingIntent = PendingIntent.getActivity(
            this,
            spec.key.ordinal + 100,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val action = NotificationCompat.Action.Builder(spec.iconRes, getString(spec.labelRes), pendingIntent)
            .build()
        builder.addAction(action)
    }

    return builder.build()
}
```

- [ ] **Step 5.3: Replace `renderRemoteView(plan, expanded)` body**

```kotlin
private fun renderRemoteView(plan: NotificationBindPlan, expanded: Boolean): RemoteViews {
    val layoutRes = if (expanded) plan.layoutExpandedRes else plan.layoutCollapsedRes
    val rv = RemoteViews(packageName, layoutRes)

    // Surface drawable — runtime swap between default + complete variant.
    rv.setInt(R.id.notif_root, "setBackgroundResource", plan.surfaceRes)

    // Title + optional explicit color (MergeComplete only).
    rv.setTextViewText(R.id.notif_title, plan.title)
    plan.titleColor?.let { rv.setTextColor(R.id.notif_title, it) }

    if (expanded) {
        rv.setTextViewText(R.id.notif_body, plan.body)
        bindDotsRow(rv, plan.dots)
        bindProgress(rv, plan.progress)
    } else {
        if (plan.collapsedTail != null) {
            rv.setTextViewText(R.id.notif_tail, plan.collapsedTail)
            rv.setViewVisibility(R.id.notif_tail, View.VISIBLE)
        } else {
            rv.setViewVisibility(R.id.notif_tail, View.GONE)
        }
    }

    return rv
}

/**
 * M5 Phase 3 §4.3 — bind 8 pre-allocated pill slots from [DotsPlan].
 * Each slot: tint via setColorFilter on the background drawable +
 * toggle visibility per pill state. Last slot (notif_dot_7) doubles
 * as count-pill carrier via the overlay TextView.
 */
private fun bindDotsRow(rv: RemoteViews, dots: DotsPlan) {
    if (!dots.visible) {
        rv.setViewVisibility(R.id.notif_dots_row, View.GONE)
        return
    }
    rv.setViewVisibility(R.id.notif_dots_row, View.VISIBLE)
    rv.setContentDescription(R.id.notif_dots_row, dots.contentDescription)

    val slotIds = intArrayOf(
        R.id.notif_dot_0, R.id.notif_dot_1, R.id.notif_dot_2, R.id.notif_dot_3,
        R.id.notif_dot_4, R.id.notif_dot_5, R.id.notif_dot_6, R.id.notif_dot_7
    )

    val doneColor = dots.accent
    val currentColor = (dots.accent and 0x00FFFFFF) or 0x66000000  // 40% alpha
    val todoColor = 0x1FFFFFFF                                      // 12% alpha white
    val countBgColor = 0x14FFFFFF                                   // 8% alpha white

    slotIds.forEachIndexed { index, viewId ->
        val pill = dots.pills.getOrNull(index)
        if (pill == null) {
            rv.setViewVisibility(viewId, View.GONE)
            // Make sure the count-overlay sibling on slot 7 is also hidden.
            if (index == 7) rv.setViewVisibility(R.id.notif_dot_count_label, View.GONE)
        } else {
            rv.setViewVisibility(viewId, View.VISIBLE)
            val tint = when (pill.kind) {
                DotState.Kind.DONE -> doneColor
                DotState.Kind.CURRENT -> currentColor
                DotState.Kind.TODO -> todoColor
                DotState.Kind.COUNT_PILL -> countBgColor
            }
            rv.setInt(viewId, "setColorFilter", tint)

            if (index == 7) {
                if (pill.kind == DotState.Kind.COUNT_PILL && pill.countLabel != null) {
                    rv.setTextViewText(R.id.notif_dot_count_label, pill.countLabel)
                    rv.setViewVisibility(R.id.notif_dot_count_label, View.VISIBLE)
                } else {
                    rv.setViewVisibility(R.id.notif_dot_count_label, View.GONE)
                }
            }
        }
    }
}

private fun bindProgress(rv: RemoteViews, progress: NotificationProgress?) {
    if (progress == null) {
        rv.setViewVisibility(R.id.notif_progress, View.GONE)
        return
    }
    rv.setProgressBar(R.id.notif_progress, progress.max, progress.current, progress.indeterminate)
    rv.setViewVisibility(R.id.notif_progress, View.VISIBLE)
    val cd = if (progress.indeterminate) {
        getString(R.string.notification_progress_cd_indeterminate)
    } else {
        val percent = if (progress.max > 0) (progress.current * 100 / progress.max) else 0
        getString(R.string.notification_progress_cd_determinate, percent)
    }
    rv.setContentDescription(R.id.notif_progress, cd)
}
```

The `currentColor` math uses bitwise `(accent and 0x00FFFFFF) or 0x66000000` to overwrite the alpha byte while preserving the RGB. ARGB packing: byte 3 = alpha. `0x66` = 40% alpha. Verified by hand on `ACCENT_RECORDING = 0xFF5B7FFF`: → `0x665B7FFF`. Same for `ACCENT_COMPLETE = 0xFF34D399`: → `0x6634D399`.

- [ ] **Step 5.4: Compile gate (atomic with Task 3 + Task 4 changes)**

```powershell
./gradlew :app:compileDebugKotlin
```

Expected: `BUILD SUCCESSFUL`.

If a compile error names `R.id.notif_rail` / `notif_chip_bg` / `notif_chip_icon` / `chipContentDescriptionRes`: revisit Task 3 (layouts) + Task 4 (renderer) — those references must be gone.

- [ ] **Step 5.5: Run full notification test suite**

```powershell
./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.service.notification.*"
```

Expected: **66 (Phase 1+2) − 4 (dropped chip CD) + 17 (Phase 2 renderer intact) + 12 (Phase 3 DotsRow) + 3 (Phase 3 renderer new fields) = 74 tests pass / 0 failed.** Math: see plan-level test count §11 of spec.

If the count is off, diff carefully against Task 4 step 4.2 — the "drop 4 chip CD tests + add 3 new tests" instruction is exact.

- [ ] **Step 5.6: Lint gate**

```powershell
./gradlew :app:lintDebug
```

Expected: `BUILD SUCCESSFUL`. No new `UnusedResources` warnings — and the 4 `notification_chip_cd_*` strings still exist in `strings.xml` (we delete them in Task 6 to avoid double-failure risk in this compile-gate split).

- [ ] **Step 5.7: Single atomic commit (Tasks 3 + 4 + 5)**

```powershell
git add app/src/main/res/layout/notif_collapsed.xml \
        app/src/main/res/layout/notif_expanded.xml \
        app/src/main/java/com/aritr/rova/service/notification/NotificationRenderer.kt \
        app/src/test/java/com/aritr/rova/service/notification/NotificationRendererTest.kt \
        app/src/main/java/com/aritr/rova/service/RovaRecordingService.kt
git rm app/src/main/res/drawable/notif_accent_rail.xml app/src/main/res/drawable/notif_chip_bg.xml
git commit -m "feat(notif): replace rail+chip with clip-dots row, surface, title color

M5 Phase 3 §3.2 + §4.1-4.4 + §7. Atomic compile-gate split:
- Layouts drop rail + chip; gain notif_root surface bg + 8-slot dots row
- Renderer: drop chipContentDescriptionRes; add dots/titleColor/surfaceRes
- Service binder: bindDotsRow + bindProgress + surfaceRes + title color
- Builder: drop setColorized (MergeComplete now dark glass + green title)
- Drawables: delete notif_accent_rail + notif_chip_bg (dead resources)

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Task 6: Cleanup unused chip CD strings + final gate

**Files:**
- Modify: `app/src/main/res/values/strings.xml`

- [ ] **Step 6.1: Delete the 4 unused chip CD strings**

Open `app/src/main/res/values/strings.xml`, locate the M5 Phase 2 block, and delete these 4 lines:

```xml
<string name="notification_chip_cd_recording">Recording in progress</string>
<string name="notification_chip_cd_waiting">Waiting for next clip</string>
<string name="notification_chip_cd_merging">Merging clips</string>
<string name="notification_chip_cd_complete">Recording complete</string>
```

Keep the 2 progress CD strings (`notification_progress_cd_*`) — still used.

- [ ] **Step 6.2: Lint + compile gate**

```powershell
./gradlew :app:lintDebug :app:assembleDebug
```

Expected: `BUILD SUCCESSFUL`, no `UnusedResources` warning for `notification_chip_cd_*`.

If lint flags an OTHER `UnusedResources` warning that wasn't there at Phase 2 baseline, investigate — we shouldn't be introducing dead resources.

- [ ] **Step 6.3: Full test suite**

```powershell
./gradlew :app:testDebugUnitTest
```

Expected: `BUILD SUCCESSFUL`, **≥ 1318 / 0-0-0**.

- [ ] **Step 6.4: Assemble**

```powershell
./gradlew :app:assembleDebug
```

Expected: `BUILD SUCCESSFUL`. APK at `app/build/outputs/apk/debug/app-debug.apk`.

- [ ] **Step 6.5: Install on a real device**

```powershell
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Real phone required (Android 14+ recommended for the AOSP-aligned shade blur).

- [ ] **Step 6.6: Real-device smoke — Phase 3 spec §8.3 checklist**

Walk every item; do not tick unless visually verified.

- [ ] ClipRecording at N=6 — dots [▆ ▆ ░ ░ ░ ░] in blue.
- [ ] GapWaiting at N=6, nextNumber=3 — dots [▆ ▆ ░ ░ ░ ░] in blue (DONE up through nextNumber-1).
- [ ] Merging at N=6, done=4 — dots [▆ ▆ ▆ ▆ ▆-translucent ░] in blue + progress fills below.
- [ ] MergeComplete at N=6 — dots [▆ ▆ ▆ ▆ ▆ ▆] in green; **title is green** (not white-on-green); card is dark glass with green hairline border.
- [ ] N=2 session — 2 wide pills, both legible.
- [ ] Synthetic N=10 — 7 state pills + "+3" count tag readable on Pixel-class device.
- [ ] No rail visible on any state. No secondary chip visible on any state.
- [ ] Translucent surface visible on Pixel-class device (custom area reads as glass against blurred shade).
- [ ] Collapsed view shows title + tail; tail right-aligned; no overflow at 48dp.
- [ ] TalkBack — dots row announces "Clip N of M" / "Merging, N of M done" / "All N clips complete".
- [ ] OS font scale 1.5× and 2× — title + body truncate with ellipsis; dots row height unchanged.
- [ ] OS light theme — text remains legible (Compat text appearance adapts).
- [ ] Lockscreen during recording — title + body visible.
- [ ] Lockscreen post-MergeComplete — body hidden.
- [ ] Channel topology in Settings → Apps → Rova → Notifications still shows 3 channels (session, complete, legacy).
- [ ] Tap "View in Library" — MainActivity opens on History tab.
- [ ] Tap "Share" — system chooser opens with merged MP4.
- [ ] Force-stop mid-merge — no orphan MergeComplete notification.

- [ ] **Step 6.7: Marker commit + cleanup of unused strings**

```powershell
git add app/src/main/res/values/strings.xml
git commit -m "chore(notif): drop unused Phase 2 chip contentDescription strings

M5 Phase 3 §9. The chip view was removed in the previous commit;
its contentDescription resources are now unreferenced. Drop them
to keep the resource surface tidy.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"

git commit --allow-empty -m "chore(m5): notification redesign Phase 3 ready for review

Real-device smoke checklist passes per
docs/superpowers/specs/2026-05-28-notification-redesign-phase3-design.md §8.3.
Test baseline: 1318+ / 0-0-0.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

- [ ] **Step 6.8: Push + open / update M5 PR**

```powershell
git push -u origin feat/notification-redesign-v1
```

If the M5 PR does not already exist:

```powershell
gh pr create --title "Milestone 5 — Notification redesign v1.0 (semantic + a11y + branded visual + mockup alignment)" --body "$(cat <<'EOF'
## Summary
- **Phase 1** (semantic + a11y): per-state copy with live numbers, dual channels (`rova_recording_session` LOW ongoing + `rova_recording_complete` DEFAULT one-shot), 4 pure helpers, MainActivity deep-link routing, ~1Hz rate limit, WCAG 2.2 AA contrast.
- **Phase 2** (branded visual): `DecoratedCustomViewStyle` + shared RemoteViews templates with rail + icon chip + fixed brand colors. Real-device smoke surfaced visual gap → motivated Phase 3.
- **Phase 3** (mockup alignment): rail + chip dropped; clip-dots row added; MergeComplete switched to dark glass card + green title; translucent surface drawable for the modern glass feel.
- Total notification tests: ~74 / 0 (Phase 1: 49, Phase 2 renderer: 13, Phase 3 dots + renderer fields: 12+3).
- Project test baseline: ≥ 1318 / 0-0-0.

## Specs + plans
- Phase 1 spec: `docs/superpowers/specs/2026-05-27-notification-redesign-v1-design.md`
- Phase 1 plan: `docs/superpowers/plans/2026-05-27-notification-redesign-v1.md`
- Phase 2 spec: `docs/superpowers/specs/2026-05-28-notification-redesign-phase2-design.md`
- Phase 2 plan: `docs/superpowers/plans/2026-05-28-notification-redesign-phase2.md`
- Phase 3 spec: `docs/superpowers/specs/2026-05-28-notification-redesign-phase3-design.md`
- Phase 3 plan: `docs/superpowers/plans/2026-05-28-notification-redesign-phase3.md`

## Out of scope (follow-on tasks)
- Rova launcher icon redesign (adaptive icon + monochrome) — separate task.
- Per-OEM tuning of translucent surface alpha if Samsung/Xiaomi smoke surfaces regressions.

## Test plan
- [x] `./gradlew :app:lintDebug` green (all `check*` tasks)
- [x] `./gradlew :app:testDebugUnitTest` green (≥ 1318 / 0-0-0)
- [x] `./gradlew :app:assembleDebug` green
- [x] Real-device smoke checklist per Phase 3 spec §8.3 passes on Android 14+
- [x] TalkBack walkthrough passes
- [x] Lockscreen visibility behaves per spec
- [x] OS font scale 1.5× / 2× truncates gracefully

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

If the M5 PR already exists, just push — GitHub auto-updates the PR.

---

## Self-review

**1. Spec coverage:**

| Spec section | Implementing task |
|---|---|
| §1 Context | informational |
| §2 In scope items 1-7 | Tasks 2 (helper), 3 (layouts + drawables), 4 (renderer), 5 (service), 6 (strings cleanup) |
| §2.1 NO-GO | enforced by task scope; no task introduces 5th state / schema bump / OS-header replacement / pill-action-buttons / collapsed dots |
| §3.1 dot cap | Task 2 (helper logic) + Task 5.3 (slot 7 binding) |
| §3.2 MergeComplete dark glass + green title | Task 4 (titleColor/surfaceRes fields) + Task 5.2 (drop setColorized) + Task 5.3 (setTextColor + setBackgroundResource) |
| §4.1 collapsed layout | Task 3.1 |
| §4.2 expanded layout | Task 3.2 |
| §4.3 dot patterns + tint mapping | Task 2 (kinds) + Task 5.3 (color math) |
| §4.4 translucent surface | Task 1 (drawables) + Task 3 (root bg) + Task 5.3 (runtime swap) |
| §5 Accessibility | Task 3 (importantForAccessibility on pill slots) + Task 5.3 (row-level CD) + Task 6.1 (drop dead strings) |
| §6 New strings | none added (in-code CDs); decision baked in |
| §7 Helper API | Task 2 (DotState + DotsPlan + toDotsPlan) + Task 4 (NotificationBindPlan field deltas) |
| §8.1 Pure JVM tests (~14 new) | Task 2 (12 dots tests) + Task 4.2 (3 renderer field tests) — 15 total, matches spec |
| §8.3 Real-device smoke | Task 6.6 |
| §9 Unused chip CD strings | Task 6.1 |
| §10 Risks | mitigations baked into Tasks 2/3/5; documented for OEM-skin and theme-adaptation risks |
| §11 Acceptance criteria | Task 6 fully gates them |
| §12 Follow-on | out of scope; no task |

No gaps.

**2. Placeholder scan.** No "TBD" / "TODO" / "appropriate" / "implement later" / vague-fix instructions. Code blocks are complete and runnable.

**3. Type consistency:**
- `DotState.Kind` enum — defined in Task 2.3, consumed in Task 5.3 `when` branch.
- `DotsPlan` field names — `pills`, `accent`, `contentDescription`, `visible` — same in Task 2 helper + Task 2 tests + Task 4 plan + Task 5 binder.
- `NotificationBindPlan` field deltas — DROPS `chipContentDescriptionRes`, ADDS `dots`, `titleColor`, `surfaceRes`. Same in Task 4 type + Task 4 tests + Task 5 service binder.
- R-resource ids:
  - `R.layout.notif_collapsed` / `R.layout.notif_expanded` — Task 3 rewrites these.
  - `R.id.notif_root`, `R.id.notif_title`, `R.id.notif_body`, `R.id.notif_tail`, `R.id.notif_progress` — defined in Task 3 layouts, consumed in Task 5 binder.
  - `R.id.notif_dots_row`, `R.id.notif_dot_0..notif_dot_7`, `R.id.notif_dot_count_label`, `R.id.notif_dot_7_container` — defined in Task 3.2, consumed in Task 5.3.
  - `R.drawable.notif_surface` / `R.drawable.notif_surface_complete` / `R.drawable.notif_dot_pill` — Task 1 creates, Tasks 3+4+5 reference.
  - Dropped: `R.drawable.notif_accent_rail`, `R.drawable.notif_chip_bg`, `R.id.notif_rail`, `R.id.notif_chip_bg`, `R.id.notif_chip_icon` — Task 3 deletes layouts + drawables; Task 5 ensures no service-side references remain.
  - `R.string.notification_chip_cd_*` (4) — Task 6.1 deletes.
  - `R.string.notification_progress_cd_*` (2) — kept; Task 5.3 still references.
- `NotificationChannelConfig.ACCENT_RECORDING` / `ACCENT_COMPLETE` — Phase 1 constants, referenced unchanged.

All consistent.

Plan complete.
