# Notification Redesign — Phase 2 (Visual Skin) — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the stock `NotificationCompat.Builder` rendering with `DecoratedCustomViewStyle` + shared RemoteViews templates so the recording notification carries Rova's brand language (accent rail + icon chip + fixed brand colors) in the customizable content area, on top of the system-imposed Android-12+ header chrome.

**Architecture:** One new pure helper (`NotificationRenderer.kt`) emits a `NotificationBindPlan` per `NotificationState` (no Android calls). The service inflates one of two shared layouts (`notif_collapsed.xml`, `notif_expanded.xml`), binds the plan's fields to the RemoteViews, applies `DecoratedCustomViewStyle`, and keeps Phase 1's channel/action/visibility wiring untouched. All 4 sealed-interface states share the same XML; the renderer toggles visibility/text/tint.

**Tech Stack:** Kotlin 2.2.10, AGP 9.2.1, Compose BOM 2025.01.01, JUnit 4, `androidx.core:core-ktx` `NotificationCompat` + `DecoratedCustomViewStyle`, RemoteViews, shape drawables.

**Spec:** [`docs/superpowers/specs/2026-05-28-notification-redesign-phase2-design.md`](../specs/2026-05-28-notification-redesign-phase2-design.md)

**Branch:** `feat/notification-redesign-v1` (folds into the M5 PR alongside Phase 1 commits `38f2212..4967f9c`)

**Last commit:** `5364706` (Phase 2 spec)

---

## File map

| Path | Disposition |
|---|---|
| `app/src/main/res/values/strings.xml` | append 6 new entries (4 chip CD + 2 progress CD templates) |
| `app/src/main/res/drawable/notif_accent_rail.xml` | **new** 4dp solid shape (runtime-tinted) |
| `app/src/main/res/drawable/notif_chip_bg.xml` | **new** 32dp rounded-square shape (runtime-tinted) |
| `app/src/main/res/layout/notif_collapsed.xml` | **new** 48dp single-row template |
| `app/src/main/res/layout/notif_expanded.xml` | **new** ≤252dp two-row template |
| `app/src/main/java/com/aritr/rova/service/notification/NotificationRenderer.kt` | **new** — pure: `NotificationState → NotificationBindPlan` |
| `app/src/test/java/com/aritr/rova/service/notification/NotificationRendererTest.kt` | **new** — pure-JVM tests |
| `app/src/main/java/com/aritr/rova/service/RovaRecordingService.kt` | modify `createNotification(state, sessionId)` to apply `DecoratedCustomViewStyle` + bind RemoteViews |

No Phase 1 file is structurally changed. `NotificationCopy.kt`, `NotificationIconRes.kt`, `NotificationChannelConfig.kt`, `NotificationActionSpec.kt` stay untouched — the renderer consumes them.

---

## Task 0: Bootstrap — verify Phase 1 baseline

**Files:** none modified

- [ ] **Step 0.1: Confirm branch + tip**

```bash
git log --oneline -1
git rev-parse --abbrev-ref HEAD
```

Expected:
- tip: `5364706 docs(notif): add M5 Phase 2 (visual skin) design spec`
- branch: `feat/notification-redesign-v1`

If different, stop and ask the owner.

- [ ] **Step 0.2: Verify green baseline**

```powershell
./gradlew :app:lintDebug :app:testDebugUnitTest :app:assembleDebug
```

Expected: `BUILD SUCCESSFUL`. Test summary `1283 tests completed, 0 failed`. All 25 `check*` tasks pass.

If anything fails, STOP and triage before touching Phase 2 code.

---

## Task 1: Add Phase-2 strings

**Files:**
- Modify: `app/src/main/res/values/strings.xml`

- [ ] **Step 1.1: Append 6 new entries to `strings.xml`**

Locate the closing `</resources>` tag and insert these entries immediately before it (after the M5 Phase 1 block):

```xml
    <!-- M5 Phase 2: chip contentDescription per state -->
    <string name="notification_chip_cd_recording">Recording in progress</string>
    <string name="notification_chip_cd_waiting">Waiting for next clip</string>
    <string name="notification_chip_cd_merging">Merging clips</string>
    <string name="notification_chip_cd_complete">Recording complete</string>

    <!-- M5 Phase 2: progress contentDescription -->
    <string name="notification_progress_cd_determinate">Progress, %1$d percent</string>
    <string name="notification_progress_cd_indeterminate">In progress</string>
```

- [ ] **Step 1.2: Verify compile**

