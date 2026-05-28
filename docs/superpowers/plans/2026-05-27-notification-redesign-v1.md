# Notification Redesign v1.0 (M5) — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Bring the foreground recording notification to v1.0 polish — per-state skin (icon, accent, copy with live numbers, progress, actions, dismissibility), dual notification channels, full WCAG 2.2 AA accessibility, and JVM-unit test coverage for every pure helper.

**Architecture:** Extend the existing `service/notification/NotificationCopy.kt` pure-helper pattern with three new pure files (`NotificationChannelConfig.kt`, `NotificationActionSpec.kt`, `NotificationIconRes.kt`). The service stays the only owner of `NotificationCompat.Builder` and `NotificationManager.notify`; all pre-builder decisions become pure data computed by helpers and consumed by a refactored per-state builder.

**Tech Stack:** Kotlin 2.2.10, AGP 9.2.1, Compose BOM 2025.01.01, JUnit 4, `androidx.core:core-ktx` `NotificationCompat`, vector drawables.

**Spec:** [`docs/superpowers/specs/2026-05-27-notification-redesign-v1-design.md`](../specs/2026-05-27-notification-redesign-v1-design.md)

**Mockup:** `mockups/new_uiux/09-notification-export.html` (phones 1–4)

---

## File map

| Path | Disposition |
|---|---|
| `app/src/main/java/com/aritr/rova/service/notification/NotificationCopy.kt` | extend `NotificationState` variants with optional numeric fields; extend `toCopy()` formatter |
| `app/src/main/java/com/aritr/rova/service/notification/NotificationChannelConfig.kt` | **new** — channel ids, importance, accent, dismissibility, progress (all pure) |
| `app/src/main/java/com/aritr/rova/service/notification/NotificationActionSpec.kt` | **new** — typed action specs (label, contentDescription, intent action key, extras) |
| `app/src/main/java/com/aritr/rova/service/notification/NotificationIconRes.kt` | **new** — `@DrawableRes` lookup per state |
| `app/src/main/java/com/aritr/rova/service/RovaRecordingService.kt` | register both channels; split `createNotification` into per-state builder; wire numeric fields; rate-limit |
| `app/src/main/java/com/aritr/rova/MainActivity.kt` | read `EXTRA_SESSION_ID` and `EXTRA_TARGET_TAB` from launch intent |
| `app/src/main/res/drawable/ic_notif_recording.xml` | **new** vector |
| `app/src/main/res/drawable/ic_notif_waiting.xml` | **new** vector |
| `app/src/main/res/drawable/ic_notif_merging.xml` | **new** vector |
| `app/src/main/res/drawable/ic_notif_complete.xml` | **new** vector |
| `app/src/main/res/values/strings.xml` | **new entries** — channel names, descriptions, action labels, contentDescriptions |
| `app/src/test/java/com/aritr/rova/service/notification/NotificationCopyTest.kt` | extend |
| `app/src/test/java/com/aritr/rova/service/notification/NotificationChannelConfigTest.kt` | **new** |
| `app/src/test/java/com/aritr/rova/service/notification/NotificationActionSpecTest.kt` | **new** |
| `app/src/test/java/com/aritr/rova/service/notification/NotificationIconResTest.kt` | **new** |
| `app/src/test/java/com/aritr/rova/service/notification/NotificationProgressTest.kt` | **new** |

---

## Task 0: Bootstrap — verify baseline + create branch

**Files:** none modified

- [ ] **Step 0.1: Confirm master is at expected tip**

```bash
git log --oneline -1
```

Expected: `12c12a9 Milestone 4 — Onboarding redesign ...`

If different, stop and ask the owner before continuing.

- [ ] **Step 0.2: Verify clean build baseline**

```powershell
./gradlew :app:lintDebug :app:testDebugUnitTest :app:assembleDebug
```

Expected: `BUILD SUCCESSFUL`. The test summary at the bottom should show `1245 tests completed, 0 failed`.

If any test fails or lint hits a new error, stop and triage before touching code.

- [ ] **Step 0.3: Create the working branch off master**

```bash
git switch -c feat/notification-redesign-v1
```

Expected: `Switched to a new branch 'feat/notification-redesign-v1'`

---

## Task 1: Add the four vector drawables

**Files:**
- Create: `app/src/main/res/drawable/ic_notif_recording.xml`
- Create: `app/src/main/res/drawable/ic_notif_waiting.xml`
- Create: `app/src/main/res/drawable/ic_notif_merging.xml`
- Create: `app/src/main/res/drawable/ic_notif_complete.xml`

Each drawable is a single-path monochrome white vector (24dp viewBox). The status-bar small icon is auto-tinted by the system; the panel large area picks up `setColor` from the builder.

- [ ] **Step 1.1: Write `ic_notif_recording.xml` (filled circle)**

```xml
<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24"
    android:tint="?attr/colorControlNormal">
    <path
        android:fillColor="#FFFFFFFF"
        android:pathData="M12,12m-7,0a7,7 0,1 1,14 0a7,7 0,1 1,-14 0" />
</vector>
```

- [ ] **Step 1.2: Write `ic_notif_waiting.xml` (hourglass)**

```xml
<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24"
    android:tint="?attr/colorControlNormal">
    <path
        android:fillColor="#FFFFFFFF"
        android:pathData="M6,2L18,2L18,8L14,12L18,16L18,22L6,22L6,16L10,12L6,8L6,2ZM8,4L8,7.17L12,11.17L16,7.17L16,4L8,4ZM12,12.83L8,16.83L8,20L16,20L16,16.83L12,12.83Z" />
</vector>
```

- [ ] **Step 1.3: Write `ic_notif_merging.xml` (merge arrows)**

```xml
<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24"
    android:tint="?attr/colorControlNormal">
    <path
        android:fillColor="#FFFFFFFF"
        android:pathData="M17,20.41L18.41,19L15,15.59L13.59,17L17,20.41ZM7.5,8H11V13.59L5.59,19L7,20.41L13,14.41V8H16.5L12,3.5L7.5,8Z" />
</vector>
```

- [ ] **Step 1.4: Write `ic_notif_complete.xml` (check)**

```xml
<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24"
    android:tint="?attr/colorControlNormal">
    <path
        android:fillColor="#FFFFFFFF"
        android:pathData="M9,16.17L4.83,12l-1.42,1.41L9,19 21,7l-1.41,-1.41z" />
</vector>
```

- [ ] **Step 1.5: Verify drawables compile**

```powershell
./gradlew :app:assembleDebug
```

Expected: `BUILD SUCCESSFUL`. Drawable XML parse errors would fail aapt2 with `error: failed parsing overlays` — investigate and fix the XML if so.

- [ ] **Step 1.6: Commit**

```bash
git add app/src/main/res/drawable/ic_notif_recording.xml app/src/main/res/drawable/ic_notif_waiting.xml app/src/main/res/drawable/ic_notif_merging.xml app/src/main/res/drawable/ic_notif_complete.xml
git commit -m "feat(notif): add 4 per-state vector drawables for redesigned FGS notification

Mockup source: mockups/new_uiux/09-notification-export.html.
Single-path monochrome white vectors, 24dp viewBox, system-tinted as
notification small icons.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Task 2: Add strings.xml entries

**Files:**
- Modify: `app/src/main/res/values/strings.xml`

- [ ] **Step 2.1: Append channel + action strings to `strings.xml`**

Locate the closing `</resources>` tag and insert these entries immediately before it:

```xml
    <!-- M5: Notification channel names + descriptions -->
    <string name="notification_channel_session_name">Recording session</string>
    <string name="notification_channel_session_desc">Ongoing notification while a recording session is active or merging.</string>
    <string name="notification_channel_complete_name">Recording complete</string>
    <string name="notification_channel_complete_desc">One-shot notification when a recorded session is ready in the Library.</string>

    <!-- M5: Notification action labels -->
    <string name="notification_action_stop">Stop</string>
    <string name="notification_action_stop_early">Stop Early</string>
    <string name="notification_action_open">Open</string>
    <string name="notification_action_view_in_library">View in Library</string>
    <string name="notification_action_share">Share</string>

    <!-- M5: Notification action contentDescription (TalkBack) -->
    <string name="notification_action_stop_cd">Stop the recording session</string>
    <string name="notification_action_stop_early_cd">Stop before the next clip</string>
    <string name="notification_action_open_cd">Open Rova</string>
    <string name="notification_action_view_in_library_cd">View this recording in the Library</string>
    <string name="notification_action_share_cd">Share this recording</string>
