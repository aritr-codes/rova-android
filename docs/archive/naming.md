# App Naming Analysis

## Why Rename?

"Loom" is taken by a well-established screen/video recording SaaS product with millions of users and strong Play Store presence. Using the same name would create confusion, harm discoverability, and risk trademark conflict.

---

## Name Candidates

### Full Longlist (15 names)

| Name | Concept | Notes |
|------|---------|-------|
| **Reel** | Film reel — looping, periodic capture | Simple, visual, but broadly used |
| **Looper** | Recording loop automation | Descriptive, used by some music apps |
| **ClipLoop** | Clips on a loop | Clear but compound, slightly generic |
| **SnapLoop** | Snapping clips in a loop | Energetic, but "Snap" is Snapchat territory |
| **Drillcam** | Camera for drill/rep training | Very specific to athletes; niche appeal |
| **Capsule** | Capturing moments in a capsule | Evokes time capsule; abstract but memorable |
| **Repshot** | One shot per rep | Athlete-focused, clear, punchy |
| **Tempo** | Rhythm and pacing of recording | Musical/athletic feel, short, brandable |
| **Stride** | Progress, movement, training | Athletic feel; implies improvement over time |
| **Frameloop** | Frames on a loop | Descriptive but two words |
| **Rova** | Short invented word (record + nova) | Unique, brandable, no meaning baggage |
| **Cyclo** | Cycle-based recording | Short, implies repetition; used in cycling products |
| **Patchwork** | Stitching segments together | Poetic but abstract; doesn't convey speed |
| **Rollcam** | Roll = start recording | Direct, camera-adjacent, easy to say |
| **Flic** | Flick of recording, short clips | Short, modern, phonetically strong |

---

## Top 5 Candidates

### 1. Tempo
**Why it works:**
- One word, five letters, easy to say and spell in any language
- Evokes rhythm and pacing — perfect for the core concept of timed, periodic recording
- Resonates with athletes ("training tempo", "workout tempo") and creators alike
- No strong existing app in this exact space
- Brandable logo possibilities: metronome, waveform, timer arc

**Tagline idea:** *"Record at your own pace."*

---

### 2. Repshot
**Why it works:**
- Compound of "rep" (repetition, training) and "shot" (camera shot) — communicates the app's purpose in one word
- Immediately understood by the primary target user (athlete filming drills/reps)
- Unique — very unlikely to conflict with existing apps
- Short enough for an app icon label

**Tagline idea:** *"Film every rep, automatically."*

---

### 3. Capsule
**Why it works:**
- Evokes "time capsule" — capturing moments and preserving them
- Works across all use cases: training, vlogging, monitoring, documentation
- Abstract enough to allow broad branding but specific enough to imply purpose
- Sounds polished and modern; fits premium app aesthetics
- High potential for a distinctive logo (pill/capsule shape with camera element)

**Tagline idea:** *"Capture it. Keep it. Come back to it."*

---

### 4. Rova
**Why it works:**
- Invented word — zero trademark risk, no prior associations
- Short (4 letters), phonetically clean, works across languages
- Sounds like "rover" (explores, autonomous) and "nova" (new, bright) without being either
- Entirely ownable as a brand name and domain
- The strongest choice if long-term brand identity matters

**Tagline idea:** *"Autonomous recording, made simple."*

---

### 5. Flic
**Why it works:**
- Evokes "flick" as in a film flick — cinematic association
- Short, modern, fits the one-word minimalist app naming trend (Shazam, Duet, Arc)
- Easy to remember and type; works well as an icon label
- Phonetically distinct from competitors
- Slight risk: "Flic" is a smart button hardware brand, though not a video app

**Tagline idea:** *"One tap. Automatic clips."*

---

## Recommendation

**For an athlete-first product:** go with **Repshot** — it communicates the core value proposition instantly to the target user without needing any explanation.

**For a broader consumer product:** go with **Tempo** — wide appeal, clean branding, works for athletes and creators equally, and has natural associations with rhythm and timing.

**For long-term brand building:** go with **Rova** — fully ownable, unique, no baggage, easy to build identity around.

---

## Implementation Note

**Rova has been chosen and the rebrand is complete.** The following were updated:
- `applicationId` → `com.aritr.rova` in `app/build.gradle.kts`
- Package name throughout source → `com.aritr.rova`
- App label in `AndroidManifest.xml` → `"Rova"`
- Notification channel and service class → `RovaRecordingService`
- Settings class → `RovaSettings`
- Beep sound file → `res/raw/rova_beep.mp3`