```powershell
./gradlew :app:assembleDebug
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 1.3: Commit**

```bash
git add app/src/main/res/values/strings.xml
git commit -m "feat(notif): add Phase 2 chip + progress contentDescription strings

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Task 2: Add the two shape drawables

**Files:**
- Create: `app/src/main/res/drawable/notif_accent_rail.xml`
- Create: `app/src/main/res/drawable/notif_chip_bg.xml`

- [ ] **Step 2.1: Write `notif_accent_rail.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android"
    android:shape="rectangle">
    <!-- White by default; the service tints at runtime via setColorFilter
         on the parent ImageView's background. -->
    <solid android:color="#FFFFFFFF" />
</shape>
```

- [ ] **Step 2.2: Write `notif_chip_bg.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android"
    android:shape="rectangle">
    <!-- White by default; the service tints at runtime via setColorFilter
         on the parent ImageView's background. 8dp corner radius matches
         the in-app HUD chip in RecordChromeTokens. -->
    <solid android:color="#FFFFFFFF" />
    <corners android:radius="8dp" />
</shape>
```

- [ ] **Step 2.3: Verify drawables compile**

```powershell
./gradlew :app:assembleDebug
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 2.4: Commit**

```bash
git add app/src/main/res/drawable/notif_accent_rail.xml app/src/main/res/drawable/notif_chip_bg.xml
git commit -m "feat(notif): add Phase 2 accent-rail + chip-bg shape drawables

Both are white solids by default; the service tints at runtime via
setColorFilter so the same drawable serves blue (recording-session)
and green (MergeComplete) accents.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Task 3: Add the two shared RemoteViews layouts

**Files:**
- Create: `app/src/main/res/layout/notif_collapsed.xml`
- Create: `app/src/main/res/layout/notif_expanded.xml`

Both layouts use only RemoteViews-safe widgets (`FrameLayout`, `LinearLayout`, `TextView`, `ImageView`, `ProgressBar`). No `ConstraintLayout`, no custom views.

- [ ] **Step 3.1: Write `notif_collapsed.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="48dp"
    android:orientation="horizontal"
    android:gravity="center_vertical">

    <!-- Accent rail: 4dp wide, full height. Tinted at runtime. -->
    <ImageView
        android:id="@+id/notif_rail"
        android:layout_width="4dp"
        android:layout_height="match_parent"
        android:background="@drawable/notif_accent_rail"
        android:importantForAccessibility="no"
        android:contentDescription="@null" />

    <!-- Chip: 32dp rounded-square, tinted at runtime, white icon centered. -->
    <FrameLayout
        android:layout_width="32dp"
        android:layout_height="32dp"
        android:layout_marginStart="12dp">

        <ImageView
            android:id="@+id/notif_chip_bg"
            android:layout_width="match_parent"
            android:layout_height="match_parent"
            android:background="@drawable/notif_chip_bg"
            android:importantForAccessibility="no"
            android:contentDescription="@null" />

        <ImageView
            android:id="@+id/notif_chip_icon"
            android:layout_width="18dp"
            android:layout_height="18dp"
            android:layout_gravity="center" />
    </FrameLayout>

    <!-- Title — takes remaining horizontal weight. -->
    <TextView
        android:id="@+id/notif_title"
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        android:layout_weight="1"
        android:layout_marginStart="12dp"
        android:layout_marginEnd="8dp"
        android:singleLine="true"
        android:ellipsize="end"
        style="@style/TextAppearance.Compat.Notification.Title" />

    <!-- Tail meta — right-aligned, dimmed via alpha. -->
    <TextView
        android:id="@+id/notif_tail"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_marginEnd="12dp"
        android:singleLine="true"
        android:ellipsize="end"
        android:alpha="0.7"
        style="@style/TextAppearance.Compat.Notification" />

</LinearLayout>
```