```

- [ ] **Step 2.2: Verify strings compile**

```powershell
./gradlew :app:assembleDebug
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 2.3: Commit**

```bash
git add app/src/main/res/values/strings.xml
git commit -m "feat(notif): add channel name + action label strings for M5 redesign

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Task 3: Extend `NotificationState` data classes + extend `NotificationCopyTest`

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/service/notification/NotificationCopy.kt`
- Modify: `app/src/test/java/com/aritr/rova/service/notification/NotificationCopyTest.kt`

The extension is purely additive — new optional fields with `null` defaults so the existing 11 tests stay green.

- [ ] **Step 3.1: Write the failing tests for new copy formatting**

Insert these tests at the end of `NotificationCopyTest`, before the closing brace:

```kotlin
    // M5 extensions — new optional numeric params

    @Test fun `ClipRecording with eta renders MM SS remaining body`() {
        val copy = NotificationState.ClipRecording(
            current = 2, total = 6, etaSecondsRemaining = 18
        ).toCopy()
        assertEquals("0:18 remaining in this clip", copy.body)
    }

    @Test fun `ClipRecording without eta falls back to static body`() {
        val copy = NotificationState.ClipRecording(
            current = 2, total = 6, etaSecondsRemaining = null
        ).toCopy()
        assertEquals("Recording in progress", copy.body)
    }

    @Test fun `ClipRecording with eta over a minute renders M SS`() {
        val copy = NotificationState.ClipRecording(
            current = 1, total = 6, etaSecondsRemaining = 75
        ).toCopy()
        assertEquals("1:15 remaining in this clip", copy.body)
    }

    @Test fun `ClipRecording with eta zero renders 0 00`() {
        val copy = NotificationState.ClipRecording(
            current = 6, total = 6, etaSecondsRemaining = 0
        ).toCopy()
        assertEquals("0:00 remaining in this clip", copy.body)
    }

    @Test fun `MergeComplete with duration renders clips dot total dot saved`() {
        val copy = NotificationState.MergeComplete(
            clipCount = 6, totalDurationSeconds = 300
        ).toCopy()
        assertEquals("6 clips · 5:00 total · saved to Library", copy.body)
    }

    @Test fun `MergeComplete singular with duration uses 1 clip`() {
        val copy = NotificationState.MergeComplete(
            clipCount = 1, totalDurationSeconds = 30
        ).toCopy()
        assertEquals("1 clip · 0:30 total · saved to Library", copy.body)
    }

    @Test fun `MergeComplete without duration falls back to existing copy`() {
        val copy = NotificationState.MergeComplete(
            clipCount = 6, totalDurationSeconds = null
        ).toCopy()
        assertEquals("6 clips saved to Library", copy.body)
    }
```

- [ ] **Step 3.2: Run new tests, confirm they fail**

```powershell
./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.service.notification.NotificationCopyTest"
```

