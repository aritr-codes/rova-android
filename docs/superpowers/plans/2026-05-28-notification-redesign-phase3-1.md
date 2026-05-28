# Notification Redesign — Phase 3.1 (Polish) — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Three-fix polish pass on the Phase 3 notification UI: (1) make the dots row visually distinct by switching pills from `background` to `src` so `setColorFilter` actually tints them, (2) bind `weightSum` dynamically so visible pills fill the row regardless of count, (3) replace static body text with a Chronometer widget for ClipRecording + GapWaiting so countdowns tick second-by-second on-device. Plus typography pixel-match to the mockup.

**Architecture:** No new helper file. `NotificationRenderer.kt` gains a `ChronoSpec` data class + `toChronoSpec` extension; `NotificationBindPlan` gains a `chrono: ChronoSpec?` field. Layouts get a stacked TextView+Chronometer slot (one visible at a time), explicit typography, and dot pills bound via `src`. Service binder updates dot tint, weightSum, and Chronometer per state.

**Tech Stack:** Kotlin 2.2.10, AGP 9.2.1, Compose BOM 2025.01.01, JUnit 4, `androidx.core:core-ktx` `NotificationCompat`, RemoteViews, `Chronometer` (API 24+, `setCountDown`).

**Spec:** [`docs/superpowers/specs/2026-05-28-notification-redesign-phase3-1-design.md`](../specs/2026-05-28-notification-redesign-phase3-1-design.md)

**Branch:** `feat/notification-redesign-v1`
**Last commit:** Phase 3.1 spec commit (just landed)

---

## File map

| Path | Disposition |
|---|---|
| `app/src/main/res/layout/notif_collapsed.xml` | modify — explicit typography on title + tail |
| `app/src/main/res/layout/notif_expanded.xml` | modify — dots `background`→`src`, add Chronometer slot, explicit typography |
| `app/src/main/java/com/aritr/rova/service/notification/NotificationRenderer.kt` | modify — add `ChronoSpec` + `toChronoSpec` + `chrono` field on bind plan |
| `app/src/test/java/com/aritr/rova/service/notification/NotificationRendererTest.kt` | modify — 7 new chrono tests |
| `app/src/main/java/com/aritr/rova/service/RovaRecordingService.kt` | modify — dot tint via `setColorFilter`, dynamic `setFloat(weightSum)`, Chronometer bind, body visibility toggle |

No new file. No layout file added or removed.

---

## Task 0: Bootstrap — verify Phase 3 baseline

**Files:** none modified

- [ ] **Step 0.1: Confirm branch + tip**

```bash
git log --oneline -1
git rev-parse --abbrev-ref HEAD
```

Expected:
- tip ends with: `docs(notif): add M5 Phase 3.1 polish spec ...`
- branch: `feat/notification-redesign-v1`

- [ ] **Step 0.2: Verify green baseline**

```powershell
./gradlew :app:lintDebug :app:testDebugUnitTest :app:assembleDebug
```

Expected: `BUILD SUCCESSFUL`. Test summary `≥ 1313 / 0 failed`. All 25 `check*` tasks pass.

If anything fails, STOP and triage before touching Phase 3.1 code.

---

## Task 1: Layout polish — dot tinting + dynamic weightSum + typography + Chronometer slot

**Files:**
- Modify: `app/src/main/res/layout/notif_expanded.xml`
- Modify: `app/src/main/res/layout/notif_collapsed.xml`

- [ ] **Step 1.1: Rewrite `notif_expanded.xml`**

Replace the file with:

```xml
<?xml version="1.0" encoding="utf-8"?>
<!-- M5 Phase 3 §4.2 + Phase 3.1 §4.2/§4.4 — expanded layout.
     Vertical stack: title, body-OR-Chronometer (one visible at a time),
     dots row (8 pre-allocated pill slots; weightSum bound at runtime),
     progress (optional). Surface background applied at root. -->
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
        android:textSize="14sp"
        android:textColor="#EBFFFFFF"
        android:textStyle="bold"
        style="@style/TextAppearance.Compat.Notification.Title" />

    <TextView
        android:id="@+id/notif_body"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_marginTop="2dp"
        android:singleLine="true"
        android:ellipsize="end"
        android:textSize="12sp"
        android:textColor="#8CFFFFFF"
        android:lineSpacingExtra="2dp"
        style="@style/TextAppearance.Compat.Notification" />

    <!-- Chronometer countdown — bound at runtime for ClipRecording +
         GapWaiting; hidden for Merging + MergeComplete. Body TextView
         above is hidden for the chrono states. One slot, two views. -->
    <Chronometer
        android:id="@+id/notif_chrono"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_marginTop="2dp"
        android:singleLine="true"
        android:ellipsize="end"
        android:textSize="12sp"
        android:textColor="#C2FFFFFF"
        android:textStyle="normal"
        android:visibility="gone"
        style="@style/TextAppearance.Compat.Notification" />

    <!-- Dots row. 8 pre-allocated slots; service binds weightSum + per-pill
         visibility + tint per state. Pills use src + scaleType=fitXY so
         setColorFilter actually tints them (Phase 3.1 §2 item 1). -->
    <LinearLayout
        android:id="@+id/notif_dots_row"
        android:layout_width="match_parent"
        android:layout_height="4dp"
        android:layout_marginTop="10dp"
        android:orientation="horizontal"
        android:weightSum="8">

        <ImageView android:id="@+id/notif_dot_0" android:layout_width="0dp" android:layout_height="match_parent" android:layout_weight="1" android:layout_marginEnd="3dp" android:src="@drawable/notif_dot_pill" android:scaleType="fitXY" android:importantForAccessibility="no" />
        <ImageView android:id="@+id/notif_dot_1" android:layout_width="0dp" android:layout_height="match_parent" android:layout_weight="1" android:layout_marginEnd="3dp" android:src="@drawable/notif_dot_pill" android:scaleType="fitXY" android:importantForAccessibility="no" />
        <ImageView android:id="@+id/notif_dot_2" android:layout_width="0dp" android:layout_height="match_parent" android:layout_weight="1" android:layout_marginEnd="3dp" android:src="@drawable/notif_dot_pill" android:scaleType="fitXY" android:importantForAccessibility="no" />
        <ImageView android:id="@+id/notif_dot_3" android:layout_width="0dp" android:layout_height="match_parent" android:layout_weight="1" android:layout_marginEnd="3dp" android:src="@drawable/notif_dot_pill" android:scaleType="fitXY" android:importantForAccessibility="no" />
        <ImageView android:id="@+id/notif_dot_4" android:layout_width="0dp" android:layout_height="match_parent" android:layout_weight="1" android:layout_marginEnd="3dp" android:src="@drawable/notif_dot_pill" android:scaleType="fitXY" android:importantForAccessibility="no" />
        <ImageView android:id="@+id/notif_dot_5" android:layout_width="0dp" android:layout_height="match_parent" android:layout_weight="1" android:layout_marginEnd="3dp" android:src="@drawable/notif_dot_pill" android:scaleType="fitXY" android:importantForAccessibility="no" />
        <ImageView android:id="@+id/notif_dot_6" android:layout_width="0dp" android:layout_height="match_parent" android:layout_weight="1" android:layout_marginEnd="3dp" android:src="@drawable/notif_dot_pill" android:scaleType="fitXY" android:importantForAccessibility="no" />

        <FrameLayout
            android:id="@+id/notif_dot_7_container"
            android:layout_width="0dp"
            android:layout_height="match_parent"
            android:layout_weight="1">

            <ImageView
                android:id="@+id/notif_dot_7"
                android:layout_width="match_parent"
                android:layout_height="match_parent"
                android:src="@drawable/notif_dot_pill"
                android:scaleType="fitXY"
                android:importantForAccessibility="no" />

            <TextView
                android:id="@+id/notif_dot_count_label"
                android:layout_width="match_parent"
                android:layout_height="match_parent"
                android:gravity="center"
                android:singleLine="true"
                android:textSize="8sp"
                android:textColor="#80FFFFFF"
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

- [ ] **Step 1.2: Rewrite `notif_collapsed.xml`**

Apply typography polish only (no Chronometer / no dots in collapsed). Replace the file with:

```xml
<?xml version="1.0" encoding="utf-8"?>
<!-- M5 Phase 3 §4.1 + Phase 3.1 §4.4 — collapsed layout.
     Single row: title (weight=1, ellipsizes) + tail (right-aligned). -->
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
        android:textSize="14sp"
        android:textColor="#EBFFFFFF"
        android:textStyle="bold"
        style="@style/TextAppearance.Compat.Notification.Title" />

    <TextView
        android:id="@+id/notif_tail"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:singleLine="true"
        android:ellipsize="end"
        android:textSize="11sp"
        android:textColor="#A6FFFFFF"
        style="@style/TextAppearance.Compat.Notification" />