- [ ] **Step 3.2: Write `notif_expanded.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:minHeight="72dp"
    android:orientation="horizontal">

    <!-- Accent rail: 4dp wide, full height. Tinted at runtime. -->
    <ImageView
        android:id="@+id/notif_rail"
        android:layout_width="4dp"
        android:layout_height="match_parent"
        android:background="@drawable/notif_accent_rail"
        android:importantForAccessibility="no"
        android:contentDescription="@null" />

    <!-- Chip column. -->
    <FrameLayout
        android:layout_width="32dp"
        android:layout_height="32dp"
        android:layout_marginStart="12dp"
        android:layout_marginTop="12dp">

        <ImageView
            android:id="@+id/notif_chip_bg"
            android:layout_width="match_parent"
            android:layout_height="match_parent"
            android:background="@drawable/notif_chip_bg"
            android:importantForAccessibility="no"
            android:contentDescription="@null" />

        <ImageView
            android:id="@+id/notif_chip_icon"
            android:layout_width="18dp"
            android:layout_height="18dp"
            android:layout_gravity="center" />
    </FrameLayout>

    <!-- Content column. -->
    <LinearLayout
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        android:layout_weight="1"
        android:layout_marginStart="12dp"
        android:layout_marginEnd="12dp"
        android:layout_marginTop="10dp"
        android:layout_marginBottom="12dp"
        android:orientation="vertical">

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

        <ProgressBar
            android:id="@+id/notif_progress"
            style="?android:attr/progressBarStyleHorizontal"
            android:layout_width="match_parent"
            android:layout_height="4dp"
            android:layout_marginTop="6dp"
            android:visibility="gone" />

    </LinearLayout>

</LinearLayout>
```

- [ ] **Step 3.3: Verify layouts compile**

```powershell
./gradlew :app:assembleDebug
```

Expected: `BUILD SUCCESSFUL`. AAPT2 errors would name the offending element if so.

- [ ] **Step 3.4: Commit**

```bash
git add app/src/main/res/layout/notif_collapsed.xml app/src/main/res/layout/notif_expanded.xml
git commit -m "feat(notif): add Phase 2 shared RemoteViews layouts

notif_collapsed.xml — 48dp single-row template.
notif_expanded.xml — wrap_content (≤252dp) two-row template.
Both shared across all 4 NotificationState variants; the renderer
toggles visibility/text/tint at bind time.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Task 4: Create `NotificationRenderer.kt` + tests (TDD)

**Files:**
- Create: `app/src/main/java/com/aritr/rova/service/notification/NotificationRenderer.kt`
- Create: `app/src/test/java/com/aritr/rova/service/notification/NotificationRendererTest.kt`

The renderer is pure: it consumes a `NotificationState` (Phase 1) and emits a `NotificationBindPlan`. No Android `RemoteViews` / `Context` calls — that boundary is the service.

- [ ] **Step 4.1: Write the failing tests**

Create `app/src/test/java/com/aritr/rova/service/notification/NotificationRendererTest.kt`:

```kotlin
package com.aritr.rova.service.notification

import com.aritr.rova.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M5 Phase 2 — pure-JVM tests for [NotificationRenderer.toBindPlan].
 * No RemoteViews, no Context, no Android resource resolution beyond
 * compile-time int constants. The plan is plain data; the service
 * consumes it to inflate + bind a real RemoteViews tree.
 */
class NotificationRendererTest {

    // ----- layout-res selection (shared across all 4 states) -----

    @Test fun `all states use the shared collapsed + expanded layouts`() {
        val states: List<NotificationState> = listOf(
            NotificationState.ClipRecording(current = 1),
            NotificationState.GapWaiting(nextNumber = 2, nextInLabel = "1:00"),
            NotificationState.Merging(done = 0, total = 6),
            NotificationState.MergeComplete(clipCount = 1)
        )
        states.forEach { s ->
            val plan = s.toBindPlan()
            assertEquals(R.layout.notif_collapsed, plan.layoutCollapsedRes)
            assertEquals(R.layout.notif_expanded, plan.layoutExpandedRes)
        }
    }

    // ----- accent + icon + chip CD wiring per state -----

    @Test fun `ClipRecording binds blue accent, recording icon, recording chip CD`() {
        val plan = NotificationState.ClipRecording(current = 1).toBindPlan()
        assertEquals(NotificationChannelConfig.ACCENT_RECORDING, plan.accent)
        assertEquals(R.drawable.ic_notif_recording, plan.iconRes)
        assertEquals(R.string.notification_chip_cd_recording, plan.chipContentDescriptionRes)
        assertFalse(plan.isComplete)
    }

    @Test fun `GapWaiting binds blue accent, waiting icon, waiting chip CD`() {
        val plan = NotificationState.GapWaiting(nextNumber = 1, nextInLabel = "1:00").toBindPlan()
        assertEquals(NotificationChannelConfig.ACCENT_RECORDING, plan.accent)
        assertEquals(R.drawable.ic_notif_waiting, plan.iconRes)
        assertEquals(R.string.notification_chip_cd_waiting, plan.chipContentDescriptionRes)
        assertFalse(plan.isComplete)
    }