Expected: 7 new tests fail with compile error (param doesn't exist) or assertion failure once fields exist. Existing 11 tests stay green.

- [ ] **Step 3.3: Extend `NotificationState` and `toCopy()` in `NotificationCopy.kt`**

Replace the entire file body (keep the package + KDoc — update the KDoc to note the M5 extension) with:

```kotlin
package com.aritr.rova.service.notification

/**
 * Phase 3.1 (NEW_UI_BACKEND_REPLAN §5 row 3.1) + M5 redesign
 * (docs/superpowers/specs/2026-05-27-notification-redesign-v1-design.md)
 * — typed copy states for the foreground recording notification.
 * Mockup source-of-truth: `mockups/new_uiux/09-notification-export.html`.
 *
 * Sealed interface (not enum) — each happy-path state carries DIFFERENT
 * params. M5 added optional numeric fields (eta / countdown / merge-% /
 * total-duration / sessionId) wired from existing service state. Null
 * defaults preserve back-compat with the Phase-3.1 callers.
 *
 * NO additional state may be added without amending the replan §11.5
 * (the 5th-state NO-GO holds). Error / init / transient strings continue
 * to flow through the existing String-based `updateNotification(contentText)`
 * overload.
 */
sealed interface NotificationState {
    data class ClipRecording(
        val current: Int,
        val total: Int? = null,
        val etaSecondsRemaining: Int? = null
    ) : NotificationState

    data class GapWaiting(
        val nextNumber: Int,
        val nextInLabel: String,
        val total: Int? = null,
        val nextStartsInSeconds: Int? = null,
        val gapTotalSeconds: Int? = null
    ) : NotificationState

    data class Merging(
        val done: Int,
        val total: Int,
        val mergeProgressPercent: Int? = null
    ) : NotificationState

    data class MergeComplete(
        val clipCount: Int,
        val totalDurationSeconds: Int? = null,
        val sessionId: String? = null
    ) : NotificationState
}

/**
 * Pure (title, body) pair consumed by the service's
 * `NotificationCompat.Builder` chain. Builder calls (channel id, color,
 * icon, action buttons, ongoing flag, FGS type, content intent) stay in
 * the service; the only escapes from this file are the four `to*()`
 * helpers in sibling files.
 */
data class NotificationCopy(val title: String, val body: String)

private fun formatMmSs(totalSeconds: Int): String {
    val s = totalSeconds.coerceAtLeast(0)
    return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
}

fun NotificationState.toCopy(): NotificationCopy = when (this) {
    is NotificationState.ClipRecording -> NotificationCopy(
        title = if (total != null) "Recording · Clip $current of $total" else "Recording · Clip $current",
        body = if (etaSecondsRemaining != null) "${formatMmSs(etaSecondsRemaining)} remaining in this clip"
        else "Recording in progress"
    )
    is NotificationState.GapWaiting -> NotificationCopy(
        title = if (total != null) "Waiting · Clip $nextNumber of $total next" else "Waiting · Clip $nextNumber next",
        body = "Next clip starts in $nextInLabel"
    )
    is NotificationState.Merging -> NotificationCopy(
        title = "Merging clips · $done of $total",
        body = "Processing — please wait"
    )
    is NotificationState.MergeComplete -> NotificationCopy(
        title = "Merge complete",
        body = when {
            totalDurationSeconds != null && clipCount == 1 ->
                "1 clip · ${formatMmSs(totalDurationSeconds)} total · saved to Library"
            totalDurationSeconds != null ->
                "$clipCount clips · ${formatMmSs(totalDurationSeconds)} total · saved to Library"
            clipCount == 1 -> "1 clip saved to Library"
            else -> "$clipCount clips saved to Library"
        }
    )
}
```

- [ ] **Step 3.4: Run tests, confirm all pass**

```powershell
./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.service.notification.NotificationCopyTest"
```

Expected: 18 tests pass (11 existing + 7 new).

- [ ] **Step 3.5: Commit**

```bash
git add app/src/main/java/com/aritr/rova/service/notification/NotificationCopy.kt app/src/test/java/com/aritr/rova/service/notification/NotificationCopyTest.kt
git commit -m "feat(notif): extend NotificationState with eta/countdown/duration/sessionId

M5 spec §5 — optional numeric fields wired from existing service state.
Back-compat: defaults are null and toCopy() falls back to Phase-3.1 copy.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Task 4: Create `NotificationIconRes.kt` + tests

**Files:**
- Create: `app/src/main/java/com/aritr/rova/service/notification/NotificationIconRes.kt`
- Create: `app/src/test/java/com/aritr/rova/service/notification/NotificationIconResTest.kt`

- [ ] **Step 4.1: Write failing tests**

Create `app/src/test/java/com/aritr/rova/service/notification/NotificationIconResTest.kt`:

```kotlin
package com.aritr.rova.service.notification

import com.aritr.rova.R
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * M5 §7 — per-state @DrawableRes lookup. Pure int comparison; no
 * Android resource resolution needed under JVM tests (R fields are
 * compile-time int constants).
 */
class NotificationIconResTest {

    @Test fun `ClipRecording maps to ic_notif_recording`() {
        val state: NotificationState = NotificationState.ClipRecording(current = 1)
        assertEquals(R.drawable.ic_notif_recording, state.toIconRes())
    }

    @Test fun `GapWaiting maps to ic_notif_waiting`() {
        val state: NotificationState = NotificationState.GapWaiting(nextNumber = 2, nextInLabel = "1:00")
        assertEquals(R.drawable.ic_notif_waiting, state.toIconRes())
    }

    @Test fun `Merging maps to ic_notif_merging`() {
        val state: NotificationState = NotificationState.Merging(done = 0, total = 6)
        assertEquals(R.drawable.ic_notif_merging, state.toIconRes())
    }

    @Test fun `MergeComplete maps to ic_notif_complete`() {
        val state: NotificationState = NotificationState.MergeComplete(clipCount = 6)
        assertEquals(R.drawable.ic_notif_complete, state.toIconRes())
    }
}
```

- [ ] **Step 4.2: Run, confirm fail**

```powershell
./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.service.notification.NotificationIconResTest"
```

Expected: compile failure — `toIconRes` is unresolved.

- [ ] **Step 4.3: Create `NotificationIconRes.kt`**

```kotlin
package com.aritr.rova.service.notification

import androidx.annotation.DrawableRes
import com.aritr.rova.R

/**
 * M5 §7 — per-state notification small-icon resource.
 *
 * Pure mapping. Drawables are single-path monochrome white vectors;
 * the system tints them per status-bar / panel surface. The colored
 * accent comes from `setColor()` (see [NotificationChannelConfig]).
 */
@DrawableRes
fun NotificationState.toIconRes(): Int = when (this) {
    is NotificationState.ClipRecording -> R.drawable.ic_notif_recording
    is NotificationState.GapWaiting -> R.drawable.ic_notif_waiting
    is NotificationState.Merging -> R.drawable.ic_notif_merging
    is NotificationState.MergeComplete -> R.drawable.ic_notif_complete
}
```

- [ ] **Step 4.4: Run, confirm pass**

```powershell
./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.service.notification.NotificationIconResTest"
```

Expected: 4 tests pass.

- [ ] **Step 4.5: Commit**

```bash
git add app/src/main/java/com/aritr/rova/service/notification/NotificationIconRes.kt app/src/test/java/com/aritr/rova/service/notification/NotificationIconResTest.kt
git commit -m "feat(notif): add NotificationIconRes pure mapper per state

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Task 5: Create `NotificationChannelConfig.kt` + tests (channels, accent, dismissibility, progress)

**Files:**
- Create: `app/src/main/java/com/aritr/rova/service/notification/NotificationChannelConfig.kt`
- Create: `app/src/test/java/com/aritr/rova/service/notification/NotificationChannelConfigTest.kt`
- Create: `app/src/test/java/com/aritr/rova/service/notification/NotificationProgressTest.kt`

- [ ] **Step 5.1: Write failing tests for channel + accent + dismissibility**

Create `app/src/test/java/com/aritr/rova/service/notification/NotificationChannelConfigTest.kt`:

```kotlin
package com.aritr.rova.service.notification

import android.app.NotificationManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M5 §4 + §5 — pure-JVM tests for channel routing, accent color, and
 * dismissibility per state. Constants come from android.app, which is
 * resolvable at compile time but returns default ints under
 * isReturnDefaultValues — we assert against the literal int values
 * the framework defines.
 */
class NotificationChannelConfigTest {

    @Test fun `ClipRecording routes to session channel`() {
        val state: NotificationState = NotificationState.ClipRecording(current = 1)
        assertEquals(NotificationChannelConfig.SESSION_CHANNEL_ID, state.toChannelId())
    }

    @Test fun `GapWaiting routes to session channel`() {
        val state: NotificationState = NotificationState.GapWaiting(nextNumber = 1, nextInLabel = "1:00")
        assertEquals(NotificationChannelConfig.SESSION_CHANNEL_ID, state.toChannelId())
    }

    @Test fun `Merging routes to session channel`() {
        val state: NotificationState = NotificationState.Merging(done = 0, total = 6)
        assertEquals(NotificationChannelConfig.SESSION_CHANNEL_ID, state.toChannelId())
    }

    @Test fun `MergeComplete routes to complete channel`() {
        val state: NotificationState = NotificationState.MergeComplete(clipCount = 1)
        assertEquals(NotificationChannelConfig.COMPLETE_CHANNEL_ID, state.toChannelId())
    }

    @Test fun `session channel importance is LOW`() {
        assertEquals(
            NotificationManager.IMPORTANCE_LOW,
            NotificationChannelConfig.importanceFor(NotificationChannelConfig.SESSION_CHANNEL_ID)
        )
    }

    @Test fun `complete channel importance is DEFAULT`() {
        assertEquals(
            NotificationManager.IMPORTANCE_DEFAULT,
            NotificationChannelConfig.importanceFor(NotificationChannelConfig.COMPLETE_CHANNEL_ID)
        )
    }

    @Test fun `accent for recording-session states is brand blue`() {
        val rec: NotificationState = NotificationState.ClipRecording(current = 1)
        val gap: NotificationState = NotificationState.GapWaiting(nextNumber = 1, nextInLabel = "1:00")
        val merge: NotificationState = NotificationState.Merging(done = 0, total = 6)
        assertEquals(NotificationChannelConfig.ACCENT_RECORDING, rec.toAccent())
        assertEquals(NotificationChannelConfig.ACCENT_RECORDING, gap.toAccent())
        assertEquals(NotificationChannelConfig.ACCENT_RECORDING, merge.toAccent())
    }

    @Test fun `accent for MergeComplete is brand green`() {
        val state: NotificationState = NotificationState.MergeComplete(clipCount = 1)
        assertEquals(NotificationChannelConfig.ACCENT_COMPLETE, state.toAccent())
    }

    @Test fun `ACCENT_RECORDING is 0xFF5b7fff`() {
        assertEquals(0xFF5B7FFF.toInt(), NotificationChannelConfig.ACCENT_RECORDING)
    }

    @Test fun `ACCENT_COMPLETE is 0xFF34d399`() {
        assertEquals(0xFF34D399.toInt(), NotificationChannelConfig.ACCENT_COMPLETE)
    }

    @Test fun `only MergeComplete is dismissible`() {
        val rec: NotificationState = NotificationState.ClipRecording(current = 1)
        val gap: NotificationState = NotificationState.GapWaiting(nextNumber = 1, nextInLabel = "1:00")
        val merge: NotificationState = NotificationState.Merging(done = 0, total = 6)
        val complete: NotificationState = NotificationState.MergeComplete(clipCount = 1)
        assertFalse(rec.isDismissible())
        assertFalse(gap.isDismissible())
        assertFalse(merge.isDismissible())
        assertTrue(complete.isDismissible())
    }

    @Test fun `legacy channel id is preserved for back-compat reference`() {
        assertEquals("RovaRecordingChannel", NotificationChannelConfig.LEGACY_CHANNEL_ID)
    }
}
```

- [ ] **Step 5.2: Write failing tests for progress mapping**

Create `app/src/test/java/com/aritr/rova/service/notification/NotificationProgressTest.kt`:

```kotlin
package com.aritr.rova.service.notification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * M5 §5 — pure-JVM tests for per-state progress-bar config. Null means
 * no progress bar; indeterminate=true means a spinning bar without a
 * fill percent.
 */
class NotificationProgressTest {

    @Test fun `ClipRecording returns null progress`() {
        val state: NotificationState = NotificationState.ClipRecording(current = 1, etaSecondsRemaining = 18)
        assertNull(state.toProgress())
    }

    @Test fun `GapWaiting with both counts returns determinate countdown`() {
        val state: NotificationState = NotificationState.GapWaiting(
            nextNumber = 2, nextInLabel = "4:42",
            nextStartsInSeconds = 60, gapTotalSeconds = 300
        )
        val progress = state.toProgress()!!
        assertEquals(300, progress.max)
        assertEquals(240, progress.current)
        assertEquals(false, progress.indeterminate)
    }

    @Test fun `GapWaiting missing nextStartsInSeconds returns null`() {
        val state: NotificationState = NotificationState.GapWaiting(
            nextNumber = 2, nextInLabel = "4:42",
            nextStartsInSeconds = null, gapTotalSeconds = 300
        )
        assertNull(state.toProgress())
    }

    @Test fun `GapWaiting missing gapTotalSeconds returns null`() {
        val state: NotificationState = NotificationState.GapWaiting(
            nextNumber = 2, nextInLabel = "4:42",
            nextStartsInSeconds = 60, gapTotalSeconds = null
        )
        assertNull(state.toProgress())
    }

    @Test fun `Merging with percent returns determinate`() {
        val state: NotificationState = NotificationState.Merging(done = 4, total = 6, mergeProgressPercent = 67)
        val progress = state.toProgress()!!
        assertEquals(100, progress.max)
        assertEquals(67, progress.current)
        assertEquals(false, progress.indeterminate)
    }

    @Test fun `Merging without percent returns indeterminate`() {
        val state: NotificationState = NotificationState.Merging(done = 0, total = 6, mergeProgressPercent = null)
        val progress = state.toProgress()!!
        assertEquals(0, progress.max)
        assertEquals(0, progress.current)
        assertEquals(true, progress.indeterminate)
    }

    @Test fun `MergeComplete returns null progress`() {
        val state: NotificationState = NotificationState.MergeComplete(clipCount = 6)
        assertNull(state.toProgress())
    }
}
```

- [ ] **Step 5.3: Run both new test files, confirm they fail**

```powershell
./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.service.notification.NotificationChannelConfigTest" --tests "com.aritr.rova.service.notification.NotificationProgressTest"
```

Expected: compile failure — `NotificationChannelConfig`, `toChannelId`, `toAccent`, `isDismissible`, `toProgress` all unresolved.

- [ ] **Step 5.4: Create `NotificationChannelConfig.kt`**

```kotlin
package com.aritr.rova.service.notification

import android.app.NotificationManager
import androidx.annotation.ColorInt

/**
 * M5 §4 + §5 — channel topology, accent colors, dismissibility and
 * progress-bar config per [NotificationState]. All pure data; the
 * service consumes these and feeds them to `NotificationCompat.Builder`.
 *
 * Two channels:
 *  - [SESSION_CHANNEL_ID] — LOW importance, ongoing, silent FGS spine
 *    (ClipRecording / GapWaiting / Merging).
 *  - [COMPLETE_CHANNEL_ID] — DEFAULT importance, one-shot, dismissible
 *    (MergeComplete). User can opt sound off per-channel without
 *    silencing recording.
 *
 * The old [LEGACY_CHANNEL_ID] is intentionally kept registered (and
 * unused) so user importance/sound overrides are not nuked silently
 * on first install of M5. A follow-on cleanup slice can delete it.
 */
object NotificationChannelConfig {
    const val SESSION_CHANNEL_ID = "rova_recording_session"
    const val COMPLETE_CHANNEL_ID = "rova_recording_complete"
    const val LEGACY_CHANNEL_ID = "RovaRecordingChannel"

    @ColorInt val ACCENT_RECORDING: Int = 0xFF5B7FFF.toInt()
    @ColorInt val ACCENT_COMPLETE: Int = 0xFF34D399.toInt()

    fun importanceFor(channelId: String): Int = when (channelId) {
        SESSION_CHANNEL_ID -> NotificationManager.IMPORTANCE_LOW
        COMPLETE_CHANNEL_ID -> NotificationManager.IMPORTANCE_DEFAULT
        else -> NotificationManager.IMPORTANCE_LOW
    }
}

data class NotificationProgress(
    val max: Int,
    val current: Int,
    val indeterminate: Boolean
)

fun NotificationState.toChannelId(): String = when (this) {
    is NotificationState.ClipRecording,
    is NotificationState.GapWaiting,
    is NotificationState.Merging -> NotificationChannelConfig.SESSION_CHANNEL_ID
    is NotificationState.MergeComplete -> NotificationChannelConfig.COMPLETE_CHANNEL_ID
}

@ColorInt
fun NotificationState.toAccent(): Int = when (this) {
    is NotificationState.ClipRecording,
    is NotificationState.GapWaiting,
    is NotificationState.Merging -> NotificationChannelConfig.ACCENT_RECORDING
    is NotificationState.MergeComplete -> NotificationChannelConfig.ACCENT_COMPLETE
}

fun NotificationState.isDismissible(): Boolean = when (this) {
    is NotificationState.MergeComplete -> true
    else -> false
}

fun NotificationState.toProgress(): NotificationProgress? = when (this) {
    is NotificationState.ClipRecording -> null
    is NotificationState.GapWaiting -> {
        if (nextStartsInSeconds != null && gapTotalSeconds != null) {
            NotificationProgress(
                max = gapTotalSeconds,
                current = (gapTotalSeconds - nextStartsInSeconds).coerceIn(0, gapTotalSeconds),
                indeterminate = false
            )
        } else null
    }
    is NotificationState.Merging -> {
        if (mergeProgressPercent != null) {
            NotificationProgress(max = 100, current = mergeProgressPercent.coerceIn(0, 100), indeterminate = false)
        } else {
            NotificationProgress(max = 0, current = 0, indeterminate = true)
        }
    }
    is NotificationState.MergeComplete -> null
}
```

- [ ] **Step 5.5: Run tests, confirm pass**

```powershell
./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.service.notification.NotificationChannelConfigTest" --tests "com.aritr.rova.service.notification.NotificationProgressTest"
```

Expected: all 18 tests pass (12 in ChannelConfigTest + 6 in ProgressTest).

- [ ] **Step 5.6: Commit**

```bash
git add app/src/main/java/com/aritr/rova/service/notification/NotificationChannelConfig.kt app/src/test/java/com/aritr/rova/service/notification/NotificationChannelConfigTest.kt app/src/test/java/com/aritr/rova/service/notification/NotificationProgressTest.kt
git commit -m "feat(notif): add NotificationChannelConfig with channel routing, accent, dismissibility, progress

M5 §4 + §5. Old RovaRecordingChannel preserved as LEGACY_CHANNEL_ID so
user-set importance/sound overrides are not nuked on install.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Task 6: Create `NotificationActionSpec.kt` + tests

**Files:**
- Create: `app/src/main/java/com/aritr/rova/service/notification/NotificationActionSpec.kt`
- Create: `app/src/test/java/com/aritr/rova/service/notification/NotificationActionSpecTest.kt`

- [ ] **Step 6.1: Write failing tests**

Create `app/src/test/java/com/aritr/rova/service/notification/NotificationActionSpecTest.kt`:

```kotlin
package com.aritr.rova.service.notification

import com.aritr.rova.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M5 §5 — pure-JVM tests for per-state action specs. The service
 * translates a spec into a [androidx.core.app.NotificationCompat.Action]
 * with a real `PendingIntent`; the spec itself is intent metadata only.
 */
class NotificationActionSpecTest {

    @Test fun `ClipRecording produces Stop + Open in order`() {
        val state: NotificationState = NotificationState.ClipRecording(current = 1)
        val actions = state.toActionSpecs()
        assertEquals(2, actions.size)
        assertEquals(NotificationActionKey.STOP, actions[0].key)
        assertEquals(NotificationActionKey.OPEN, actions[1].key)
    }

    @Test fun `GapWaiting produces StopEarly + Open in order`() {
        val state: NotificationState = NotificationState.GapWaiting(nextNumber = 2, nextInLabel = "1:00")
        val actions = state.toActionSpecs()
        assertEquals(2, actions.size)
        assertEquals(NotificationActionKey.STOP_EARLY, actions[0].key)
        assertEquals(NotificationActionKey.OPEN, actions[1].key)
    }

    @Test fun `Merging produces empty action list`() {
        val state: NotificationState = NotificationState.Merging(done = 4, total = 6)
        val actions = state.toActionSpecs()
        assertTrue(actions.isEmpty())
    }

    @Test fun `MergeComplete with sessionId produces View + Share`() {
        val state: NotificationState = NotificationState.MergeComplete(clipCount = 6, sessionId = "abc123")
        val actions = state.toActionSpecs()
        assertEquals(2, actions.size)
        assertEquals(NotificationActionKey.VIEW_IN_LIBRARY, actions[0].key)
        assertEquals("abc123", actions[0].sessionIdExtra)
        assertEquals(NotificationActionKey.SHARE, actions[1].key)
        assertEquals("abc123", actions[1].sessionIdExtra)
    }

    @Test fun `MergeComplete without sessionId produces View without extras`() {
        val state: NotificationState = NotificationState.MergeComplete(clipCount = 6, sessionId = null)
        val actions = state.toActionSpecs()
        assertEquals(2, actions.size)
        assertEquals(NotificationActionKey.VIEW_IN_LIBRARY, actions[0].key)
        assertNull(actions[0].sessionIdExtra)
    }

    @Test fun `every action spec has a non-blank contentDescription resource`() {
        val all = listOf<NotificationState>(
            NotificationState.ClipRecording(current = 1),
            NotificationState.GapWaiting(nextNumber = 1, nextInLabel = "1:00"),
            NotificationState.MergeComplete(clipCount = 1, sessionId = "x")
        )
        all.flatMap { it.toActionSpecs() }.forEach { spec ->
            assertTrue("contentDescription res must be non-zero for ${spec.key}", spec.contentDescriptionRes != 0)
        }
    }

    @Test fun `action labels point to the M5 strings`() {
        val complete: NotificationState = NotificationState.MergeComplete(clipCount = 1)
        val viewAction = complete.toActionSpecs()[0]
        assertEquals(R.string.notification_action_view_in_library, viewAction.labelRes)
        assertEquals(R.string.notification_action_view_in_library_cd, viewAction.contentDescriptionRes)
    }
}
```

- [ ] **Step 6.2: Run, confirm fail**

```powershell
./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.service.notification.NotificationActionSpecTest"
```

Expected: compile failure — `NotificationActionKey`, `NotificationActionSpec`, `toActionSpecs` unresolved.

- [ ] **Step 6.3: Create `NotificationActionSpec.kt`**

```kotlin
package com.aritr.rova.service.notification

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.aritr.rova.R

/**
 * M5 §5 — typed notification action.
 *
 * The spec carries only intent metadata; the service binds it to a
 * real `PendingIntent` (the service is the only owner of the launch
 * surface, by ADR 0001 + ADR 0005 §"Scan Trigger Boundary" — receivers
 * and the activity are tightly coupled to existing wiring there).
 *
 * Icon defaults are intentionally generic system icons; the small-icon
 * channel-tinted vector ([NotificationIconRes]) dominates the visual.
 */
enum class NotificationActionKey { STOP, STOP_EARLY, OPEN, VIEW_IN_LIBRARY, SHARE }

data class NotificationActionSpec(
    val key: NotificationActionKey,
    @StringRes val labelRes: Int,
    @StringRes val contentDescriptionRes: Int,
    @DrawableRes val iconRes: Int,
    val sessionIdExtra: String? = null
)

fun NotificationState.toActionSpecs(): List<NotificationActionSpec> = when (this) {
    is NotificationState.ClipRecording -> listOf(
        stopSpec(NotificationActionKey.STOP, R.string.notification_action_stop, R.string.notification_action_stop_cd),
        openSpec()
    )
    is NotificationState.GapWaiting -> listOf(
        stopSpec(NotificationActionKey.STOP_EARLY, R.string.notification_action_stop_early, R.string.notification_action_stop_early_cd),
        openSpec()
    )
    is NotificationState.Merging -> emptyList()
    is NotificationState.MergeComplete -> listOf(
        NotificationActionSpec(
            key = NotificationActionKey.VIEW_IN_LIBRARY,
            labelRes = R.string.notification_action_view_in_library,
            contentDescriptionRes = R.string.notification_action_view_in_library_cd,
            iconRes = android.R.drawable.ic_menu_view,
            sessionIdExtra = sessionId
        ),
        NotificationActionSpec(
            key = NotificationActionKey.SHARE,
            labelRes = R.string.notification_action_share,
            contentDescriptionRes = R.string.notification_action_share_cd,
            iconRes = android.R.drawable.ic_menu_share,
            sessionIdExtra = sessionId
        )
    )
}

private fun stopSpec(key: NotificationActionKey, @StringRes labelRes: Int, @StringRes cdRes: Int) =
    NotificationActionSpec(
        key = key,
        labelRes = labelRes,
        contentDescriptionRes = cdRes,
        iconRes = android.R.drawable.ic_media_pause,
        sessionIdExtra = null
    )

private fun openSpec() = NotificationActionSpec(
    key = NotificationActionKey.OPEN,
    labelRes = R.string.notification_action_open,
    contentDescriptionRes = R.string.notification_action_open_cd,
    iconRes = android.R.drawable.ic_menu_view,
    sessionIdExtra = null
)
```

- [ ] **Step 6.4: Run, confirm pass**

```powershell
./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.service.notification.NotificationActionSpecTest"
```

Expected: 7 tests pass.

- [ ] **Step 6.5: Commit**

```bash
git add app/src/main/java/com/aritr/rova/service/notification/NotificationActionSpec.kt app/src/test/java/com/aritr/rova/service/notification/NotificationActionSpecTest.kt
git commit -m "feat(notif): add NotificationActionSpec — typed per-state actions

M5 §5. Spec carries intent metadata only; the service binds it to a
real PendingIntent. Merging emits empty action list (mockup-faithful).

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Task 7: MainActivity — read deep-link extras

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/MainActivity.kt`

The "View in Library" action launches MainActivity with two extras: `EXTRA_SESSION_ID` and `EXTRA_TARGET_TAB`. MainActivity reads them on first composition and lifts them into MainScreen via a tab-selection hint. Scroll-to-row is out of scope (spec §2).

- [ ] **Step 7.1: Add intent extras + History tab selection**

Replace the body of `MainActivity` with:

```kotlin
package com.aritr.rova

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.aritr.rova.ui.MainScreen
import com.aritr.rova.ui.theme.RovaTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // ADR 0005 §"Scan Trigger Boundary" — recovery scan is triggered
        // ONLY here, not from RovaApp.onCreate.
        (application as RovaApp).triggerRecoveryScanIfNeeded()
        enableEdgeToEdge()
        val initialTab = readInitialTab(intent)
        setContent {
            RovaTheme {
                MainScreen(initialTab = initialTab)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // Tab re-selection on subsequent launches (e.g., MergeComplete tap
        // while MainActivity is already in the stack) is handled by
        // MainScreen recomposing — we intentionally do not call setContent
        // again. If a future redesign needs runtime tab selection, lift
        // initialTab into a StateFlow and observe.
    }

    private fun readInitialTab(intent: Intent?): InitialTab {
        val target = intent?.getStringExtra(EXTRA_TARGET_TAB) ?: return InitialTab.DEFAULT
        return when (target) {
            TAB_HISTORY -> InitialTab.HISTORY
            else -> InitialTab.DEFAULT
        }
    }

    companion object {
        const val EXTRA_SESSION_ID = "com.aritr.rova.EXTRA_SESSION_ID"
        const val EXTRA_TARGET_TAB = "com.aritr.rova.EXTRA_TARGET_TAB"
        const val TAB_HISTORY = "history"
    }
}

enum class InitialTab { DEFAULT, HISTORY }
```

- [ ] **Step 7.2: Plumb `initialTab` through `MainScreen`**

Open `app/src/main/java/com/aritr/rova/ui/MainScreen.kt`. If `MainScreen` already takes parameters, add `initialTab: InitialTab = InitialTab.DEFAULT` to the signature. Inside, where the bottom-nav selected tab is held in state (likely a `var selectedTab by remember { mutableStateOf(...) }` or similar), wrap the initial value:

```kotlin
val startTab = remember(initialTab) {
    when (initialTab) {
        InitialTab.HISTORY -> /* the existing History tab enum value */
        InitialTab.DEFAULT -> /* the existing default tab enum value */
    }
}
```

Use `startTab` as the initial `mutableStateOf(...)` value. The exact tab enum name depends on the existing screen-tab type — read the file first, locate the tab enum, and substitute.

- [ ] **Step 7.3: Verify compile + existing tests still pass**

```powershell
./gradlew :app:compileDebugKotlin :app:testDebugUnitTest
```

Expected: `BUILD SUCCESSFUL`, all existing tests green (1245 + the new ones from Tasks 3–6).

- [ ] **Step 7.4: Commit**

```bash
git add app/src/main/java/com/aritr/rova/MainActivity.kt app/src/main/java/com/aritr/rova/ui/MainScreen.kt
git commit -m "feat(activity): accept EXTRA_TARGET_TAB + EXTRA_SESSION_ID for deep links

M5 §5.4 — MergeComplete View in Library action launches MainActivity
with these extras. Scroll-to-row is deferred (spec §2 — out of scope).

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Task 8: Register both notification channels in `RovaRecordingService`

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/service/RovaRecordingService.kt`

- [ ] **Step 8.1: Replace `createNotificationChannel()` to register both**

Locate the existing `createNotificationChannel()` at line ~2636 and replace its body. Find:

```kotlin
private fun createNotificationChannel() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Rova Background Recording",
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
}
```

Replace with:

```kotlin
private fun createNotificationChannel() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    val mgr = getSystemService(NotificationManager::class.java)

    // Legacy channel — kept registered (and unused) so existing user
    // importance/sound overrides are not nuked silently on M5 install.
    // Cleanup deletion is a follow-on slice after one release.
    val legacy = NotificationChannel(
        CHANNEL_ID,
        "Rova Background Recording",
        NotificationManager.IMPORTANCE_LOW
    )
    mgr.createNotificationChannel(legacy)

    // M5 §4 — new dual-channel topology.
    val session = NotificationChannel(
        NotificationChannelConfig.SESSION_CHANNEL_ID,
        getString(R.string.notification_channel_session_name),
        NotificationManager.IMPORTANCE_LOW
    ).apply {
        description = getString(R.string.notification_channel_session_desc)
        setShowBadge(false)
    }
    mgr.createNotificationChannel(session)

    val complete = NotificationChannel(
        NotificationChannelConfig.COMPLETE_CHANNEL_ID,
        getString(R.string.notification_channel_complete_name),
        NotificationManager.IMPORTANCE_DEFAULT
    ).apply {
        description = getString(R.string.notification_channel_complete_desc)
        setShowBadge(true)
    }
    mgr.createNotificationChannel(complete)
}
```

Add the imports at the top of the file if missing:

```kotlin
import com.aritr.rova.service.notification.NotificationChannelConfig
import com.aritr.rova.R
```

- [ ] **Step 8.2: Compile gate**

```powershell
./gradlew :app:compileDebugKotlin
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 8.3: Commit**