</LinearLayout>
```

- [ ] **Step 1.3: Compile gate**

```powershell
./gradlew :app:compileDebugKotlin
```

Expected: `BUILD SUCCESSFUL`. AAPT2 + R.id generation must pick up the new `notif_chrono` id.

If `:app:compileDebugKotlin` fails citing unresolved `R.id.notif_chrono`, the service binder hasn't been updated yet — Task 3 wires it. The compile here is just verifying AAPT2 accepts the XML.

- [ ] **Step 1.4: Commit**

```bash
git add app/src/main/res/layout/notif_expanded.xml app/src/main/res/layout/notif_collapsed.xml
git commit -m "feat(notif): pixel-match typography + dots src + chrono slot in layouts

Phase 3.1 §2 + §4.4. Dots ImageViews switch from android:background to
android:src + scaleType=fitXY so RemoteViews.setColorFilter actually
tints the pill. Adds a Chronometer view next to body TextView (one
visible at a time per state). Explicit textSize/textColor matching
mockup CSS on title + body + tail + Chronometer.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Task 2: Renderer — add `ChronoSpec` + `toChronoSpec` + `chrono` plan field (TDD)

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/service/notification/NotificationRenderer.kt`
- Modify: `app/src/test/java/com/aritr/rova/service/notification/NotificationRendererTest.kt`

- [ ] **Step 2.1: Add 7 failing tests**

Open `app/src/test/java/com/aritr/rova/service/notification/NotificationRendererTest.kt` and append these tests inside the existing class, before its closing brace:

```kotlin
    // ── Phase 3.1: ChronoSpec ─────────────────────────────────────

    @Test fun `ClipRecording with eta emits ChronoSpec at now plus eta`() {
        val state = NotificationState.ClipRecording(current = 1, etaSecondsRemaining = 18)
        val chrono = state.toChronoSpec(now = { 1000L })
        assertEquals(ChronoSpec(baseElapsedMs = 1000L + 18 * 1000L), chrono)
    }

    @Test fun `ClipRecording with eta=null emits chrono=null`() {
        val state = NotificationState.ClipRecording(current = 1, etaSecondsRemaining = null)
        assertNull(state.toChronoSpec(now = { 1000L }))
    }

    @Test fun `GapWaiting with countdown emits ChronoSpec at now plus countdown`() {
        val state = NotificationState.GapWaiting(
            nextNumber = 2, nextInLabel = "5:00",
            nextStartsInSeconds = 300, gapTotalSeconds = 600
        )
        val chrono = state.toChronoSpec(now = { 5000L })
        assertEquals(ChronoSpec(baseElapsedMs = 5000L + 300 * 1000L), chrono)
    }

    @Test fun `GapWaiting with countdown=null emits chrono=null`() {
        val state = NotificationState.GapWaiting(
            nextNumber = 2, nextInLabel = "1:00",
            nextStartsInSeconds = null
        )
        assertNull(state.toChronoSpec(now = { 5000L }))
    }

    @Test fun `Merging always emits chrono=null`() {
        val determinate = NotificationState.Merging(done = 4, total = 6, mergeProgressPercent = 67)
        val indeterminate = NotificationState.Merging(done = 0, total = 6, mergeProgressPercent = null)
        assertNull(determinate.toChronoSpec(now = { 1L }))
        assertNull(indeterminate.toChronoSpec(now = { 1L }))
    }

    @Test fun `MergeComplete always emits chrono=null`() {
        val state = NotificationState.MergeComplete(clipCount = 6, totalDurationSeconds = 300)
        assertNull(state.toChronoSpec(now = { 1L }))
    }

    @Test fun `toBindPlan forwards chrono field from toChronoSpec`() {
        val state: NotificationState = NotificationState.ClipRecording(current = 1, etaSecondsRemaining = 18)
        val plan = state.toBindPlan()
        // bind plan calls real SystemClock; we only verify chrono is non-null
        // for an eta-bearing ClipRecording (matches toChronoSpec contract).
        assertNotNull(plan.chrono)
    }