    @Test fun `Merging binds blue accent, merging icon, merging chip CD`() {
        val plan = NotificationState.Merging(done = 0, total = 6).toBindPlan()
        assertEquals(NotificationChannelConfig.ACCENT_RECORDING, plan.accent)
        assertEquals(R.drawable.ic_notif_merging, plan.iconRes)
        assertEquals(R.string.notification_chip_cd_merging, plan.chipContentDescriptionRes)
        assertFalse(plan.isComplete)
    }

    @Test fun `MergeComplete binds green accent, complete icon, complete chip CD, isComplete true`() {
        val plan = NotificationState.MergeComplete(clipCount = 1).toBindPlan()
        assertEquals(NotificationChannelConfig.ACCENT_COMPLETE, plan.accent)
        assertEquals(R.drawable.ic_notif_complete, plan.iconRes)
        assertEquals(R.string.notification_chip_cd_complete, plan.chipContentDescriptionRes)
        assertTrue(plan.isComplete)
    }

    // ----- title + body delegate to Phase 1 toCopy() -----

    @Test fun `plan title + body match Phase 1 toCopy output`() {
        val state: NotificationState = NotificationState.MergeComplete(
            clipCount = 6, totalDurationSeconds = 300
        )
        val plan = state.toBindPlan()
        val copy = state.toCopy()
        assertEquals(copy.title, plan.title)
        assertEquals(copy.body, plan.body)
    }

    // ----- progress delegates to Phase 1 toProgress() -----

    @Test fun `ClipRecording progress is null (matches Phase 1)`() {
        val plan = NotificationState.ClipRecording(
            current = 1, etaSecondsRemaining = 18
        ).toBindPlan()
        assertNull(plan.progress)
    }

    @Test fun `GapWaiting with full countdown inputs surfaces determinate progress`() {
        val plan = NotificationState.GapWaiting(
            nextNumber = 2, nextInLabel = "4:42",
            nextStartsInSeconds = 60, gapTotalSeconds = 300
        ).toBindPlan()
        val p = plan.progress!!
        assertEquals(300, p.max)
        assertEquals(240, p.current)
        assertFalse(p.indeterminate)
    }

    @Test fun `Merging with percent surfaces determinate progress`() {
        val plan = NotificationState.Merging(
            done = 4, total = 6, mergeProgressPercent = 67
        ).toBindPlan()
        val p = plan.progress!!
        assertEquals(100, p.max)
        assertEquals(67, p.current)
        assertFalse(p.indeterminate)
    }

    @Test fun `Merging without percent surfaces indeterminate progress`() {
        val plan = NotificationState.Merging(
            done = 0, total = 6, mergeProgressPercent = null
        ).toBindPlan()
        val p = plan.progress!!
        assertTrue(p.indeterminate)
    }

    @Test fun `MergeComplete progress is null`() {
        val plan = NotificationState.MergeComplete(clipCount = 1).toBindPlan()
        assertNull(plan.progress)
    }

    // ----- collapsed tail per state -----

    @Test fun `ClipRecording collapsedTail is X SS remaining when eta present`() {
        val plan = NotificationState.ClipRecording(
            current = 2, total = 6, etaSecondsRemaining = 18
        ).toBindPlan()
        assertEquals("0:18 remaining", plan.collapsedTail)
    }

    @Test fun `ClipRecording collapsedTail is null when eta absent`() {
        val plan = NotificationState.ClipRecording(
            current = 2, total = 6, etaSecondsRemaining = null
        ).toBindPlan()
        assertNull(plan.collapsedTail)
    }

    @Test fun `GapWaiting collapsedTail is the nextInLabel`() {
        val plan = NotificationState.GapWaiting(
            nextNumber = 3, nextInLabel = "4:42"
        ).toBindPlan()
        assertEquals("4:42", plan.collapsedTail)
    }

    @Test fun `Merging collapsedTail is NN percent when percent present`() {
        val plan = NotificationState.Merging(
            done = 4, total = 6, mergeProgressPercent = 67
        ).toBindPlan()
        assertEquals("67%", plan.collapsedTail)
    }

    @Test fun `Merging collapsedTail is N of M when percent absent`() {
        val plan = NotificationState.Merging(
            done = 4, total = 6, mergeProgressPercent = null
        ).toBindPlan()
        assertEquals("4 of 6", plan.collapsedTail)
    }