```bash
git add app/src/main/java/com/aritr/rova/service/RovaRecordingService.kt
git commit -m "feat(service): register session + complete notification channels alongside legacy

M5 §4. Legacy RovaRecordingChannel kept registered (unused) so user
overrides persist. Follow-on slice can delete it after one release.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Task 9: Refactor `createNotification` to per-state builder

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/service/RovaRecordingService.kt`

Today there is one `createNotification(title, contentText, sessionId)` (~line 2671) that emits the same shape regardless of state, plus a free-form `updateNotification(String)` overload (~line 2705) and a typed `updateNotification(NotificationState)` (~line 2716). The typed overload calls into the same `createNotification(title, body, sessionId)` shell. M5 replaces the shell so it builds per-state from helpers.

- [ ] **Step 9.1: Add a new typed overload `createNotification(state, sessionId)` next to the existing one**

Insert this method directly above the existing `createNotification(title, contentText, sessionId)`:

```kotlin
private fun createNotification(state: NotificationState, sessionId: String?): Notification {
    val copy = state.toCopy()
    val channelId = state.toChannelId()
    val accent = state.toAccent()
    val iconRes = state.toIconRes()
    val ongoing = !state.isDismissible()
    val autoCancel = state.isDismissible()

    val openPendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    val builder = NotificationCompat.Builder(this, channelId)
        .setContentTitle(copy.title)
        .setContentText(copy.body)
        .setSmallIcon(iconRes)
        .setColor(accent)
        .setColorized(state is NotificationState.MergeComplete)
        .setContentIntent(openPendingIntent)
        .setOngoing(ongoing)
        .setAutoCancel(autoCancel)
        .setOnlyAlertOnce(state !is NotificationState.MergeComplete)
        .setVisibility(
            if (state is NotificationState.MergeComplete) NotificationCompat.VISIBILITY_PRIVATE
            else NotificationCompat.VISIBILITY_PUBLIC
        )
        .setShowWhen(state is NotificationState.MergeComplete)

    state.toProgress()?.let { p ->
        builder.setProgress(p.max, p.current, p.indeterminate)
    }

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

private fun buildActionIntent(spec: NotificationActionSpec, sessionIdContext: String?): Intent {
    return when (spec.key) {
        NotificationActionKey.STOP, NotificationActionKey.STOP_EARLY ->
            // Stop routes back through the existing user-stop pipeline.
            // RovaRecordingService maps Intent action -> stop call site;
            // we use the same Intent action the existing STOP button used.
            Intent(this, MainActivity::class.java)
                .setAction(ACTION_USER_STOP)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        NotificationActionKey.OPEN ->
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        NotificationActionKey.VIEW_IN_LIBRARY ->
            Intent(this, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_TARGET_TAB, MainActivity.TAB_HISTORY)
                .also { i -> spec.sessionIdExtra?.let { i.putExtra(MainActivity.EXTRA_SESSION_ID, it) } }
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        NotificationActionKey.SHARE -> {
            // Share is a one-shot pass-through. The share chooser is the
            // user-visible entry; we route through MainActivity so the
            // activity can resolve safeShareUri against the merged URI
            // (chooser construction needs Context, which only the
            // activity holds without a notification-level FileProvider
            // workaround).
            Intent(this, MainActivity::class.java)
                .setAction(ACTION_SHARE_RECORDING)
                .also { i -> spec.sessionIdExtra?.let { i.putExtra(MainActivity.EXTRA_SESSION_ID, it) } }
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
    }
}
```

