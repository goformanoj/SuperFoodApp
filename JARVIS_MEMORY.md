# JARVIS OS — Build Memory

A running log Claude maintains and updates **after every prompt**. It captures
what was asked, what changed, and key decisions — so nothing is lost between
sessions (the build container is ephemeral).

## Project facts
- **Stack:** Kotlin, Jetpack Compose, Material 3
- **Toolchain:** AGP 9.1.0 (built-in Kotlin, no separate kotlin-android plugin),
  Compose compiler 2.2.10, Gradle **9.3.1** (AGP 9.1.0's minimum), JDK 17
- **SDK:** compileSdk/targetSdk 36, minSdk 26 · **applicationId** `com.jarvis.os`
- **Build:** GitHub Actions `.github/workflows/build.yml` → artifact `jarvis-debug-apk`.
  Builds on every push (repo is public → free CI minutes).
- **Branches:** develop on `claude/jarvis-minimal-build-4jwvo1`, mirrored to `main`
  (workflow_dispatch needs the workflow on the default branch).
- **Secrets:** never committed. `GEMINI_API_KEY` is a GitHub Actions secret,
  injected at build time into `BuildConfig.GEMINI_API_KEY`. `.gitignore` blocks
  `local.properties`, `secrets.properties`, `gemini.properties`, `*.env`,
  `google-services.json`, keystores.

## Architecture (current)
- `MainActivity` — hosts `JarvisTheme` + `VoiceHome`; drives `AssistantEngine`
  from the Activity lifecycle and requests `RECORD_AUDIO`.
- `assistant/AssistantEngine` — orchestrates the loop: listen → think → speak →
  listen. Owns the single `VoiceUiState` the UI observes.
- `voice/VoiceController` — callback wrapper over Android `SpeechRecognizer`.
- `voice/Speaker` — Android `TextToSpeech` wrapper.
- `ai/GeminiClient` — minimal Gemini REST client (no SDK), key from BuildConfig.
- `ui/home/HomeScreen` (`VoiceHome`) — menu drawer (top-left) with module
  destinations, centered `HudOrb`, scrollable schedule below.
- `ui/components/HudOrb` — procedural JARVIS HUD orb (rings, ticks, orange arc),
  reacts to mic amplitude.
- `ui/theme/*` — colors, Orbitron/Inter typography, dark theme.

## Secrets in use
- `GROQ_API_KEY` — **primary brain** (Groq, free, no billing). Add in GitHub →
  Settings → Secrets and variables → Actions.
- `GEMINI_API_KEY` — optional fallback (needs billing to be useful).

## Log

### 2026-07-26 — Step 1: minimal buildable app
Created the project from an empty repo: Gradle files, wrapper, workflow, a
Compose `MainActivity` showing "JARVIS". First green build after bumping Gradle
to 9.3.1 (AGP 9.1.0's required minimum).

### 2026-07-26 — Step 2: Home screen
Added theme (Orbitron/Inter bundled fonts), a breathing orb, and a home screen
(greeting, orb, Speak button, Today's Tasks, module grid). Fixed one compile
error: opt-in to `ExperimentalTextApi` for variable-font weights.

### 2026-07-26 — Voice-first home
Replaced the top bar + Speak button with a centered HUD orb that auto-listens
on open (Android `SpeechRecognizer`, `RECORD_AUDIO`), reacting to voice level
with a live transcript.

### 2026-07-26 — Drawer + schedule + Gemini wiring + this memory file
- Added a **navigation drawer** (top-left menu button) with module destinations
  (Home, Speech, Chat, Memory, Files, Calendar, Vision, Automation, Skills,
  Settings). Real screens come in Step 3.
- Moved the **schedule (Today's Tasks) below the orb** — scroll down to reach it;
  the orb fills the first screen.
- **Wired Gemini:** speech → `AssistantEngine` → `GeminiClient` (REST) → reply is
  shown and spoken via `TextToSpeech`, then listening resumes. Key comes from the
  `GEMINI_API_KEY` GitHub secret (empty ⇒ the app tells you to set it).
- Created this memory file; will update it every prompt.

### 2026-07-26 — Gemini key added, rebuild to inject it
User added the `GEMINI_API_KEY` repository secret. The key is injected at build
time, so the previous APK (built before the secret existed) still has an empty
key. Pushed this commit to trigger a fresh build that bakes the key into
`BuildConfig`; the new APK's voice loop should reach Gemini.

### 2026-07-26 — Gemini call failing; surface the real error
On device: mic + speech recognition work (transcribed "hello"), key is injected
(app showed "Couldn't reach the JARVIS brain", not the missing-key message), but
the Gemini HTTP call failed. The client was swallowing the cause. Changed
`GeminiClient` to throw `GeminiException` with a short reason (HTTP code + API
message, or network error) and the UI now shows it in red, so we can diagnose
(bad/restricted key vs model access vs network) and fix precisely.

### 2026-07-26 — Diagnosis: HTTP 429 quota; add model fallback
The surfaced error was `HTTP 429: You exceeded your current quota…` — so the
whole pipeline works (speech → key → Gemini → parsing); only the free-tier
quota for gemini-2.0-flash was exhausted. Added a model fallback list
(gemini-2.0-flash-lite → 2.5-flash → 2.0-flash → 1.5-flash): on 429/404 it
tries the next model. User-side options: wait for the quota to reset, or enable
pay-as-you-go billing in Google AI Studio.

### 2026-07-26 — Fallback was amplifying rate limits; fix
AI Studio dashboard: only ~5 total requests, 4 were 429 — so it's the
per-minute rate limit, not the daily quota. The fallback fired all 4 models per
utterance (3×429 + 1×404 for the now-retired gemini-1.5-flash), which burns the
rate limit faster. Fixes: removed gemini-1.5-flash; models are now
2.5-flash → 2.0-flash → 2.0-flash-lite; only fall through on 404 (model missing),
NOT on 429 — so one utterance = one request. 429 now shows a friendly
"wait a minute or enable billing" message.

### 2026-07-26 — 429 persists with ~zero usage → free-tier quota is 0
Build #20 (single request per utterance) still returns 429 on the very first
call, with almost no usage and 0 output tokens ever recorded. Conclusion: this
is not "too many requests" — the key's free-tier allocation is effectively zero
(commonly because the Gemini free tier is unavailable in the user's region), so
the API needs **billing enabled** to serve any request. No code change; this is
account-side. Recommended: enable pay-as-you-go billing on the key's Google
Cloud project (Flash cost is negligible), then retry once.

### 2026-07-26 — Switch primary brain to Groq (free, no billing)
User declined billing. Added `GroqClient` (OpenAI-compatible chat completions,
models: llama-3.3-70b-versatile → llama-3.1-8b-instant → gemma2-9b-it) and a
`Brain` facade that prefers Groq when `GROQ_API_KEY` is set, else Gemini.
Added `GROQ_API_KEY` BuildConfig field + workflow secret. Engine now calls
`Brain`. User must add the `GROQ_API_KEY` repository secret, then rebuild.

### 2026-07-26 — Groq works; TTS fix + Step 3 (nav, screens, orb states)
Groq answers well (conversational replies on device). Changes:
- **TTS fix:** Groq is so fast the reply often arrived before TextToSpeech
  finished initializing, so speech was skipped. `Speaker` now buffers the reply
  and speaks it once ready, with a language fallback (default → en-US).
- **Step 3 — navigation:** self-rolled (no nav dependency). `JarvisApp` shell:
  top-left menu → drawer with all destinations; Home = live voice screen, others
  are themed "coming soon" placeholders; Back returns to Home; drawer highlights
  the current screen.
- **Orb states:** `HudOrb` accent color now follows the state — Listening=cyan,
  Thinking=blue (and spins faster), Speaking=green, Error=red — animated between.

### 2026-07-26 — "Hey JARVIS" wake word
The engine now has an asleep/awake model. Asleep: always listening but only
reacts when a final transcript contains a wake phrase ("hey jarvis" + common
mishears); until then it shows `Say "Hey JARVIS"` and stays silent. On wake it
answers any command spoken after the phrase (or says "Yes?" if just the wake
word), then stays awake so follow-ups need no wake word. After 18s of silence
it returns to sleep. Wake handling lives in `AssistantEngine.onFinalTranscript`.

### 2026-07-26 — V1 memory: context, persistence, Chat screen, grounding
Completes "V1: memory".
- **Conversation memory:** AI clients (`GroqClient`/`GeminiClient`/`Brain`) now
  take a `List<ChatTurn>` history + a grounding `context`. The engine keeps a
  running conversation and sends the last 20 turns, so follow-ups have context.
- **Persistence:** `ConversationStore` saves the conversation as JSON in
  SharedPreferences (no DB/codegen dependency); loaded on engine init.
- **Chat screen:** the drawer's Chat destination now shows the real
  conversation history (terminal style) with a clear button.
- **Grounding:** the engine feeds today's date + `todaysTasks` into the prompt
  and instructs the model to use only that list — fixes schedule hallucinations.
  Task list moved to `data/Schedule.kt` (single source for UI + AI).
- **Home text lifecycle:** transcript/reply show only while the orb is active
  (listening/thinking/speaking) and disappear when JARVIS returns to sleep.

### 2026-07-26 — Calendar tool use (add events by voice)
JARVIS can now add calendar events conversationally.
- System prompt teaches the model to gather title/date/time (asking one short
  follow-up if missing) and, only after the user confirms, emit a hidden marker
  `<<EVENT|Title|YYYY-MM-DD|HH:MM|60>>`.
- `EventParser` strips the marker from the spoken reply and parses the event.
- `CalendarWriter` inserts directly via CalendarContract when READ/WRITE_CALENDAR
  are granted and a writable calendar exists; otherwise it opens the calendar
  app's new-event screen pre-filled (works without permission).
- Manifest gains READ/WRITE_CALENDAR; MainActivity requests them once at startup.
- Pattern: LLM "tool use" via a confirm-gated structured marker, executed app-side.

### 2026-07-26 — Read the REAL device calendar + fix permission prompt
Two fixes after on-device testing:
- **Permission prompt:** requesting mic then calendar in two back-to-back
  launchers dropped the calendar dialog. MainActivity now requests all missing
  permissions (RECORD_AUDIO, READ/WRITE_CALENDAR) in a single
  RequestMultiplePermissions call.
- **Real calendar grounding:** the AI was fed the hardcoded sample task list, so
  "what's my schedule" read fake events. Added `CalendarReader` (queries
  CalendarContract.Instances for today) and `buildContext` now grounds on the
  real device calendar (or says it needs access if permission is missing).
  The Home "Today's Tasks" card remains a separate sample widget for now.

### 2026-07-26 — App launcher icon (JARVIS orb)
Replaced the default Android icon with a custom adaptive icon: a vector orb
(glow, cyan/electric-blue rings, orange accent arc, glowing core) on the dark
background. Files: `drawable/ic_launcher_foreground.xml` +
`ic_launcher_background.xml`, `mipmap-anydpi-v26/ic_launcher(.round).xml`;
manifest now sets `android:icon`/`android:roundIcon`. minSdk 26 → adaptive icon
covers all devices, no PNGs needed.

### 2026-07-26 — Calendar delete/reschedule (fix duplicate events bug)
Bug: JARVIS could only ADD, so "reschedule" added a new event without removing
the old, and repeated "remove it" requests kept adding duplicates (user had 4
"seminar" copies). Fixes:
- New command protocol (replaces add-only EventParser): `CalendarActions` parses
  `<<CAL|ADD|Title|date|time|dur>>` and `<<CAL|DEL|Title|date|time>>` (multiple
  per reply). Reschedule = DEL old + ADD new.
- `CalendarReader.findMatchingEventIds` matches by title (± time window) on a
  date; `CalendarWriter.deleteEvents` removes them via CalendarContract.
- Grounding now lists upcoming events with dates (next 7 days) so the model can
  target the exact event; system prompts updated: DEL to remove, DEL+ADD to
  reschedule, never add a replacement when removing.

### 2026-07-26 — Redesigned launcher icon (HUD orb)
First orb icon looked too plain. Redrew it to match the in-app HUD orb:
segmented outer ring (6 cyan + 2 orange accent segments), dashed inner ring,
soft middle ring, and a bright white→cyan→blue glowing core, with a faint glow
in the background layer. Still a pure vector adaptive icon.

### 2026-07-26 — Conversation flow: longer awake window + graceful end
- Bumped the awake timeout 18s → 30s so multi-turn exchanges don't sleep too
  soon (the "it forgets" feeling was partly the short window; history was always
  sent — reinforced that in the prompt too).
- Model can append `<<END>>` when the user is done ("no", "that's all", …). The
  engine strips it, speaks the sign-off, then drops back to the asleep
  (wake-word) state. Prompts also ask it to say "anything else?" after a task.
- Note: calendar edits can take a couple minutes to reflect due to Google
  Calendar sync — not an app bug.

### 2026-07-26 — Smoother conversation (latency tuning)
- Recognizer endpointing hints (complete/possibly-complete silence ~900ms, min
  length 800ms) so it responds sooner after the user stops talking; also set
  EXTRA_CALLING_PACKAGE.
- Turn-restart delay 350ms → 200ms for quicker back-and-forth.
- Prompts ask for a single short spoken sentence (less TTS wait).
- Known limitation: Android SpeechRecognizer plays a start "beep" and can't do
  true always-on wake detection without a dedicated engine; smoothing that fully
  would need something like Porcupine (future).

### 2026-07-26 — Conversational quality: honesty + capabilities in prompt
User clarified "smoother" = better conversation, not latency: ask relevant
clarifying questions, don't lose context, and be honest when it can't do
something. Rewrote both system prompts to state explicit CAN (chat, read/add/
delete/reschedule calendar) and CANNOT-yet (alarms/reminders, texting, calls,
opening apps, media, screen vision) capabilities, with a rule to never pretend
or claim an action it didn't perform, plus ask one short clarifying question
when a detail is missing. Prompts are now triple-quoted `val`s (not const).

### 2026-07-26 — GROQ_API_KEY added; rebuild to inject it
User added the `GROQ_API_KEY` secret. Pushed this commit to trigger a build
that bakes the key into `BuildConfig`; the new APK's voice loop should reach
Groq and finally answer.

### 2026-07-26 — Siri-style background wake mode ("Hey JARVIS" anywhere)
User wants JARVIS reachable without launching the app: say "Hey JARVIS" from
any screen and a translucent panel appears to talk. Implementation:
- **`JarvisService`** (foreground service, `foregroundServiceType="microphone"`):
  runs a `WakeWordListener` that continuously listens *only* for the wake word.
  Shows a low-priority ongoing notification ("JARVIS is listening"). On wake it
  launches `OverlayActivity` with the trailing command as an extra.
- **`WakeWordListener`**: thin loop over `VoiceController` that restarts on
  no-input and calls back only when `WakeWord.extractCommand` matches.
- **`OverlayActivity`**: translucent, `singleTask`, excluded from recents. Runs
  its own `AssistantEngine`; `engine.wakeUp(command)` starts it already awake;
  closes itself on `onConversationEnd` (the `<<END>>` / graceful-end path), on
  tap-away, or when it loses focus.
- **Mic-conflict guard**: only one thing can hold the mic. `JarvisService`
  keeps an `appActive` flag; `pauseWake()`/`resumeWake()` are called by both
  `MainActivity` and `OverlayActivity` in onStart/onStop so the foreground
  screen owns the mic while visible and the service resumes wake-listening when
  it's gone.
- **`MainActivity`**: now also requests `SYSTEM_ALERT_WINDOW` ("display over
  other apps", via `Settings.ACTION_MANAGE_OVERLAY_PERMISSION`) and
  `POST_NOTIFICATIONS`, then starts `JarvisService` once mic + overlay are
  granted.
- **Manifest**: added FOREGROUND_SERVICE, FOREGROUND_SERVICE_MICROPHONE,
  POST_NOTIFICATIONS, SYSTEM_ALERT_WINDOW permissions; registered
  `OverlayActivity` (translucent) and `JarvisService`.
- **Honest limits**: this uses Android's `SpeechRecognizer`, not a dedicated
  always-on wake engine (Porcupine etc.), so background listening is best-effort
  — it can be killed by aggressive OEM battery managers, plays a start chime,
  and drains more battery than a real hotword DSP. Requires the user to grant
  "Display over other apps" + notifications.

### 2026-07-26 — Reverted background wake mode (mic conflict, broke in-app)
The Siri-style background service **did not work and broke the working in-app
flow**, so it was rolled back. Root cause confirmed on-device: Android's
`SpeechRecognizer` is a single shared system resource. Running it continuously
inside a foreground service AND in-app means two mic holders at once — a
screenshot showed the mic "in use by JARVIS" (the service) and "in use by
Speech Recognition and Synthesis from Google" (the recognizer) simultaneously,
producing RECOGNIZER_BUSY so neither the overlay nor the in-app wake word
responded. Removed `JarvisService`, `WakeWordListener`, `OverlayActivity`;
restored `MainActivity` + `AndroidManifest` to the b73e28a (known-good) state
(mic + calendar permissions only, no foreground service / overlay / notif
permissions). Kept the harmless `WakeWord.kt` shared matcher.

**Honest conclusion for the record:** true "Hey Siri" always-on background wake
is NOT achievable with the stock `SpeechRecognizer` API. It needs a dedicated
on-device hotword engine (Porcupine / openWakeWord / Vosk) running its own tiny
always-on model, with the heavy Google recognizer only spun up AFTER the
hotword fires — and even then it fights OEM battery killers. That's a separate,
larger piece of work; the app now reliably does in-app "Hey JARVIS" again.

### 2026-07-26 — Screen control v1 (open app + tap a visible control)
First narrow, reliable slice of the "JARVIS controls the screen" vision, using
Android's **AccessibilityService** (separate mechanism from the mic — no repeat
of the background-wake conflict).
- **`ScreenControlService`** (AccessibilityService): given a label, BFS-searches
  the current window's node tree for text/contentDescription containing it,
  climbs to the nearest clickable ancestor, draws a cyan glow outline over its
  bounds (WindowManager TYPE_ACCESSIBILITY_OVERLAY — no SYSTEM_ALERT_WINDOW
  needed), then performs ACTION_CLICK (fallback: tap gesture at centre).
  `tapWhenReady(pkg, label)` polls up to ~7s for the target app to be foreground
  before acting, so "open X then tap Y" works. Static `instance` / `isRunning()`.
- **`AppLauncher`**: resolves a spoken app name to an installed launchable
  package (needs QUERY_ALL_PACKAGES on Android 11+) and starts it.
- **`ScreenActions`**: parses `<<OPEN|App>>` / `<<TAP|Label>>` markers out of the
  reply (same pattern as calendar markers).
- **Engine**: after LLM reply, strips + runs OPEN/TAP. App-open needs no
  permission; tap needs the a11y service — if it's off, JARVIS opens Accessibility
  settings and says to switch it on, then retry.
- **Prompts** (Groq + Gemini): CAN now includes open-app + tap-visible-control;
  CANNOT clarifies it can't type yet and only taps on-screen labelled controls,
  best-effort, and needs the a11y toggle on.
- **Manifest**: QUERY_ALL_PACKAGES + the accessibility `<service>` +
  `res/xml/accessibility_config.xml` + `res/values/strings.xml`.
- **Why "open + tap":** tapping only makes sense on an app that's in front; the
  a11y service runs independently of JARVIS's Activity, so JARVIS launches the
  app and the service completes the tap once it's foreground. No overlay-summon,
  no background mic.
- **Honest limits:** best-effort (breaks if a control has no readable label or an
  app restructures its UI), tap-only (no typing yet), one open + one tap per
  turn, requires the user to enable JARVIS under Settings > Accessibility.
- **Next steps:** typing into the focused field (ACTION_SET_TEXT), scrolling,
  multi-step sequences, and waiting-for-content instead of a fixed poll.

### 2026-07-26 — Fix machine-gun recognizer beep + "doesn't hear me"
User: the mic start-beep repeats on/off every second and JARVIS never hears
them / the orb never reacts. Two causes addressed:
- **The earcon.** Android's SpeechRecognizer plays a start/stop beep every
  session and has no flag to disable it; wake-word mode restarts constantly, so
  it machine-guns. Fix: `VoiceController` now mutes the earcon streams
  (STREAM_MUSIC/SYSTEM/NOTIFICATION via AudioManager.ADJUST_MUTE) while
  listening and restores them on stop/destroy/fatal — listening and TTS never
  overlap, so JARVIS's voice stays audible.
- **Tight restart loop / stuck recognizer.** `startListening(snappy)` now only
  applies the short silence timeouts during an active conversation; when idle
  (waiting for the wake word) it uses the recognizer's default longer session so
  it stays up and actually catches "Hey JARVIS." On ERROR_RECOGNIZER_BUSY /
  ERROR_CLIENT it destroys + recreates the recognizer instead of hammering the
  broken instance. Restart gap 200ms -> 600ms.
- **Note:** the same symptom also appears if the old background-service build is
  still installed (two recognizers fighting the mic). Getting onto this clean
  build is part of the cure.

### 2026-07-26 — Fix "hears the command but never responds"
Symptom (screenshots): "Hey JARVIS" -> "Yes?" works, then the follow-up command
is heard (transcript shows it) but JARVIS stays on "Listening…" and never
answers. Root cause: the app reused ONE SpeechRecognizer instance across turns.
After `cancel()` (which fires when JARVIS speaks "Yes?"), many devices return
only PARTIAL results on the next session and never deliver a final — so ask()
is never called. Fix: `stopListening()` now fully destroys the recognizer so the
next turn builds a fresh instance. (Earlier "known-good" was tap-to-speak =
fresh recognizer per tap, so it never hit this; continuous wake-word listening
exposed it.) This is the real regression behind the recent voice trouble, not
the background service itself.

### 2026-07-26 — Voice input REBUILT from scratch (back to the version that worked)
The wake-word layer had made voice input unreliable ("Hey JARVIS" heard, "Yes?"
said, but the follow-up command was heard yet never answered). Root cause: the
command path listened differently from the wake path — a spoken "Yes?" hand-off
plus an aggressive 900ms silence timeout — and that combination never finalized
on the user's device. Per the user's request, deleted the whole wake-word voice
machinery and rebuilt the simple, proven loop from before:
- **VoiceController**: minimal recognizer intent (no custom silence-timeout
  hints — those were the culprit), reused instance, earcon streams still muted so
  continuous listening doesn't beep.
- **AssistantEngine**: plain always-on loop — listen -> think (Groq) -> speak ->
  listen. No wake word, no "Yes?", no awake/asleep state, no sleep timers, no
  snappy/patient split, no `<<END>>` sleep behavior (marker still stripped).
- Kept everything valuable: conversation memory, calendar add/delete/reschedule,
  and screen control (open app / tap). Deleted `WakeWord.kt`.
This restores the reliable behavior and keeps the features built since.

### 2026-07-26 — Voice rebuild CONFIRMED working + 2 small screen-control fixes
User screenshot: asked a question by voice, it was heard and answered (Speaking)
— the rebuilt always-on loop works. Two follow-ups:
- **Resume bug:** after JARVIS sent the user to Accessibility settings, returning
  to the app didn't resume listening — the `busy` flag stayed true (TTS was cut
  by backgrounding so onSpokenDone never fired). Fix: `resume()` now clears
  `busy` and always re-listens on foreground.
- **Realme a11y location:** Screen control lives under Settings > Accessibility &
  convenience > Accessibility > General tab > "Downloaded apps" > JARVIS Screen
  Control. Updated the spoken NEEDS_PERMISSION guidance to name "Downloaded apps".

### 2026-07-26 — Speak first, THEN switch apps (don't cut off the reply)
User: opening WhatsApp works, but JARVIS's spoken reply gets cut off because the
screen switches before it finishes talking. Fix: the screen action (open app /
tap / send-to-Settings) is now DEFERRED until after TTS completes. ask() decides
what to say and stashes the plan in `pendingScreen`; `onSpokenDone()` runs it
once speaking finishes, so JARVIS says "Opening WhatsApp" fully, then opens it.
Needs-permission message is decided up front via a ScreenControlService.isRunning
pre-check (executeScreen no longer runs before speaking).