    @Test fun `MergeComplete collapsedTail is N clip(s)`() {
        val one = NotificationState.MergeComplete(clipCount = 1).toBindPlan()
        val many = NotificationState.MergeComplete(clipCount = 6).toBindPlan()
        assertEquals("1 clip", one.collapsedTail)
        assertEquals("6 clips", many.collapsedTail)
    }
}
```

- [ ] **Step 4.2: Run, confirm fail**

```powershell
./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.service.notification.NotificationRendererTest"
```

Expected: compile failure — `NotificationRenderer`, `NotificationBindPlan`, `toBindPlan` are unresolved.

- [ ] **Step 4.3: Create `NotificationRenderer.kt`**

```kotlin
package com.aritr.rova.service.notification

import androidx.annotation.ColorInt
import androidx.annotation.DrawableRes
import androidx.annotation.LayoutRes
import androidx.annotation.StringRes
import com.aritr.rova.R

/**
 * M5 Phase 2 — bind-plan emitted by [toBindPlan]. Pure data; the
 * service consumes it to inflate + bind a real RemoteViews tree. No
 * Android RemoteViews / Context calls in this file — the boundary
 * mirrors Phase 1's pure-helper / service-as-seam pattern.
 *
 * Title + body + progress are forwarded from Phase 1 helpers
 * ([toCopy], [toProgress]) — never duplicated. Accent + icon + chip
 * CD + collapsedTail are Phase-2-specific.
 *
 * Spec: docs/superpowers/specs/2026-05-28-notification-redesign-phase2-design.md §7
 */
data class NotificationBindPlan(
    @LayoutRes val layoutCollapsedRes: Int,
    @LayoutRes val layoutExpandedRes: Int,
    val title: String,
    val body: String,
    val collapsedTail: String?,
    @ColorInt val accent: Int,
    @DrawableRes val iconRes: Int,
    @StringRes val chipContentDescriptionRes: Int,
    val progress: NotificationProgress?,
    val isComplete: Boolean
)

fun NotificationState.toBindPlan(): NotificationBindPlan {
    val copy = toCopy()
    return NotificationBindPlan(
        layoutCollapsedRes = R.layout.notif_collapsed,
        layoutExpandedRes = R.layout.notif_expanded,
        title = copy.title,
        body = copy.body,
        collapsedTail = collapsedTailFor(this),
        accent = toAccent(),
        iconRes = toIconRes(),
        chipContentDescriptionRes = chipCdFor(this),
        progress = toProgress(),
        isComplete = this is NotificationState.MergeComplete
    )
}

