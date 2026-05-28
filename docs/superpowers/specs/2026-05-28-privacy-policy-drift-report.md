# Privacy policy drift report — cleanup pass sub-E Phase 1

**Date:** 2026-05-28
**Spec:** `docs/superpowers/specs/2026-05-28-project-cleanup-pass-design.md`
**Repo audited:** `aritr-codes/rova-privacy` (cloned at `/tmp/rova-privacy-audit` → resolves to `C:/Users/HP/AppData/Local/Temp/rova-privacy-audit` on Windows)
**Policy file:** `index.md` (single file in repo; no README separate from policy)
**Policy version / date in file:** `_Last updated: 2026-05-03_` (line 3)
**rova-android master tip at audit time:** `5e354d2` (origin/master `d54e051`)

---

## Per-surface analysis

### Surface 1 — Inter font via Google Fonts provider (HIGHEST PRIORITY)

**What changed:** Commit `c37e081 feat(ui): add Inter downloadable font (Google Fonts provider)` (M5 Phase 1 follow-on, landed in PR #54 / origin/master `d54e051`) adds Android Downloadable Fonts using `androidx.core.provider.FontProvider`. The Google Fonts FontProvider fetches the Inter typeface from Google's servers on first use, then caches it on device. This is a net-new **outbound network call** initiated by Google Play Services on behalf of the app.

**Currently in policy:**

> Line 36: "Does not request the `INTERNET` permission. The app has no network capability."
> Line 37: "Does not bundle any network or analytics library (no Firebase, no Crashlytics, no Google Analytics, no third-party SDKs that phone home)."

The policy explicitly asserts zero network capability. The Downloadable Fonts API does not use the app's own `INTERNET` permission (Google Play Services holds that permission), so the claim is technically literal — but the assertion "the app has no network capability" and "no third-party SDKs that phone home" is misleading: on first launch, Google Play Services' FontProvider fetches data on the app's behalf. The "no network" framing needs an exception carved out.

**Status:** STALE (existing "no network" claim is now misleading; Inter font is not mentioned at all)

**Suggested revision for "What the app does NOT do" section:**

Replace:
> Does not request the `INTERNET` permission. The app has no network capability.

With:
> Does not request the `INTERNET` permission directly. On first launch, Rova uses Android's Downloadable Fonts API to fetch the Inter typeface. This request is made by Google Play Services' FontProvider component on behalf of the app — Rova does not transmit any user data in this process. After the first fetch the typeface is cached on device and no further network requests are made for fonts. See [Google Play Services' privacy policy](https://policies.google.com/privacy) for details on the FontProvider component's data handling.

And add a new entry (or expand the existing Google note in "What the app does NOT do"):
> Does not bundle analytics, advertising, or crash-reporting libraries. No Firebase, Crashlytics, or Google Analytics SDK.

**Suggested addition for a new "Third-party components" section (or expand "What the app does NOT do"):**

> **Google Fonts (Downloadable Fonts API)**
> Rova uses Android's Downloadable Fonts API to load the Inter typeface. The system component that handles this request is Google Play Services' FontProvider. On first install/launch, Play Services fetches the typeface file from Google's servers. Rova does not send any personally identifiable information to Google as part of this request. The fetched font is cached on device. This is the only outbound network activity associated with Rova; it is handled by the Android OS font infrastructure, not by Rova code.

---

### Surface 2 — M5 notification redesign (custom RemoteViews layout)

**What changed:** PR #54 / M5 redesigned the notification to use `DecoratedCustomViewStyle` with custom `RemoteViews` layouts (Phase 2 visual skin + Phase 3 clip-dots row + Phase 4 Chronometer). Change is purely visual — no new data fields, no new network calls, no new permissions. Content displayed in the notification is still: app name, recording state, segment counter, elapsed time.

**Currently in policy:**

> Line 49–50: `| \`FOREGROUND_SERVICE\`, \`FOREGROUND_SERVICE_CAMERA\`, \`FOREGROUND_SERVICE_MICROPHONE\` | Continue recording with the screen off via a foreground service. |`
> `| \`POST_NOTIFICATIONS\` | Show the recording notification (Android 13+). |`

The notification purpose and the permissions for it are correctly disclosed.

**Status:** CURRENT

**Recommendation:** NO-CHANGE — no new data flow or disclosure gap introduced by the visual redesign.

---

### Surface 3 — DualShot P+L dual-encode (doubles storage footprint)

**What changed:** PRs #22–#35 introduced DualShot mode, which runs two parallel `MediaMuxer` instances producing two separate MP4 output files per session (Portrait + Landscape). In P+L mode, a session that would previously produce one merged video now produces two. This doubles local storage consumption per session in P+L mode.

**Currently in policy:**

> Line 17–19: "Video frames captured from the camera you select. Audio, if microphone permission is granted... Recording configuration you choose (duration, interval, loop count, resolution, presets)."
> Line 27–30: "The public Movies directory for finalized merged videos, using the right Android API..."

The policy describes recording in the singular ("merged video") and does not mention that P+L mode produces two output files. The "Where files are written" section does not distinguish modes.

**Status:** STALE — "merged video" (singular) is inaccurate for P+L sessions; the doubling of storage footprint is undisclosed.

**Suggested revision for "Where files are written":**

Add after line 30 (the Tier 1/2/3 list):
> In Portrait+Landscape (P+L) dual-recording mode, two output files are produced per session (one portrait crop, one landscape crop). Both files are written to the public Movies directory. Storage usage in P+L mode is approximately double that of single-mode recording.

And revise line 27 from "finalized merged videos" to "finalized merged video(s)" or more precisely:
> The public **Movies** directory for finalized output files (one per session in Portrait or Landscape mode; two in P+L dual-recording mode), using the right Android API for the device:

---

### Surface 4 — Thermal sensors (`PowerManager.OnThermalStatusChangedListener`, M3 ADR-0019)

**What changed:** M3 / PR #52 (`e1e121d`) added `PowerManager.OnThermalStatusChangedListener` (ADR-0019) to read device thermal status. The listener reads a coarse thermal severity level (NONE / LIGHT / MODERATE / SEVERE / CRITICAL / EMERGENCY / SHUTDOWN — from `PowerManager.THERMAL_STATUS_*`). This data is: (a) used purely in-process to gate recording via `ThermalHysteresis`, (b) never persisted to disk, (c) never transmitted.

**Currently in policy:** No mention of thermal status, `PowerManager`, or sensor data of any kind.

**Status:** MISSING — the policy has no "sensor data" section and does not mention thermal status monitoring. Many privacy policies include a catch-all "other sensor data" clause; this one does not.

**Risk assessment:** Low risk because the data is never persisted or transmitted. However, thermal status is a real-time hardware sensor reading. Some app stores / legal frameworks (e.g., GDPR's "special categories", though thermal data does not qualify) and store review guidelines require disclosure of any sensor access. For an internal beta this is acceptable to note as ADD/DEFER.

**Suggested addition for a new "Device sensors" section or appended to "What the app processes":**

> **Thermal status**: When a recording session is active, Rova monitors the device's thermal severity level (via `PowerManager.OnThermalStatusChangedListener`). This is used solely to auto-pause recording if the device overheats. The thermal status value is never stored to disk, never transmitted, and is not retained after the session ends.

**Recommendation:** ADD — low-stakes but complete disclosure is better than omission.

---

### Surface 5 — Recovery scan (filesystem + manifest reads on cold launch)

**What changed:** Recovery scan has been present since early milestones; M2 (PR #51) and ADRs 0005/0014/0017/0018 refined the mechanics. On every cold launch, `MainActivity.onCreate` → `RovaApp.triggerRecoveryScanIfNeeded()` → `RecoveryScanner.classifyAll` reads manifests and segment files from app-private storage. No new network calls, no new permissions; all reads are from `Android/data/com.aritr.rova/files/`.

**Currently in policy:**

> Line 20: "Session metadata (start time, segment list, termination reason) used to merge segments and recover from crashes. Stored on the device."
> Line 26: "App-private storage at `Android/data/com.aritr.rova/files/` for in-progress segments, manifests, and intermediate merge outputs."

Both the data collected (session metadata, termination reason) and the storage location are correctly disclosed. The recovery-scan behavior (reading these on cold launch to offer merge/discard) is the natural implication of "used to... recover from crashes."

**Status:** CURRENT

**Recommendation:** NO-CHANGE — adequate coverage. No new disclosure gap.

---

### Surface 6 — Tiered public export (`Tier1/2/3Exporter`)

**What changed:** Tier 1/2/3 export path has been present since early milestones; ADR-0003 and ROADMAP v6 document the three tiers. No new behavior was added in the cleanup pass. The tiers determine the Android API used to write to the public Movies directory.

**Currently in policy:**

> Lines 27–30:
> "The public Movies directory for finalized merged videos, using the right Android API for the device:
> - API 29+: MediaStore (scoped storage, no external storage permission needed).
> - API 26–28: scoped temp file then MediaScannerConnection.
> - API 24–25: direct path under legacy WRITE_EXTERNAL_STORAGE."
> Line 54: `| \`WRITE_EXTERNAL_STORAGE\` (API ≤ 28 only) | Legacy storage write path on Android 9 and below. Not used on API 29+. |`

All three tiers are accurately described, including the API-level branching and the legacy-permission note.

**Status:** CURRENT

**Recommendation:** NO-CHANGE — complete and accurate.

---

### Surface 7 — Foreground service types (`FOREGROUND_SERVICE_CAMERA` + `FOREGROUND_SERVICE_MICROPHONE`)

**What changed:** FGS type declarations (`foregroundServiceType="camera|microphone"` in `AndroidManifest.xml`) have been present since the initial CameraX-recording service. No change in the cleanup pass.

**Currently in policy:**

> Line 49: `| \`FOREGROUND_SERVICE\`, \`FOREGROUND_SERVICE_CAMERA\`, \`FOREGROUND_SERVICE_MICROPHONE\` | Continue recording with the screen off via a foreground service. |`

All three FGS permission entries are explicitly listed in the Permissions table with a clear description.

**Status:** CURRENT

**Recommendation:** NO-CHANGE — the API 30+ FGS type declarations are correctly disclosed.

---

### Surface 8 — `SCHEDULE_EXACT_ALARM` + `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`

**What changed:** Both permissions have been present since the AlarmManager-based segment loop was introduced. No change in the cleanup pass.

**Currently in policy:**

> Line 52: `| \`SCHEDULE_EXACT_ALARM\` | Schedule the segment-boundary and loop-stop alarms that drive the periodic recording loop. |`
> Line 53: `| \`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS\` | Optional prompt — guides you to exempt Rova from Doze so long unattended sessions are not killed. You can decline. |`

Both permissions are explicitly listed with accurate explanations. The "You can decline" note on battery optimizations is good user-facing transparency.

**Status:** CURRENT

**Recommendation:** NO-CHANGE — both are correctly disclosed.

---

## Summary

| # | Surface | Currently in policy | Status | Recommendation |
|---|---|---|---|---|
| 1 | Inter font / Google Fonts provider | "No network capability" (line 36) — contradicted by Downloadable Fonts; Inter not mentioned | STALE | REVISE — existing "no network" claim + ADD new Google Fonts disclosure paragraph |
| 2 | M5 notification redesign | `POST_NOTIFICATIONS` + FGS permissions listed (lines 49–50) | CURRENT | NO-CHANGE |
| 3 | DualShot P+L dual-encode | "Merged video" singular; no mention of dual-file output or doubled storage | STALE | REVISE — "Where files are written" section |
| 4 | Thermal sensors (`PowerManager` listener) | Not mentioned | MISSING | ADD — sensor data paragraph; low risk but complete |
| 5 | Recovery scan (manifest reads on cold launch) | Session metadata + app-private storage covered (lines 20, 26) | CURRENT | NO-CHANGE |
| 6 | Tiered public export (Tier1/2/3) | All three API-level tiers explicitly described (lines 27–30, 54) | CURRENT | NO-CHANGE |
| 7 | FGS types (CAMERA + MICROPHONE) | Explicitly listed in permissions table (line 49) | CURRENT | NO-CHANGE |
| 8 | `SCHEDULE_EXACT_ALARM` + `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Both explicitly listed with descriptions (lines 52–53) | CURRENT | NO-CHANGE |

**Counts:** MISSING: 1 · STALE: 2 · CURRENT: 5 · TBD-VERIFY: 0

---

## Recommended actions (owner go/no-go)

| # | Surface | Recommendation | Reason |
|---|---|---|---|
| 1 | Inter font / Google Fonts | REVISE | Existing "no network capability" claim directly contradicts the Downloadable Fonts outbound call via Google Play Services. The claim must be scoped; a new Google Fonts disclosure paragraph should be added. This is the most legally significant gap — the "no network" statement is the first thing a privacy reviewer (app store, GDPR audit) would check. |
| 2 | M5 notification redesign | NO-CHANGE | Visual-only redesign; no new data surfaces. |
| 3 | DualShot P+L | REVISE | "Finalized merged videos" (singular framing) is inaccurate for P+L mode; doubled storage footprint is undisclosed. Low-stakes but inaccurate. |
| 4 | Thermal sensors | ADD | Sensor access is not mentioned. Although this data never leaves the device, complete disclosure is better practice. Recommend adding a one-sentence note in "What the app processes" or a new "Device sensors" paragraph. |
| 5 | Recovery scan | NO-CHANGE | Adequately covered. |
| 6 | Tiered public export | NO-CHANGE | Accurately described across all API levels. |
| 7 | FGS types | NO-CHANGE | Correctly disclosed. |
| 8 | SCHEDULE_EXACT_ALARM + battery optimizations | NO-CHANGE | Both listed with accurate explanations. |

---

## Phase 2 scope (pending owner go/no-go)

If owner approves, Phase 2 applies exactly these edits to `aritr-codes/rova-privacy` `index.md` on branch `update/2026-05-28-data-handling-refresh`:

1. **Surface 1 (REVISE):** Revise the "Does not request the `INTERNET` permission" line in "What the app does NOT do"; add a Google Fonts disclosure paragraph (as "Third-party components" section or subsection of "What the app does NOT do"). This is the HIGHEST PRIORITY change.
2. **Surface 3 (REVISE):** Update "Where files are written" to mention P+L dual-output (two files, doubled storage).
3. **Surface 4 (ADD):** Add thermal sensor note to "What the app processes" or a new "Device sensors" section.

Surfaces 2, 5, 6, 7, 8: NO-CHANGE — no edits to policy needed.

_Owner decision needed for each: approve, defer, or reject._