Add the imports (top of file):

```kotlin
import com.aritr.rova.MainActivity
import com.aritr.rova.service.notification.NotificationActionKey
import com.aritr.rova.service.notification.NotificationActionSpec
import com.aritr.rova.service.notification.toActionSpecs
import com.aritr.rova.service.notification.toAccent
import com.aritr.rova.service.notification.toChannelId
import com.aritr.rova.service.notification.toIconRes
import com.aritr.rova.service.notification.toProgress
import com.aritr.rova.service.notification.isDismissible
import com.aritr.rova.service.notification.toCopy
```

Add the action-intent constants alongside the other companion constants (~line 491):

```kotlin
const val ACTION_USER_STOP = "com.aritr.rova.action.USER_STOP"
const val ACTION_SHARE_RECORDING = "com.aritr.rova.action.SHARE_RECORDING"
```

If the existing service already has a user-stop intent action under a different name, reuse it instead — do not introduce a duplicate. Grep the file:

```powershell
Select-String -Path "app/src/main/java/com/aritr/rova/service/RovaRecordingService.kt" -Pattern "ACTION_.*STOP|stopPendingIntent" -List
```

If a `STOP_ACTION` or similar already exists, substitute it for `ACTION_USER_STOP` in the `buildActionIntent` branch.

- [ ] **Step 9.2: Repoint `updateNotification(NotificationState)` at the new builder**