@StringRes
private fun chipCdFor(state: NotificationState): Int = when (state) {
    is NotificationState.ClipRecording -> R.string.notification_chip_cd_recording
    is NotificationState.GapWaiting -> R.string.notification_chip_cd_waiting
    is NotificationState.Merging -> R.string.notification_chip_cd_merging
    is NotificationState.MergeComplete -> R.string.notification_chip_cd_complete
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

- [ ] **Step 4.4: Run, confirm pass**

```powershell
./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.service.notification.NotificationRendererTest"
```

Expected: 17 tests pass / 0 failed.

- [ ] **Step 4.5: Commit**

```bash
git add app/src/main/java/com/aritr/rova/service/notification/NotificationRenderer.kt app/src/test/java/com/aritr/rova/service/notification/NotificationRendererTest.kt
git commit -m "feat(notif): add NotificationRenderer pure bind-plan helper

M5 Phase 2 §7. Consumes Phase 1 helpers (toCopy/toProgress/toAccent/
toIconRes) and emits NotificationBindPlan — the service binds these
fields to a real RemoteViews tree.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Task 5: Refactor service `createNotification` to `DecoratedCustomViewStyle` + RemoteViews binding

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/service/RovaRecordingService.kt`

The Phase 1 `createNotification(state, sessionId)` builds a stock notification. Phase 2 replaces the body so it applies `DecoratedCustomViewStyle` + inflates the shared layouts + binds the bind plan. Action row (`builder.addAction(...)`) stays untouched — system row keeps Phase 1 wiring.

- [ ] **Step 5.1: Add imports to `RovaRecordingService.kt`**

Insert these imports alphabetically into the existing import block at the top of the file (do NOT re-import anything already present):

```kotlin
import android.widget.RemoteViews
import com.aritr.rova.service.notification.NotificationBindPlan
import com.aritr.rova.service.notification.toBindPlan
```

- [ ] **Step 5.2: Replace the body of `createNotification(state, sessionId)`**

Find the existing function in the service. The Phase 1 body (from commit `9cdad4d`) starts:

```kotlin
private fun createNotification(state: NotificationState, sessionId: String?): Notification {
    val copy = state.toCopy()
    val channelId = state.toChannelId()
    val accent = state.toAccent()
    val iconRes = state.toIconRes()
    val ongoing = !state.isDismissible()
    val autoCancel = state.isDismissible()
    ...
```

Replace the ENTIRE function body with:

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

    val builder = NotificationCompat.Builder(this, channelId)
        // Title + text still set for accessibility fallback + lockscreen
        // preview, even though DecoratedCustomViewStyle overrides the
        // visible content area.
        .setContentTitle(plan.title)
        .setContentText(plan.body)
        .setSmallIcon(plan.iconRes)
        .setColor(plan.accent)
        .setColorized(plan.isComplete)
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

private fun renderRemoteView(plan: NotificationBindPlan, expanded: Boolean): RemoteViews {
    val layoutRes = if (expanded) plan.layoutExpandedRes else plan.layoutCollapsedRes
    val rv = RemoteViews(packageName, layoutRes)

    // Title + body.
    rv.setTextViewText(R.id.notif_title, plan.title)
    if (expanded) {
        rv.setTextViewText(R.id.notif_body, plan.body)
    }

    // Tail meta (collapsed only).
    if (!expanded) {
        if (plan.collapsedTail != null) {
            rv.setTextViewText(R.id.notif_tail, plan.collapsedTail)
            rv.setViewVisibility(R.id.notif_tail, View.VISIBLE)
        } else {
            rv.setViewVisibility(R.id.notif_tail, View.GONE)
        }
    }

    // Accent rail tint — solid color filter on the ImageView background.
    rv.setInt(R.id.notif_rail, "setBackgroundColor", plan.accent)

    // Chip background tint — solid color filter on the ImageView background.
    rv.setInt(R.id.notif_chip_bg, "setBackgroundColor", plan.accent)
    rv.setContentDescription(R.id.notif_chip_bg, getString(plan.chipContentDescriptionRes))

    // Chip icon.
    rv.setImageViewResource(R.id.notif_chip_icon, plan.iconRes)

    // Progress (expanded only).
    if (expanded) {
        val progress = plan.progress
        if (progress != null) {
            rv.setProgressBar(R.id.notif_progress, progress.max, progress.current, progress.indeterminate)
            rv.setViewVisibility(R.id.notif_progress, View.VISIBLE)
            val cd = if (progress.indeterminate) {
                getString(R.string.notification_progress_cd_indeterminate)
            } else {
                val percent = if (progress.max > 0) (progress.current * 100 / progress.max) else 0
                getString(R.string.notification_progress_cd_determinate, percent)
            }
            rv.setContentDescription(R.id.notif_progress, cd)
        } else {
            rv.setViewVisibility(R.id.notif_progress, View.GONE)
        }
    }

    return rv
}
```

Note: `View` is used inline (`View.VISIBLE` / `View.GONE`). Confirm `import android.view.View` is in the file's import block; if not, add it.

- [ ] **Step 5.3: Compile gate**

```powershell
./gradlew :app:compileDebugKotlin
```

Expected: `BUILD SUCCESSFUL`.

If a compile error names a missing symbol (e.g. `R.id.notif_title` unresolved), confirm Task 3 layouts were committed; AAPT regenerates the `R` class.

- [ ] **Step 5.4: Run full notification test suite**

```powershell
./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.service.notification.*"
```

Expected: 49 (Phase 1) + 17 (Phase 2 Task 4) = **66 tests pass / 0 failed**.

- [ ] **Step 5.5: Run lint gate**

```powershell
./gradlew :app:lintDebug
```

Expected: `BUILD SUCCESSFUL` + all 25 `check*` tasks pass.

If lint flags `NewApi` on any of the `RemoteViews` calls, the calls used (`setTextViewText`, `setViewVisibility`, `setImageViewResource`, `setProgressBar`, `setContentDescription`, `setInt`) are all available from API 1 / API 15 — well below our `minSdk = 24`. If `setColorStateList` or `setColorInt` warnings fire, the bind code in Step 5.2 deliberately avoids them.

- [ ] **Step 5.6: Commit**

```bash
git add app/src/main/java/com/aritr/rova/service/RovaRecordingService.kt
git commit -m "feat(notif): bind DecoratedCustomViewStyle RemoteViews per state

M5 Phase 2 §3 + §4. createNotification(state, sessionId) now applies
DecoratedCustomViewStyle and inflates notif_collapsed + notif_expanded
from a NotificationBindPlan; system header + action row preserved.
Accent rail + chip + icon + progress + chip contentDescription all
bind from the plan.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Task 6: Final gate — lint + tests + assemble + real-device smoke + PR

**Files:** none modified

- [ ] **Step 6.1: Full lint**

```powershell
./gradlew :app:lintDebug
```

Expected: `BUILD SUCCESSFUL`, all 25 `check*` tasks pass, no new lint warnings vs Phase 1 baseline.

- [ ] **Step 6.2: Full test suite**

```powershell
./gradlew :app:testDebugUnitTest
```

Expected: `BUILD SUCCESSFUL`, **≥ 1300 tests / 0 failed** (Phase 1 baseline 1283 + 17 Phase 2 = 1300).

- [ ] **Step 6.3: Assemble**

```powershell
./gradlew :app:assembleDebug
```

Expected: `BUILD SUCCESSFUL`. APK at `app/build/outputs/apk/debug/app-debug.apk`.

- [ ] **Step 6.4: Install on a real device**

```powershell
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Expected: `Success`. Real phone (Android 14+) required — emulators fail CameraX video recording per project policy.

- [ ] **Step 6.5: Real-device smoke (Phase 2 spec §8 — supersedes Phase 1 visual items)**

Walk through every item. Do not check the box unless verified visually.

- [ ] ClipRecording — collapsed shows blue rail + blue chip + white dot icon + "Recording · Clip 1 of N" + tail "0:XX remaining". Expanded shows progress hidden (per spec §4).
- [ ] GapWaiting — blue rail + blue chip + hourglass icon + "Waiting · Clip N of M next" + tail "M:SS". Expanded shows determinate countdown bar shrinking each second.
- [ ] Merging — blue rail + blue chip + merge-arrows icon. Expanded shows determinate fill bar advancing as merge runs. No system action buttons (empty row).
- [ ] MergeComplete — **green** rail + green chip + check icon + "Merge complete" + tail "N clips". Expanded shows full body + system actions [View in Library] [Share]. Swipe-dismissible.
- [ ] Lockscreen during recording — title row visible, body visible (`VISIBILITY_PUBLIC`).
- [ ] Lockscreen post-MergeComplete — body hidden (`VISIBILITY_PRIVATE`).
- [ ] TalkBack — each state announces chip CD + title + body + (progress percent if shown) + action labels.
- [ ] OS font scale 1.5× and 2× — title + body truncate with ellipsis instead of overflowing the 252dp budget.
- [ ] OS light theme — text remains legible (Compat text appearance adapts).
- [ ] Channel topology in Settings → Apps → Rova → Notifications still shows 3 channels (session, complete, legacy).
- [ ] Disable "Recording complete" channel only — session notif still posts; complete notif suppressed.
- [ ] Tap "View in Library" — MainActivity opens, History tab selected.
- [ ] Tap "Share" — system chooser opens with the merged MP4 (video/mp4).
- [ ] Force-stop mid-merge — no orphaned MergeComplete notification.

- [ ] **Step 6.6: Marker commit**

```bash
git commit --allow-empty -m "chore(m5): notification redesign Phase 2 ready for review

Real-device smoke checklist passes per
docs/superpowers/specs/2026-05-28-notification-redesign-phase2-design.md §8.
Test baseline: 1300 / 0-0-0.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

- [ ] **Step 6.7: Push and open M5 PR (covers Phase 1 + Phase 2)**

```bash
git push -u origin feat/notification-redesign-v1
gh pr create --title "Milestone 5 — Notification redesign v1.0 (semantic + a11y + branded visual)" --body "$(cat <<'EOF'
## Summary
- **Phase 1** (semantic + a11y): per-state copy with live numbers, dual channels (`rova_recording_session` LOW ongoing + `rova_recording_complete` DEFAULT one-shot), 4 pure helpers (`NotificationCopy`, `NotificationIconRes`, `NotificationChannelConfig`, `NotificationActionSpec`), MainActivity deep-link routing, ~1Hz rate limit, ~49 new JVM tests, WCAG 2.2 AA contrast verified.
- **Phase 2** (branded visual): `DecoratedCustomViewStyle` + shared RemoteViews templates (`notif_collapsed.xml` 48dp, `notif_expanded.xml` ≤252dp), accent rail + icon chip + fixed brand colors (blue `#5B7FFF` for recording-session, green `#34D399` for MergeComplete), 17 new pure-JVM tests on `NotificationRenderer`.
- Total notification tests: 66 / 0 (Phase 1: 49, Phase 2: 17).
- Project test baseline: ≥ 1300 / 0-0-0.

## Specs + plans
- Phase 1 spec: `docs/superpowers/specs/2026-05-27-notification-redesign-v1-design.md`
- Phase 1 plan: `docs/superpowers/plans/2026-05-27-notification-redesign-v1.md`
- Phase 2 spec: `docs/superpowers/specs/2026-05-28-notification-redesign-phase2-design.md`
- Phase 2 plan: `docs/superpowers/plans/2026-05-28-notification-redesign-phase2.md`

## Test plan
- [x] `./gradlew :app:lintDebug` green (25 check* tasks)
- [x] `./gradlew :app:testDebugUnitTest` green (≥ 1300 / 0-0-0)
- [x] `./gradlew :app:assembleDebug` green
- [x] Real-device smoke checklist per Phase 2 spec §8 passes on Android 14+
- [x] TalkBack walkthrough passes
- [x] Lockscreen visibility behaves per spec
- [x] OS font scale 1.5× / 2× truncates gracefully

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

Expected: PR URL returned.

---

## Self-review

**1. Spec coverage:**

| Spec section | Implementing task |
|---|---|
| §1 Context | informational |
| §2 In scope | Tasks 1–5 |
| §2 NO-GO | enforced by task scope; no task introduces 5th state / schema bump / OS-header replacement |
| §3 Architecture deltas (file map) | Tasks 1–5 (one task per file group) |
| §3 Data flow | Tasks 4 + 5 (renderer → bind plan → service binds) |
| §4 Visual contract (layout grid, per-state table, typography, collapsed view, expanded view) | Tasks 2 + 3 + 4 + 5 |
| §5 Accessibility (chip CD, progress CD, rail importantForAccessibility=no, Compat text appearance, WCAG contrast) | Tasks 1 + 3 + 4 + 5 |
| §6 New strings | Task 1 |
| §7 Helper API contract (NotificationBindPlan, toBindPlan) | Task 4 |
| §8 Testing — pure JVM (~16 tests) | Task 4 (17 tests — one extra for the shared-layout assertion) |
| §8 Testing — what we don't unit-test | enforced by task design |
| §8 Real-device smoke | Task 6.5 |
| §9 Lint + static-check gate impact | Tasks 5.5 + 6.1 |
| §10 Risks + mitigations | smoke checklist 6.5 catches the OEM-tinting + font-scale risks |
| §11 Acceptance criteria | Task 6 fully gates them |
| §12 Follow-on | out-of-scope per spec; no task |

No gaps.

**2. Placeholder scan:** no "TBD" / "TODO" / "appropriate" / "implement later" / "similar to Task N" / vague-fix instructions. Code blocks are complete and runnable.

**3. Type consistency:**
- `NotificationBindPlan` field names — same in Task 4 helper + Task 4 tests + Task 5 service binder (`layoutCollapsedRes`, `layoutExpandedRes`, `title`, `body`, `collapsedTail`, `accent`, `iconRes`, `chipContentDescriptionRes`, `progress`, `isComplete`).
- `toBindPlan` extension function — declared in Task 4, consumed in Task 5.
- `NotificationChannelConfig.ACCENT_RECORDING` / `ACCENT_COMPLETE` — Phase 1 constants, referenced in Task 4 tests (no new accent constants introduced).
- R-resource ids:
  - `R.layout.notif_collapsed` / `R.layout.notif_expanded` — Task 3 creates the XMLs, Tasks 4 + 5 reference them.
  - `R.id.notif_rail` / `notif_chip_bg` / `notif_chip_icon` / `notif_title` / `notif_body` / `notif_tail` / `notif_progress` — defined in Task 3 layouts, consumed in Task 5 binder.
  - `R.drawable.notif_accent_rail` / `notif_chip_bg` — Task 2 creates, Task 3 references.
  - `R.drawable.ic_notif_recording` / `ic_notif_waiting` / `ic_notif_merging` / `ic_notif_complete` — Phase 1, referenced in Task 4 tests + bound in Task 5.
  - `R.string.notification_chip_cd_recording` etc. — Task 1 creates, Tasks 4 + 5 reference.
  - `R.string.notification_progress_cd_determinate` / `_indeterminate` — Task 1 creates, Task 5 references.

All consistent.

Plan complete.