```

Confirm the existing imports cover `assertNotNull`; add `import org.junit.Assert.assertNotNull` if missing.

- [ ] **Step 2.2: Run, confirm fail**

```powershell
./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.service.notification.NotificationRendererTest"
```

Expected: compile failure — `ChronoSpec` and `toChronoSpec` are unresolved.

- [ ] **Step 2.3: Add `ChronoSpec` + `toChronoSpec` + `chrono` field in `NotificationRenderer.kt`**

Open `app/src/main/java/com/aritr/rova/service/notification/NotificationRenderer.kt`. Apply three edits:

**(a) Add `ChronoSpec` data class above `NotificationBindPlan`:**

```kotlin
/**
 * M5 Phase 3.1 §6 — countdown spec for Chronometer binding.
 *
 * [baseElapsedMs] is the absolute `SystemClock.elapsedRealtime()` value
 * at which the countdown hits zero. The service binds it via
 * `RemoteViews.setChronometer(viewId, base = baseElapsedMs, format = null, started = true)`
 * + `setBoolean(viewId, "setCountDown", true)`. The widget then free-ticks
 * per second without service round-trips.
 *
 * `null` plan → service hides Chronometer + shows static body TextView.
 */
data class ChronoSpec(val baseElapsedMs: Long)
```

**(b) Add `chrono: ChronoSpec?` field to `NotificationBindPlan`:**

```kotlin
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
    @DrawableRes val surfaceRes: Int,
    val chrono: ChronoSpec?
)
```

**(c) Wire `chrono` inside `toBindPlan()` and add the `toChronoSpec` extension:**

Update the existing `toBindPlan()` return block to add one more field:

```kotlin
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
        surfaceRes = if (complete) R.drawable.notif_surface_complete else R.drawable.notif_surface,
        chrono = toChronoSpec()
    )
}

internal fun NotificationState.toChronoSpec(
    now: () -> Long = { android.os.SystemClock.elapsedRealtime() }
): ChronoSpec? = when (this) {
    is NotificationState.ClipRecording ->
        etaSecondsRemaining?.let { ChronoSpec(now() + it * 1000L) }
    is NotificationState.GapWaiting ->
        nextStartsInSeconds?.let { ChronoSpec(now() + it * 1000L) }
    is NotificationState.Merging,
    is NotificationState.MergeComplete -> null
}
```

- [ ] **Step 2.4: Run, confirm pass**

```powershell
./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.service.notification.NotificationRendererTest"
```

Expected: full test class passes (previous + 7 new = `≥24` tests / 0 failed).

- [ ] **Step 2.5: Commit**

```bash
git add app/src/main/java/com/aritr/rova/service/notification/NotificationRenderer.kt app/src/test/java/com/aritr/rova/service/notification/NotificationRendererTest.kt
git commit -m "feat(notif): add ChronoSpec + toChronoSpec for Phase 3.1 countdown