Find the existing typed updater (~line 2716):

```kotlin
private fun updateNotification(state: NotificationState) {
    val copy = state.toCopy()
    getSystemService(NotificationManager::class.java).notify(
        NOTIFICATION_ID,
        createNotification(copy.title, copy.body, currentSessionId)
    )
}
```

Replace with:

```kotlin
private fun updateNotification(state: NotificationState) {
    getSystemService(NotificationManager::class.java).notify(
        NOTIFICATION_ID,
        createNotification(state, currentSessionId)
    )
}
```

Leave the free-form `updateNotification(String)` (~line 2705) unchanged — it is the INIT / error transient path.

- [ ] **Step 9.3: Handle the new `ACTION_USER_STOP` / `ACTION_SHARE_RECORDING` intents**

The notification actions route through `MainActivity` (not the service directly) so existing user-stop wiring is preserved. In `MainActivity.onCreate` / `onNewIntent`, add handling at the end of the function (after `setIntent(intent)` in `onNewIntent`):

```kotlin
when (intent?.action) {
    "com.aritr.rova.action.USER_STOP" -> {
        // Route through the existing stop entry point that the in-app
        // Stop button uses. Locate the existing app-level stop call in
        // RecordViewModel or RovaController and invoke it here.
        // Example surface: RovaController.requestUserStop(this).
        (application as RovaApp).requestUserStopIfRunning()
    }
    "com.aritr.rova.action.SHARE_RECORDING" -> {
        val sessionId = intent.getStringExtra(EXTRA_SESSION_ID) ?: return@apply
        (application as RovaApp).shareRecording(this, sessionId)
    }
}
```

If `RovaApp` does not already have `requestUserStopIfRunning()` and `shareRecording(activity, sessionId)`, add them as thin wrappers around the existing stop pipeline and the existing `safeShareUri` helper from `app/src/main/java/com/aritr/rova/ui/share/ShareUriResolver.kt`:

```kotlin
// In RovaApp.kt
fun requestUserStopIfRunning() {
    // Reuse the existing user-stop path. The same path RecordViewModel
    // takes when the in-app Stop FAB is tapped.
    // Inspect RecordViewModel.onUserStopRequested() (or equivalent)
    // and call the same lower-level entry — typically a service
    // intent or a RovaController function.
}

fun shareRecording(activity: Activity, sessionId: String) {
    val manifest = sessionStore.read(sessionId) ?: return
    val mergedFile = File(manifest.outputPath)
    val uri = com.aritr.rova.ui.share.safeShareUri(activity, mergedFile, manifest.pendingUri?.let { Uri.parse(it) })
        ?: return
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "video/mp4"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    activity.startActivity(Intent.createChooser(send, null))
}
```

The exact field/function names depend on the existing `SessionStore` + `RovaController` API — read the call sites in `RecordViewModel.kt` and `HistoryScreen.kt` to confirm the share construction matches the existing pattern. **Do not invent new persistence**; reuse the existing `manifest.outputPath` / `manifest.pendingUri` reads.

- [ ] **Step 9.4: Compile gate**

```powershell
./gradlew :app:compileDebugKotlin
```

Expected: `BUILD SUCCESSFUL`. If any of the cross-module references in 9.3 (`requestUserStopIfRunning`, `shareRecording`, manifest field names) don't match the actual code, the compile error will tell you exactly which symbol — fix by reading the real names from `RovaApp.kt`, `SessionStore.kt`, `SessionManifest.kt`, then substituting.

- [ ] **Step 9.5: Run the full notification test suite**

```powershell
./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.service.notification.*"
```

Expected: all notification tests pass (NotificationCopy 18 + IconRes 4 + ChannelConfig 12 + Progress 6 + ActionSpec 7 = 47).

- [ ] **Step 9.6: Commit**

```bash
git add app/src/main/java/com/aritr/rova/service/RovaRecordingService.kt app/src/main/java/com/aritr/rova/MainActivity.kt app/src/main/java/com/aritr/rova/RovaApp.kt
git commit -m "feat(notif): per-state builder consuming all four helpers

M5 §5 — createNotification(state, sessionId) builds from channel + accent
+ icon + progress + actions; legacy createNotification(title, body,
sessionId) stays for the String-based updateNotification path.

MainActivity now handles ACTION_USER_STOP and ACTION_SHARE_RECORDING
to keep notification-issued intents on the existing stop + share rails.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Task 10: Wire numeric fields from service state into `NotificationState` construction

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/service/RovaRecordingService.kt`

The service already computes the values needed; the call sites that build `NotificationState` instances need to pass them.

Plan owner: locate every `NotificationState.{ClipRecording, GapWaiting, Merging, MergeComplete}(...)` construction in the service and surrounding files, and pass the optional fields.

- [ ] **Step 10.1: Find all current construction sites**

```powershell
Select-String -Path "app/src/main/java/com/aritr/rova/service/**/*.kt" -Pattern "NotificationState\.(ClipRecording|GapWaiting|Merging|MergeComplete)" -List
```

Capture the list of files and line numbers.

- [ ] **Step 10.2: Plumb `etaSecondsRemaining` into `ClipRecording`**

At each ClipRecording construction site, compute the per-clip remaining seconds from the existing segment boundary state. The service already tracks `segmentStartElapsedRealtime` and `segmentDurationMillis` (or equivalent — read the file). Pass:

```kotlin
val etaSeconds = ((segmentStartElapsedRealtime + segmentDurationMillis) - SystemClock.elapsedRealtime())
    .coerceAtLeast(0L)
    .div(1000L)
    .toInt()
NotificationState.ClipRecording(
    current = currentLoopNumber,
    total = totalLoops,
    etaSecondsRemaining = etaSeconds
)
```

If the exact field names differ, use whatever the service uses for segment-start time and segment duration — `RecordHudFormatters` already consumes the same values for the in-app HUD so the symbols exist.

- [ ] **Step 10.3: Plumb `nextStartsInSeconds` + `gapTotalSeconds` into `GapWaiting`**

At each GapWaiting construction site:

```kotlin
val nextStartsInSec = ((nextTickFireTimeMillis - System.currentTimeMillis()) / 1000L)
    .coerceAtLeast(0L)
    .toInt()
val gapTotalSec = (sessionConfig.intervalMinutes * 60 - sessionConfig.durationSeconds).coerceAtLeast(0)
NotificationState.GapWaiting(
    nextNumber = nextLoopNumber,
    nextInLabel = formatHumanizedCountdown(nextStartsInSec),
    total = totalLoops,
    nextStartsInSeconds = nextStartsInSec,
    gapTotalSeconds = gapTotalSec
)
```

`nextTickFireTimeMillis` comes from `AlarmScheduler` — confirm the symbol name by reading `service/scheduler/AlarmScheduler.kt`. If it isn't surfaced, expose a read-only getter rather than computing in the notification path.

- [ ] **Step 10.4: Plumb `mergeProgressPercent` into `Merging`**

The service already throttles merge-notify via `lastMergeNotifyMillis` and computes a percent. Surface it:

```kotlin
NotificationState.Merging(
    done = segmentsMerged,
    total = totalSegments,
    mergeProgressPercent = (segmentsMerged * 100 / totalSegments.coerceAtLeast(1))
)
```

- [ ] **Step 10.5: Plumb `totalDurationSeconds` + `sessionId` into `MergeComplete`**

In `performMerge` (the only writer of `Terminated.COMPLETED` per `checkCompletedWriteOnlyFromPerformMerge`), sum the segment durations:

```kotlin
val totalDurationSec = (manifest.segments.sumOf { it.durationMillis } / 1000L).toInt()
NotificationState.MergeComplete(
    clipCount = manifest.segments.size,
    totalDurationSeconds = totalDurationSec,
    sessionId = currentSessionId
)
```