Pure data + extension. ClipRecording etaSecondsRemaining and
GapWaiting nextStartsInSeconds map to absolute SystemClock.elapsedRealtime()
targets; Merging + MergeComplete emit null (no countdown). Service binds
Chronometer per plan.chrono in the next task.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Task 3: Service binder — dot tint via setColorFilter, dynamic weightSum, Chronometer wiring

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/service/RovaRecordingService.kt`

The Phase 3 binder currently sets dot tint via `setInt(viewId, "setBackgroundColor", color)` (or equivalent), which makes pills opaque solid color blocks AND loses rounded corners. Switch to `setColorFilter` on the `src` drawable. Also bind `setFloat(weightSum)` and the Chronometer.

- [ ] **Step 3.1: Locate current `bindDotsRow` (or equivalent) inside `renderRemoteView`**

```bash
grep -n "notif_dot_" app/src/main/java/com/aritr/rova/service/RovaRecordingService.kt | head -20
```

Identify the block that binds dot tint + visibility per `plan.dots`. It's inside the existing `renderRemoteView(plan, expanded)` private function.

- [ ] **Step 3.2: Replace the dots-binding block with the corrected approach**

The replacement responsibilities:
1. Bind `weightSum` to the visible pill count: `rv.setFloat(R.id.notif_dots_row, "setWeightSum", visibleCount.toFloat())`
2. For each visible pill (index 0 to visibleCount-1): set visibility VISIBLE + tint via `rv.setInt(slotId, "setColorFilter", computedColor)`
3. For each hidden slot (index visibleCount to 7): set visibility GONE
4. For the count-pill case (last pill is COUNT_PILL): show the count label TextView with `+N` text + matching tint on the pill
5. Hide the whole row if `plan.dots.visible == false`

Exact code to insert in place of the current dots-binding block (inside `renderRemoteView`, expanded branch — collapsed has no dots row):

```kotlin
// Dots row (expanded only). Per Phase 3.1 §2 + §4.3:
// - setColorFilter tints the src drawable (Phase 3.1 fix: src not background)
// - setFloat(weightSum) makes visible pills fill the row regardless of count
val dots = plan.dots
if (expanded) {
    if (!dots.visible) {
        rv.setViewVisibility(R.id.notif_dots_row, View.GONE)
    } else {
        rv.setViewVisibility(R.id.notif_dots_row, View.VISIBLE)
        val pills = dots.pills
        val visibleCount = pills.size.coerceAtMost(8)
        rv.setFloat(R.id.notif_dots_row, "setWeightSum", visibleCount.toFloat())

        val slotIds = intArrayOf(
            R.id.notif_dot_0, R.id.notif_dot_1, R.id.notif_dot_2, R.id.notif_dot_3,
            R.id.notif_dot_4, R.id.notif_dot_5, R.id.notif_dot_6, R.id.notif_dot_7
        )
        val containerIds = intArrayOf(
            R.id.notif_dot_0, R.id.notif_dot_1, R.id.notif_dot_2, R.id.notif_dot_3,
            R.id.notif_dot_4, R.id.notif_dot_5, R.id.notif_dot_6, R.id.notif_dot_7_container
        )

        for (i in 0 until 8) {
            if (i < visibleCount) {
                rv.setViewVisibility(containerIds[i], View.VISIBLE)
                val pill = pills[i]
                val color = when (pill.kind) {
                    DotState.Kind.DONE -> dots.accent
                    DotState.Kind.CURRENT -> (dots.accent and 0x00FFFFFF) or 0x66000000
                    DotState.Kind.TODO -> 0x1FFFFFFF
                    DotState.Kind.COUNT_PILL -> 0x14FFFFFF
                }
                rv.setInt(slotIds[i], "setColorFilter", color)
            } else {
                rv.setViewVisibility(containerIds[i], View.GONE)
            }
        }

        // Count-pill label overlay: visible only when last visible pill is COUNT_PILL
        val lastPill = pills.lastOrNull()
        if (lastPill?.kind == DotState.Kind.COUNT_PILL && lastPill.countLabel != null) {
            rv.setTextViewText(R.id.notif_dot_count_label, lastPill.countLabel)
            rv.setViewVisibility(R.id.notif_dot_count_label, View.VISIBLE)
        } else {
            rv.setViewVisibility(R.id.notif_dot_count_label, View.GONE)
        }
    }
}
```

If the existing block already handles count-pill / visibility differently, KEEP its overall structure and apply the two essential edits:
1. Add the `rv.setFloat(R.id.notif_dots_row, "setWeightSum", visibleCount.toFloat())` call right after the visibility-VISIBLE for the row
2. Change every `setInt(... "setBackgroundColor", color)` for dot pills to `setInt(... "setColorFilter", color)`

- [ ] **Step 3.3: Add Chronometer / body toggle**

Locate the title + body binding block inside `renderRemoteView` (expanded branch). After the existing body `setTextViewText` call, insert the Chronometer/body toggle:

```kotlin
// Chronometer / body toggle (Phase 3.1 §4.2).
// ClipRecording + GapWaiting → Chronometer visible, body hidden.
// Merging + MergeComplete    → body visible, Chronometer hidden.
val chrono = plan.chrono
if (expanded) {
    if (chrono != null) {
        rv.setViewVisibility(R.id.notif_body, View.GONE)
        rv.setViewVisibility(R.id.notif_chrono, View.VISIBLE)
        rv.setChronometer(R.id.notif_chrono, chrono.baseElapsedMs, null, true)
        rv.setBoolean(R.id.notif_chrono, "setCountDown", true)
    } else {
        rv.setViewVisibility(R.id.notif_chrono, View.GONE)
        rv.setViewVisibility(R.id.notif_body, View.VISIBLE)
        // body TextView text already set above via rv.setTextViewText(notif_body, plan.body)
    }
}
```

- [ ] **Step 3.4: Compile gate**

```powershell
./gradlew :app:compileDebugKotlin
```

Expected: `BUILD SUCCESSFUL`. If a missing-symbol error names `R.id.notif_chrono`, confirm Task 1's layout edit was committed.

- [ ] **Step 3.5: Full notification test suite**

```powershell
./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.service.notification.*"
```

Expected: previous + 7 new = pass / 0 failed.

- [ ] **Step 3.6: Full lint gate**

```powershell
./gradlew :app:lintDebug
```

Expected: `BUILD SUCCESSFUL`, all 25 `check*` tasks pass.

If lint flags `NewApi` on `setChronometer` (API 1+), `setBoolean` (API 16+), `setColorFilter` (API 16+), `setFloat` (API 16+) — none should fire because all are below `minSdk=24`. If `setCountDown` flags as API 24+, that matches our minSdk exactly — no warning expected.

- [ ] **Step 3.7: Commit**

```bash
git add app/src/main/java/com/aritr/rova/service/RovaRecordingService.kt
git commit -m "feat(notif): fix dot tint, bind dynamic weightSum, wire Chronometer

Phase 3.1 §2 + §4.3 + §4.2. setColorFilter on src drawable replaces
the previous setBackgroundColor (which didn't tint, producing all-grey
pills). setFloat(weightSum, visibleCount) makes pills fill the row at
any N. Chronometer + body TextView occupy the same slot; visibility
toggled per plan.chrono (ClipRecording + GapWaiting tick the Chronometer
via setCountDown, others show static body).

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Task 4: Final gate — lint + tests + assemble + smoke + PR

**Files:** none modified

- [ ] **Step 4.1: Full lint**

```powershell
./gradlew :app:lintDebug
```

Expected: `BUILD SUCCESSFUL`, all 25 `check*` tasks pass, no new lint warnings vs Phase 3 baseline.

- [ ] **Step 4.2: Full test suite**

```powershell
./gradlew :app:testDebugUnitTest
```

Expected: `BUILD SUCCESSFUL`, **≥ 1320 tests / 0 failed** (Phase 3 baseline ~1313 + 7 Phase 3.1 = ~1320).

- [ ] **Step 4.3: Assemble**

```powershell
./gradlew :app:assembleDebug
```

Expected: `BUILD SUCCESSFUL`. APK at `app/build/outputs/apk/debug/app-debug.apk`.

- [ ] **Step 4.4: Install on a real device**

```powershell
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Expected: `Success`. Real phone (Android 14+) required.

- [ ] **Step 4.5: Real-device smoke (Phase 3.1 spec §7.3 — additive to Phase 3)**

- [ ] ClipRecording — dots distinct: solid blue (done) + translucent blue (current) + faint white (todo).
- [ ] GapWaiting — same color distinction.
- [ ] Merging — done pills solid blue, current translucent blue, todo faint white.
- [ ] MergeComplete — all pills solid green.
- [ ] N=3 — 3 pills fill the full card width (no right-side dead space).
- [ ] N=2 — 2 pills fill the full width.
- [ ] N=8 — 8 pills evenly distributed (no overflow).
- [ ] N=10 — 7 state pills + "+3" count pill, all together filling the row.
- [ ] ClipRecording — Chronometer counts DOWN smoothly second-by-second.
- [ ] GapWaiting — Chronometer counts down smoothly from `nextStartsInSeconds`.
- [ ] Merging — body shows static text or progress %; no Chronometer visible.
- [ ] MergeComplete — body shows clipCount summary; no Chronometer visible.
- [ ] Typography — title larger/bolder than body; body alpha visibly lower; matches mockup density.
- [ ] TalkBack — Chronometer announces remaining time each tick.
- [ ] OS font scale 2× — title + body fit; Chronometer text doesn't overflow.
- [ ] Lockscreen — Chronometer keeps ticking during recording.
- [ ] Force-stop mid-recording — no orphan Chronometer.

- [ ] **Step 4.6: Marker commit**

```bash
git commit --allow-empty -m "chore(m5): notification redesign Phase 3.1 polish ready for review

Real-device smoke checklist passes per
docs/superpowers/specs/2026-05-28-notification-redesign-phase3-1-design.md §7.3.
Test baseline: ≥ 1320 / 0-0-0.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

- [ ] **Step 4.7: Push + open M5 PR (covers Phase 1 + 2 + 3 + 3.1)**