If `segments[i].durationMillis` is not a field on `SessionManifest.Segment`, use whatever duration field exists; if no duration is persisted per-segment, leave `totalDurationSeconds = null` and let `toCopy()` fall back to the existing "$N clips saved to Library" body. **Do not bump the manifest schema** to add a duration field — that is an explicit NO-GO (spec §2; replan §7 #8).

- [ ] **Step 10.6: Compile + test gate**

```powershell
./gradlew :app:compileDebugKotlin :app:testDebugUnitTest
```

Expected: `BUILD SUCCESSFUL`, all tests green.

- [ ] **Step 10.7: Commit**

```bash
git add app/src/main/java/com/aritr/rova/service/RovaRecordingService.kt
git commit -m "feat(notif): wire eta, countdown, merge-%, total-duration, sessionId

M5 §6. All values from existing service state — no new manifest fields.
Per-segment duration is best-effort; falls back to the static
N-clips-saved body when SessionManifest does not surface durationMillis.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Task 11: Rate-limit session-channel updates

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/service/RovaRecordingService.kt`

The service already throttles merge notifications via `lastMergeNotifyMillis`. Extend the pattern to ClipRecording + GapWaiting so per-second ETA / countdown ticks don't spam TalkBack.

- [ ] **Step 11.1: Add a throttle gate**

Near the existing `lastMergeNotifyMillis` field (grep for it), add:

```kotlin
@Volatile private var lastSessionNotifyMillis: Long = 0L
private val MIN_SESSION_NOTIFY_INTERVAL_MS = 950L  // ~1Hz; just under a second to absorb scheduler jitter
```

Wrap the `updateNotification(state: NotificationState)` call site so non-terminal session-channel updates honor it:

```kotlin
private fun updateNotification(state: NotificationState) {
    val now = SystemClock.elapsedRealtime()
    val isSessionChannel = state.toChannelId() == NotificationChannelConfig.SESSION_CHANNEL_ID
    val isHighRate = state is NotificationState.ClipRecording || state is NotificationState.GapWaiting
    if (isSessionChannel && isHighRate) {
        if (now - lastSessionNotifyMillis < MIN_SESSION_NOTIFY_INTERVAL_MS) return
        lastSessionNotifyMillis = now
    }
    getSystemService(NotificationManager::class.java).notify(
        NOTIFICATION_ID,
        createNotification(state, currentSessionId)
    )
}
```

State transitions (e.g., ClipRecording → GapWaiting) should always notify — add a transition reset:

```kotlin
@Volatile private var lastNotifiedState: NotificationState? = null

// In updateNotification, BEFORE the throttle check:
val previousState = lastNotifiedState
lastNotifiedState = state
val sameStateClass = previousState != null && previousState::class == state::class
if (!sameStateClass) {
    lastSessionNotifyMillis = 0L  // force-emit on transition
}
```

- [ ] **Step 11.2: Compile + test gate**

```powershell
./gradlew :app:compileDebugKotlin :app:testDebugUnitTest
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 11.3: Commit**

```bash
git add app/src/main/java/com/aritr/rova/service/RovaRecordingService.kt
git commit -m "feat(notif): rate-limit session-channel updates at ~1Hz with transition flush

M5 §6. ClipRecording / GapWaiting tick every second; throttle absorbs
sub-second updates from the service ticker while always emitting on
state transitions.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Task 12: Final gate — lint + test + assemble + real-device smoke

**Files:** none modified

- [ ] **Step 12.1: Lint**

```powershell
./gradlew :app:lintDebug
```

Expected: `BUILD SUCCESSFUL`. The notification builder path should not introduce new warnings. If `NotificationPermission` lint flags `setSmallIcon` use without POST_NOTIFICATIONS, confirm the existing pre-M5 FGS permission flow handles it — no new permission code in M5.

- [ ] **Step 12.2: Unit tests**

```powershell
./gradlew :app:testDebugUnitTest
```

Expected: `BUILD SUCCESSFUL` with test count ≥ 1270 (baseline 1245 + ~25 new), 0 failed.

- [ ] **Step 12.3: Assemble**

```powershell
./gradlew :app:assembleDebug
```

Expected: `BUILD SUCCESSFUL`. APK at `app/build/outputs/apk/debug/app-debug.apk`.

- [ ] **Step 12.4: Install on a real device**

```powershell
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Expected: `Success`.

Emulator does not satisfy this step — CameraX video recording fails consistently on emulators per the project policy. A real phone running Android 14+ is required.

- [ ] **Step 12.5: Real-device smoke checklist (from spec §11)**

Walk through every item. Do not check the box unless verified visually.

- [ ] Start a session — ClipRecording notification: red dot icon, blue accent, "Recording · Clip 1 of N", "X:XX remaining in this clip" body decrementing per second, Stop+Open actions visible
- [ ] Wait through clip end → GapWaiting notification: hourglass icon, blue accent, "Waiting · Clip 2 of N next", body "Next clip starts in M:SS", determinate countdown bar advancing
- [ ] Loop completes → Merging notification: merge-arrows icon, blue accent, "Merging clips · X of N", determinate bar filling, NO actions (empty action row)
- [ ] Merge completes → MergeComplete notification on `recording_complete` channel: green check icon, green accent, "Merge complete", body "N clips · M:SS total · saved to Library", swipe-dismissible, View+Share actions
- [ ] Tap "View in Library" → MainActivity opens with History tab selected
- [ ] Tap "Share" → system share chooser opens with the merged MP4 (video/mp4 MIME)
- [ ] Open Android Settings → Apps → Rova → Notifications: three channels visible ("Recording session", "Recording complete", "Rova Background Recording" legacy)
- [ ] Disable "Recording complete" channel only → start + complete a session: recording-session notifications still post; the complete one is suppressed
- [ ] Enable TalkBack → each state's title + body + actions read aloud; ETA / countdown changes do not spam (1Hz throttle confirmed)
- [ ] Lock the screen during recording → recording-session notification title visible; lock the screen after MergeComplete → body hidden (VISIBILITY_PRIVATE)
- [ ] Force-stop the app mid-merge via Settings → no orphaned MergeComplete notification appears

- [ ] **Step 12.6: Final test summary commit (no code, just a marker)**

```bash
git commit --allow-empty -m "chore(m5): notification redesign v1.0 ready for review

Real-device smoke checklist passes per
docs/superpowers/specs/2026-05-27-notification-redesign-v1-design.md §11.
Test baseline: 1270 / 0-0-0.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

- [ ] **Step 12.7: Push and open PR**

```bash
git push -u origin feat/notification-redesign-v1
gh pr create --title "Milestone 5 — Notification redesign v1.0 (per-state skin + dual channels + a11y)" --body "$(cat <<'EOF'
## Summary
- Per-state notification skin (icon, accent, copy with live numbers, progress, actions, dismissibility)
- Dual channels: `rova_recording_session` (LOW, ongoing) + `rova_recording_complete` (DEFAULT, one-shot dismissible)
- Three new pure helpers (`NotificationChannelConfig.kt`, `NotificationActionSpec.kt`, `NotificationIconRes.kt`)
- ~25 new JVM unit tests
- WCAG 2.2 AA accessibility (contrast verified, contentDescription on all actions, lockscreen privacy on complete)
- Four new vector drawables for status-bar small icons

Spec: `docs/superpowers/specs/2026-05-27-notification-redesign-v1-design.md`
Plan: `docs/superpowers/plans/2026-05-27-notification-redesign-v1.md`

## Test plan
- [x] `./gradlew :app:lintDebug` green
- [x] `./gradlew :app:testDebugUnitTest` green (≥ 1270 / 0-0-0)
- [x] `./gradlew :app:assembleDebug` green
- [x] Real-device smoke checklist per spec §11 passes on Android 14+
- [x] TalkBack walkthrough passes
- [x] Lockscreen visibility behaves per spec (PUBLIC for session, PRIVATE for complete)

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

Expected: PR URL returned.

---

## Self-review

Spec coverage:
- §1 (context audit) — informational only, no task
- §2 (scope: in/out/NO-GO) — Tasks 1–12 cover in-scope; out-of-scope (export sheet, i18n, scroll-to-row) explicitly skipped; NO-GO items explicitly avoided (no 5th state, no Robolectric, no schema bump)
- §3 (architecture deltas — sealed `NotificationState` → 4 helpers → service builder) — Tasks 3, 4, 5, 6, 9 cover the diagram
- §4 (channel topology) — Task 8 + Task 5 (`NotificationChannelConfig`)
- §5 (per-state spec) — Task 3 (copy), Task 4 (icon), Task 5 (channel/accent/progress/dismissible), Task 6 (actions), Task 9 (assemble in builder)
- §6 (data flow — numeric fields) — Task 10
- §7 (drawables) — Task 1
- §8 (a11y — contentDescription, contrast, alert-once, visibility) — Tasks 2, 6, 9 (setOnlyAlertOnce + setVisibility)
- §9 (responsive — BigTextStyle, action overflow, lockscreen) — Task 9 (setVisibility); BigTextStyle deferred; mockup bodies fit collapsed
- §10 (testing) — Tasks 3, 4, 5, 6 (~25 tests)
- §11 (smoke checklist) — Task 12.5
- §12 (build/check impact) — Task 12.1
- §13 (files touched) — all covered
- §14 (risks) — addressed in §6 (rate-limit), §9 (lockscreen), §10 (sessionId-null fallback)
- §15 (acceptance criteria) — Task 12 gates them

Placeholder scan: no "TBD", "TODO", "fill in details", "similar to Task N", or "appropriate error handling" entries. Step 7.2 references the existing tab enum which the engineer reads from `MainScreen.kt` — that is a real concrete reference, not a placeholder. Step 9.3 says "the exact field/function names depend on the existing `SessionStore` + `RovaController` API — read the call sites" because the plan is bounded; this is a real instruction to read named files, not a TBD.

Type consistency:
- `NotificationState` variants — same names everywhere (ClipRecording / GapWaiting / Merging / MergeComplete)
- Helper function names: `toCopy`, `toChannelId`, `toAccent`, `toIconRes`, `toActionSpecs`, `toProgress`, `isDismissible` — used identically in test files (Tasks 3–6) and the service builder (Task 9)
- Constants: `SESSION_CHANNEL_ID`, `COMPLETE_CHANNEL_ID`, `LEGACY_CHANNEL_ID`, `ACCENT_RECORDING`, `ACCENT_COMPLETE` — defined in Task 5, consumed in Tasks 8, 9, 11
- `NotificationActionKey` enum values: `STOP`, `STOP_EARLY`, `OPEN`, `VIEW_IN_LIBRARY`, `SHARE` — same set in tests and `buildActionIntent`

Plan complete and saved.