```bash
git push -u origin feat/notification-redesign-v1
gh pr create --title "Milestone 5 — Notification redesign v1.0 (semantic + branded + polished)" --body "$(cat <<'EOF'
## Summary
- **Phase 1** (semantic + a11y): per-state copy with live numbers, dual channels, 4 pure helpers, MainActivity deep-link routing, ~1Hz rate limit, 49 JVM tests.
- **Phase 2** (branded visual): DecoratedCustomViewStyle + shared RemoteViews + accent rail + chip + fixed brand colors.
- **Phase 3** (mockup alignment): drop rail + chip, add clip-dots row, translucent surface, dark-glass MergeComplete with green title.
- **Phase 3.1** (polish): fix dot tinting (src + setColorFilter), dynamic weightSum (pills fill row at any N), Chronometer for ClipRecording + GapWaiting countdowns, mockup-spec typography.
- Total notification tests: ~73 / 0.
- Project test baseline: ≥ 1320 / 0-0-0.

## Specs + plans
- Phase 1 spec: `docs/superpowers/specs/2026-05-27-notification-redesign-v1-design.md`
- Phase 2 spec: `docs/superpowers/specs/2026-05-28-notification-redesign-phase2-design.md`
- Phase 3 spec: `docs/superpowers/specs/2026-05-28-notification-redesign-phase3-design.md`
- Phase 3.1 spec: `docs/superpowers/specs/2026-05-28-notification-redesign-phase3-1-design.md`

## Out-of-scope follow-ons
- Rova launcher icon redesign (adaptive icon + monochrome).
- Inter font asset shipping.
- Fully-custom notifications (Android-12+ retired).

## Test plan
- [x] `./gradlew :app:lintDebug` green (25 check* tasks).
- [x] `./gradlew :app:testDebugUnitTest` green (≥ 1320 / 0-0-0).
- [x] `./gradlew :app:assembleDebug` green.
- [x] Real-device smoke checklists per all 4 phase specs pass on Android 14+.
- [x] TalkBack walkthrough.
- [x] Lockscreen visibility per spec.
- [x] OS font scale 1.5× / 2× truncates gracefully.

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

Expected: PR URL returned.

---

## Self-review

**1. Spec coverage:**

| Spec section | Task |
|---|---|
| §2 In scope: dot tinting fix | Task 1 + Task 3 |
| §2 In scope: dynamic weightSum | Task 3 |
| §2 In scope: Chronometer countdown | Task 1 + Task 2 + Task 3 |
| §2 In scope: chrono field on bind plan | Task 2 |
| §2 In scope: typography pixel-match | Task 1 |
| §2.1 NO-GO items | enforced by task scope |
| §3 Architecture | Task 2 + Task 3 |
| §4.1 Collapsed | Task 1 |
| §4.2 Expanded | Task 1 + Task 3 |
| §4.3 Dots row | Task 1 + Task 3 |
| §4.4 Typography | Task 1 |
| §5 Accessibility | preserved across tasks |
| §6 Helper API contract | Task 2 |
| §7.1 Pure JVM tests | Task 2 |
| §7.3 Real-device smoke | Task 4.5 |
| §8 Lint gate | Tasks 3.6 + 4.1 |
| §10 Acceptance criteria | Task 4 fully gates them |

No gaps.

**2. Placeholder scan:** no "TBD" / "TODO" / "appropriate" / "implement later" / "similar to". Code blocks are complete and runnable.

**3. Type consistency:**
- `ChronoSpec(baseElapsedMs: Long)` — declared Task 2, consumed Task 3 (`rv.setChronometer(..., chrono.baseElapsedMs, null, true)`), tested Task 2.
- `toChronoSpec(now: () -> Long)` — declared Task 2, called by `toBindPlan()` Task 2, tested Task 2.
- `chrono: ChronoSpec?` field on `NotificationBindPlan` — declared Task 2, consumed by `renderRemoteView` Task 3, tested via `toBindPlan` delegation Task 2.
- R-resource ids:
  - `R.id.notif_chrono` — created Task 1 (`notif_expanded.xml`), consumed Task 3.
  - `R.id.notif_dots_row`, `notif_dot_0..7`, `notif_dot_7_container`, `notif_dot_count_label` — existing from Phase 3, Task 3 binds them.
  - `R.layout.notif_collapsed` / `notif_expanded` — existing from Phase 2/3.
- `DotState.Kind` constants (DONE / CURRENT / TODO / COUNT_PILL) — declared in Phase 3, referenced in Task 3.
- `dots.accent` / `dots.pills` / `dots.visible` — Phase 3 fields, used in Task 3.

All consistent.

Plan complete.
