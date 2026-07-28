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

### 2026-07-26 — ✅ End-to-end confirmed + ROADMAP
User confirmed: JARVIS spoke, opened YouTube, tapped Search, clean handoff —
voice + brain + memory + calendar + screen control all working on-device.

## Guiding principles (learned the hard way this session)
1. One small change -> one green build -> test on device -> next change.
2. Never stack a fragile feature on a working one without a fallback / known-good baseline.
3. When patches pile up and it's still broken, STOP patching and reset to the last version that worked.
4. Be honest about what stock Android can and cannot do (no reliable always-on wake word; screen control is best-effort).

## Roadmap
### Phase 1 — Make it feel finished (RECOMMENDED NEXT)
- Tap-to-talk toggle: choose always-on vs press-to-talk (stops it answering random speech).
- Clear the on-screen transcript/reply when idle.
- First-run onboarding for the 3 permissions (mic / calendar / accessibility) incl. the Realme "Downloaded apps" path.
- Simple Settings screen (clear memory, switch listening mode).
- Graceful error states (no network, missing permission).

### Phase 2 — Grow screen control
- Type into fields (ACTION_SET_TEXT): "type ... and send".
- Scroll / back / home global actions.
- Multi-step chains: "open Messages, tap Mom, type 'running late', send".
- Smarter/fuzzier on-screen element matching.

### Phase 3 — Reliable device skills (real APIs, not screen-poking)
- Alarms / timers / reminders (AlarmClock intents).
- Send SMS, place calls (confirm first).
- Toggle wifi / torch / DND, brightness, volume; media play/pause/next.
- Weather + web answers (needs an API).

### Phase 4 — Smarter & smoother
- Proper wake word (Porcupine / openWakeWord) for real hands-free.
- Streaming replies (snappier feel).
- Long-term memory of user facts/preferences.
- Vision: "what's on my screen?" (screenshot -> model).

### Phase 5 — Polish & durability
- Offline handling, battery/perf, app polish, full icon set.

### 2026-07-26 — Screen control: smarter matching + scrolling
User: "open WhatsApp and open the Mom chat" tapped the wrong thing ("XYZ Mom",
then "Mom's status"), and couldn't reach the Mom chat because it's below the fold
(no scrolling). Rewrote ScreenControlService matching:
- Scored matching: exact name (100) > starts-with-word (90) > whole-word (65) >
  contains (55); visible TEXT outranks contentDescription. So "Mom" prefers the
  real chat row over "XYZ Mom" and a "Mom's status" entry. startsWithWord ignores
  apostrophe boundaries so "Mom" doesn't match "Mom's status".
- **Scrolling**: if no confident (>=70) match is on screen, find a scrollable
  node, ACTION_SCROLL_FORWARD, and re-scan (up to 8 scrolls) to hunt for the
  target below the fold. Tap a confident match immediately; only tap a weak match
  once scrolling is exhausted.
- Prompt nudge (both brains): for <<TAP|..>> use the exact short on-screen name
  (e.g. "Mom"), not a phrase.
Still best-effort by nature (accessibility tree varies per app).

### 2026-07-26 — Tell the brain it can scroll (prompt caught up to the code)
Build #65 gave the CODE auto-scroll (a <<TAP|..>> scrolls to find off-screen
targets), but the system prompt still said "cannot tap things not currently on
screen" — so when the user said "scroll and find the Mother chat," the LLM
refused. Fixed both prompts: removed the off-screen restriction, and noted that
a <<TAP|..>> automatically scrolls, so "scroll to / find / open <chat>" should
just emit the tap and it must never claim it can't scroll. (Scrolling is
automatic — the user doesn't need to say "scroll"; "open the chat with Mother"
is enough.)

### 2026-07-26 — Scroll DOWN the list (not across tabs) + stop the "anything else?" tic
Two fixes:
- **Vertical scroll:** the scroll code grabbed the first scrollable node, which in
  WhatsApp is the horizontal tab pager (Chats/Updates/Communities/Calls), so it
  flipped tabs instead of scrolling the chat list. Now `findVerticalScrollable`
  picks the largest VERTICAL scrollable (via ACTION_SCROLL_DOWN/UP support on
  API 30+, else collectionInfo columns/rows, else taller-than-wide) and scrolls
  with ACTION_SCROLL_DOWN.
- **Repetitive closing:** the prompt told it to "ask if there's anything else"
  after every task, so it repeated that line constantly (even to a bare "Jarvis").
  Rewrote both prompts: keep replies natural/varied, don't repeat that phrase, and
  reply briefly (e.g. "Yes?") to a bare name/greeting.

### 2026-07-26 — Scroll fix take 2 (only scroll DOWN-capable nodes) + natural spoken replies
Still scrolled sideways because isVertical() misclassified WhatsApp's full-screen
tab pager as vertical (tall + no directional action -> height>width fallback said
vertical), then ACTION_SCROLL_FORWARD moved it right. New approach: scrollForward
only ever scrolls the largest node that EXPLICITLY supports ACTION_SCROLL_DOWN
(guaranteed vertical) on API 30+; if none, don't scroll. Older APIs use a strict
list-shape fallback.
Also: JARVIS kept saying "On it" for every screen command because the model
emitted only the <<OPEN|..>>/<<TAP|..>> markers and no spoken text (clean was
blank -> "On it" fallback). Prompt now tells it to ALWAYS include a short, varied,
natural spoken sentence alongside the markers, so the user hears real replies.

### 2026-07-26 — Scroll via a real swipe gesture (final, reliable approach)
Build #71 broke scrolling entirely: it only scrolled nodes advertising
ACTION_SCROLL_DOWN, but WhatsApp's chat list on the user's device doesn't expose
that action, so nothing scrolled — it opened WhatsApp and sat there. Root lesson:
stop depending on what a list *claims* it can do. Now `scrollForward` dispatches
a real finger SWIPE UP (dispatchGesture) over the largest scrollable area's
bounds — a vertical swipe scrolls vertical content and cannot flip horizontal
tabs, and it works regardless of advertised scroll actions. Removed the
action-based helpers and the unused Build import.

### 2026-07-27 — Part A: multi-step commands + typing (foundation for compound instructions)
User wants: typing, compound "understand straight up" commands ("show me a
standup comedy video" -> open YouTube, search, wait), a continuous background
"work session" that starts only after opening an app + giving a command and
stops on "thank you Jarvis", and a plan for tap accuracy.
Built Part A now:
- `ScreenActions` now parses an ORDERED sequence of steps (sealed `ScreenStep`:
  Open / Tap / Type / Enter) from the reply, not just one open+tap.
- `ScreenControlService.runSteps` executes them in order with settle delays:
  Open (launch + wait for foreground), Tap (await app + scroll-find + tap),
  Type (wait for an editable field, ACTION_SET_TEXT), Enter (ACTION_IME_ENTER
  = search/go, API 30+).
- Engine runs the whole sequence via the service when it needs accessibility;
  open-only sequences go straight through AppLauncher (no a11y needed).
- Prompts: added <<TYPE|..>> and <<ENTER>>, taught chaining with examples
  (YouTube search, WhatsApp chat). Removed "can't type" from CANNOT.

## Roadmap for the rest of this request (NOT yet built)
- **Part B — continuous "work session":** foreground service that listens for
  follow-up commands ONLY after JARVIS opened an app from a command; keeps
  hearing while the user is in the other app; stops on "thank you Jarvis". Must
  keep exactly one mic owner at a time (in-app engine when JARVIS is foreground,
  service when it's backgrounded) to avoid the old mic conflict.
- **Part C — accuracy:** feed the AI the on-screen text (accessibility tree) so
  it taps what's really there; verify a tap changed the screen and retry; ask to
  disambiguate ties; per-app hints for common flows.
- **Part D — polish:** tap-to-talk toggle, clear idle text, onboarding.

### 2026-07-27 — Free the LLM: conversational-first prompt (user feedback)
User: "we have an AI, why can't you make the AI think and act... you're
restricting the right of the LLM to think." Rewrote both system prompts to put
being a smart, natural, knowledgeable conversational assistant FIRST — answer
questions, explain, reason, chat, give fuller answers when asked — and frame the
phone-control + calendar abilities as TOOLS it reaches for when the user wants an
action, not a rigid CAN/CANNOT cage. Dropped the "single short sentence only"
constraint (allow fuller answers when asked). Kept the command protocols intact.
(Per user: Part A is the stopping point for the screen-control feature; awaiting
further instructions before Part B/C/D.)

### 2026-07-27 — Added four living project docs
Created repo-root working docs so the vision, status, next steps, and resume-state
are written down (not just in chat): PRODUCT_PLAN.md (north star + full spec +
API-key security reality), PROGRESS.md (living status + feature table),
EXECUTION_PLAN.md (ordered build queue + working loop + pre-handoff checklist),
SESSION_HANDOFF.md (CI/secrets/architecture/marker-protocol/install runbook/gotchas).
Refreshed README's stale status line to point at them. These are the files I work
from going forward; JARVIS_MEMORY.md stays as the detailed chronological log.
Also answered the user's Groq-key security question: key is never in source but IS
embedded in the APK (public repo -> extractable) -> keep Groq free-tier/no-billing +
rotate if abused.

### 2026-07-27 — Commercialization plan + key-security architecture (new COMMERCIALIZATION.md)
User asked three things: (1) "don't you merge to main?", (2) all options for keeping the API
key secret, (3) how to put JARVIS on the Play Store and commercialise it.

On (1): main WAS up to date (4029979 = the docs commit, branch level with it). The real gap was
that SESSION_HANDOFF/EXECUTION_PLAN still named the OLD session branch
(claude/jarvis-minimal-build-4jwvo1) while this session develops on claude/root-file-context-ko322w.
Fixed: the branch is now described as per-session, and SESSION_HANDOFF gained an explicit
"Definition of done" (push -> artifact appears -> fast-forward main -> update docs). A green
commit is never left unmerged.

On (2) and (3): created COMMERCIALIZATION.md with the full 7-option key matrix (status quo,
private repo, obfuscation, BYOK, backend proxy, Firebase AI Logic, on-device model), the chosen
architecture, and the Play Store path in phases A-F.

User decisions this session:
- **Backend proxy is the product path; BYOK is a later bonus.** Corrected an impression I gave
  that they were equal halves — the app must work end-to-end from the Play Store with no key
  prompt, like every other AI app. What stops the current build scaling is not Play policy but
  arithmetic: all installs share one Groq key and Groq's limits are per-account.
- **Freemium subscription.** Real numbers checked: Groq 70B is $0.59/M in, $0.79/M out => ~$0.001
  per voice turn; typical user ~$0.45/mo; Google takes ~15% => ~75% margin at ~$2.30/mo. Free
  tier must run on the cheap 8B model or 1,000 free users cost ~$600/mo.
- **Firebase Auth** (anonymous free tier, Google sign-in to subscribe) + **Play Integrity** on
  every backend call. Server stores only a usage counter; history stays on-device.
- **Name deferred but gated:** "JARVIS" is a Marvel trademark and applicationId can NEVER change
  after the first publish — both must be settled before the first upload.
- **Sequencing: features first** (Parts B/C/D), then commercialization as the new **Part E**.

Play compliance blockers written down while they're cheap to design around: the
AccessibilityService declaration + prominent disclosure/consent (undeclared use = suspension),
QUERY_ALL_PACKAGES (replace with a <queries> MAIN/LAUNCHER block), the foreground-service mic
type that Part B needs anyway, and the Part C constraint that screen text going to a third-party
LLM needs consent plus password/OTP redaction before it leaves the device.

Next: Part B — continuous work session, built Play-compliant from day one.

### 2026-07-27 — Real testing: unit tests in CI, diagnostics, and a typed command box
User asked how the app actually gets tested, and whether Claude could run it.
Checked the container honestly: **no /dev/kvm, no virtualisation extensions, no
Android SDK, and Gradle cannot even download its own wrapper through the proxy**
— so no emulator and no local build here, ever. Everything runs on CI.

Also found the repo had **zero automated tests** — no `src/test`, no JUnit
dependency. The "testing" was porting the regexes to Python and running cases,
which only tests the Python translation and gets thrown away. Fixed properly:

1. **Unit tests (build #83/#85).** `ScreenActions` has no imports at all and
   `CalendarActions` only imports SimpleDateFormat/Locale — both are pure JVM,
   so plain JUnit covers them with no device. Added tests for the open→tap→type
   →enter chain, marker stripping, case-insensitivity, empty args, calendar
   add/del/reschedule, bad dates, and defaults. CI runs `testDebugUnitTest`
   BEFORE `assembleDebug`, so "artifact present" now means compiles AND correct.
   Test reports upload as an artifact on failure.
2. **DebugLog (#85).** Capped in-memory trace of every turn: heard → raw model
   reply → markers parsed → calendar/screen actions → spoken. Every entry goes
   through a redactor (gsk_*, AIza*, Bearer tokens) because the log is designed
   to be shared out of the app. Ring buffer + redaction are unit-tested.
3. **Diagnostics screen (#85).** Drawer → Diagnostics. Self-checks for mic,
   calendar r/w, speech recognition, accessibility service, AI key, device info;
   a "Test AI" button doing a real round-trip reporting provider + latency (the
   one check impossible from the build environment); Share exports checks + trace
   as plain text.
4. **Typed command box (#85).** `AssistantEngine.submitText()` runs a typed
   command through the identical pipeline, skipping only the mic. Most failures
   are in the reasoning or the taps, not the speech.

Rejected: driving the user's phone remotely (behind NAT, restricted egress,
would need an open ADB tunnel on their daily driver — fragile and a security
hole). Deferred to later: emulator in GitHub Actions with screenshot artifacts
(good for launch/UI regressions, useless for the mic and for real WhatsApp/
YouTube), and Firebase Test Lab Robo tests on real devices (free tier: 5
physical + 10 virtual runs/day) before the Play launch.

Housekeeping: two commits this session carried a Co-Authored-By trailer naming
the model, which PRODUCT_PLAN forbids in any pushed artifact. With the user's
go-ahead, rewrote both messages (trees verified identical) and force-pushed
main and the branch. Do not add that trailer here.

### 2026-07-27 — Part B shipped: continuous work session (build #89)
After a command opens an app, JARVIS keeps listening for follow-ups and stops on
"thank you Jarvis"; opening/closing JARVIS without a command never starts a
session. The old background-wake failure was solved structurally rather than
carefully: `WorkSession.owner` is a single computed value (NONE/ENGINE/SESSION),
so "two mic owners" is not a representable state, and the foreground service
never opens the mic at all — the engine keeps the process's only VoiceController
and the service only holds the app in the foreground state (type=microphone) and
shows a notification with Stop. Removed the engine's own `visible` flag so there
is one source of truth. The service starts when the session begins, while still
on screen: Android 12+ throws ForegroundServiceStartNotAllowedException for an
FGS started from the background.

### 2026-07-27 — Part C: making it actually see the screen (builds #91-#96)
Every fix below came from a trace the user shared out of the Diagnostics screen.
The traces repeatedly contradicted the obvious guess — in most cases the model's
plan was reasonable and the EXECUTOR was wrong.

- **#91 marker robustness.** A screenshot showed `<<TAP|Thriller by Michael
  Jackson>` printed on screen and spoken. The model closed the marker with one
  `>`; the rigid parser neither ran the tap nor stripped the text. Both parsers
  now accept one or two brackets, and a catch-all strips anything still shaped
  like a marker before speaking.
- **#91 executor.** After ENTER the code waited a blind 700ms, so a following tap
  resolved against the pre-search screen; and a search field still holding the
  query text scored a perfect 100 exact match, so "tap Believe" tapped the search
  box. ENTER now waits for the visible text to actually change (bounded), and
  editable nodes are demoted rather than excluded.
- **#93 screen awareness.** The real disease: the model replanned from zero every
  turn, so "send the message" re-ran OPEN/TAP/TYPE/ENTER and re-tapping "Mom"
  inside the Mom chat opened her PROFILE. `describeScreen()` now renders the live
  tree into context with an instruction to emit only the steps still needed.
  Passwords and OTP-shaped digits are redacted; scan bounded (400 nodes/45 items).
- **#94 state memory.** Bug in #93: `rootInActiveWindow` returns JARVIS's own UI
  when JARVIS is in front, so it described "[Good evening] [J.A.R.V.I.S.]" and
  concluded it was inside itself — worse than blindness. Now it reads the
  front-most non-JARVIS window (falling back to scanning interactive windows) and
  otherwise reports what the user was last in, and how long ago.
- **#95 type vs send.** "only type hello in the chat" still sent, because every
  TYPE example in the prompt ended in `<<ENTER>>` — the model learned typing and
  submitting as one move. Fixed in the prompt AND with `SendGuard`, which drops a
  trailing submit/Send when the user clearly asked to compose and clearly did not
  authorise sending. Sending is irreversible, so it does not rely on the prompt
  alone. Deliberately narrow: any mention of send/post or search/find/play leaves
  the plan alone, and a submit mid-sequence survives.
- **#96 concurrency + honest failure.** Timestamps proved two sequences running at
  once and fighting over the screen (one typing while the other tapped Voice
  search). runSteps now supersedes: bump a token, clear pending callbacks.
  `<<OPEN|X>>` no longer relaunches an app already in front (that reset YouTube to
  the home feed, losing the user's results). And `tapNode` now returns whether the
  tap happened: `seek` used to always call `onDone(true)`, so a dead tap was
  announced as "Playing the Thriller video" four times in a row with no error.

**Still open:** the album-row tap does nothing (now reported honestly, cause
unknown — the outline overlay was ruled out, FLAG_NOT_TOUCHABLE is set), and the
FIRST command of a chain still plans blind because the target app is not open yet.
That is what `<<PICK>>` (mid-sequence re-planning) is for, and it is next.

**Process note:** docs went 8 commits stale during this run while chasing bug
reports. EXECUTION_PLAN says to update PROGRESS and JARVIS_MEMORY every time —
that means after each merge, not at the end of a debugging session.

### 2026-07-28 — <<PICK>>: choosing by looking, not by guessing (b922b65)
The last blind spot in Part C. Screen awareness fixed follow-up commands, but the
FIRST command of a chain still planned against a screen that did not exist yet —
when "open YouTube and play Thriller" is written, YouTube is not open and there
are no results. So the model had to invent a label, and the traces showed the
cost: tapping the search box (it still held the query text) or scrolling for the
literal words "first video" until it gave up.

"The first video result" is an intent, not a label. It cannot be matched as text,
only chosen once the results exist. <<PICK|..>> defers the decision to execution:
the executor lists what is genuinely tappable (deduped, capped at 25, same
redaction as the screen description) and asks the model which one, via a separate
tiny call carrying none of the assistant prompt — cheap, fast, one job. An index
outside range, or 0, is an honest failure rather than an arbitrary tap.

Two details that matter: the chosen option is re-found BY LABEL at tap time
rather than holding the node (the round trip is ~1s and node handles go stale),
and the run token is re-checked in the callback so a pick answered after a newer
command started is discarded instead of acting on a screen that has moved on.
Groq only — a Gemini-only build reports the step failed rather than guessing.

### 2026-07-28 — Regression: it stopped opening apps (d801260)
From a trace: "open Amazon music for me" -> "I'm not able to open other apps
directly", then "I can only interact with the current app, which is WhatsApp".

Caused by my own screen-awareness prompt. Telling the model to use the real
on-screen labels and not to pretend it can see things was over-generalised into
"I may only act on what is visible", and it stopped launching apps entirely.
Opening never depended on the screen. The lesson is general: **stating what the
model CAN see implies a limit on everything else**, so the powers that do not
depend on the screen (OPEN, BACK, HOME) must be restated explicitly, including in
the blank-screen branch where the wrong inference is most tempting.

Also added <<BACK>> and <<HOME>> via performGlobalAction. "Go back" had produced
no marker at all once, and then <<TAP|Back>> — hunting for a control labelled
"Back" that many screens do not have, and some have several of. A global action
is deterministic and cannot mis-target.

Working correctly in the same trace, worth recording: "already in WhatsApp — not
relaunching" fired as designed, and the screen description handed the model the
real chat preview text to tap.

### 2026-07-28 — A proper voice (aa74c4d)
Speaker took the engine's default voice at default pitch and rate — the blandest
option installed. Now it ranks every installed voice: English only, en-GB ahead
of en-US ahead of the rest, male ahead of female, higher quality ahead of lower,
local ahead of network (a network voice adds latency to every reply and fails
offline, so it only wins when nothing local is close). Pitch 0.92, rate 0.98.
The chosen voice is written to the trace so a device that sounds wrong can be
diagnosed from a shared log.

Scoring lives in VoicePreference on plain values rather than Android types, so it
is unit-tested. The test that earns its keep: **"female" contains "male"**, so a
naive contains() check picks female voices about half the time — a bug that would
have shipped and been blamed on the TTS engine.

Costed the premium options rather than assuming: Groq PlayAI TTS is $50/M
characters ≈ $0.005 per reply, about 5x the LLM cost per turn and ~$4.50/month
for a heavy user — more than the modelled subscription price. So a cloud voice
cannot be the default; it belongs in the paid tier (Part E), where it only costs
money for users already paying.

### 2026-07-28 — The docs hook did its job
The Stop hook added in 02a0d04 fired on this batch: "The project docs are behind
the code by 3 commit(s)." That is exactly the failure it was built to catch, and
it caught it before the turn ended rather than eight commits later.

### 2026-07-28 — Typing opens its own field; a regression traced to my own fix (48d7847)
Device trace, perfectly correlated:

  15:46:53  relaunched      Tap(Search) -> Type OK
  15:48:09  relaunched      Tap(Search) -> Type OK
  15:47:38  already in app  Tap(Search) -> Type FAILED
  15:48:48  already in app  Tap(Search) -> Type FAILED

Type failed if and only if the app was NOT relaunched. Cause: my own #96 change.
Skipping the relaunch of an app already in front was right on its own terms — it
was throwing away the results the user was looking at — but relaunching had a
side effect the rest of the plan silently depended on: it reset YouTube to its
HOME screen, where "Search" is a real button. On the results page there is no
such button, the query sits in a non-editable bar, <<TAP|Search>> lands on
nothing useful, and no editable ever appears.

Fix: remove the dependency rather than patch it. Type no longer assumes an
earlier step opened a field; when nothing typeable appears it taps a likely way
into text entry itself and keeps waiting. Attempts are SPACED and work through
candidates in turn, because one retry would be useless here — the earlier tap
already "succeeded" on the wrong node, so re-tapping it changes nothing. The
schedule (tries 5, 8, 11, 14) fits inside the existing 15-poll budget, so a real
failure is still reported instead of hanging.

Two lessons worth more than the fix:
- **Deleting a behaviour deletes its side effects.** Ask what was leaning on it.
- **A step must not rely on an earlier step having guessed right.** If a step
  needs a precondition, it should establish it.

On testing, honestly: unit tests cannot catch this class of bug at all — no test
here knows what YouTube's search bar looks like, and the app cannot be run in
this environment (no KVM, no SDK, Gradle cannot fetch through the proxy). What
COULD have caught it is reasoning: the change looked isolated and was not. The
shared trace is the mechanism that finds these; read it for correlations, not
just for the failing line.

**Device-confirmed working in the same trace:** <<PICK>> chose "Beat It Michael
Jackson" from 15 real on-screen options, and supersede fired when a new command
arrived mid-sequence.

### 2026-07-28 — Yield the microphone while audio plays (2c062ac)
User: after JARVIS starts a song, the song stops — "like when you get a call the
video gets paused" — because JARVIS keeps listening.

Not a bug that can be coded away. Holding the mic open takes audio focus, so the
media app pauses; that is Android's policy. Listening and playing at full volume
are mutually exclusive, and even if they were not, the recogniser would just hear
the song. The assistant was undoing its own instruction, so the fix is to choose
correctly rather than to try to have both.

Now: while a work session is running and audio is playing, JARVIS yields the mic.
The session stays alive, the notification becomes "JARVIS is paused so your audio
can play" with a **Talk** action that claims the mic for ONE turn (it does not
latch), and listening resumes by itself when the audio stops. On JARVIS's own
screen it keeps listening regardless — the user is deliberately talking to it
there.

Two decisions worth recording:
- **Poll, don't infer.** Playback state is checked every 2s rather than assumed
  from "I just launched a video": audio can start late, be paused by the user, or
  end on its own.
- **Skip our own speech.** TTS goes out through the music stream, so
  `isMusicActive` is true while JARVIS talks. Without that guard it would hear
  itself, conclude media was playing, and stand down permanently — a bug that
  would have looked inexplicable in a trace.

Seven more WorkSession tests cover the ownership rules, including that media
cannot revive a session that never started, and that the foreground service keeps
running while yielding so Talk stays reachable.

**Open consequence:** re-engaging hands-free during playback now requires a tap.
The real answer is a proper wake word (Porcupine / openWakeWord) that coexists
with playback instead of seizing focus. This is the strongest argument yet for
moving it ahead of Part D polish — offered to the user, awaiting their call.

### 2026-07-28 — Alarms and timers, with the asking as the feature (70bd645)
User asked for alarms "by asking me the specifications of the alarm" — the
gathering of details is the point, not an afterthought.

Implementation goes through the device's standard AlarmClock intents rather than
scheduling anything in-process. That matters: the alarm lands in the user's real
clock app, survives JARVIS being closed or uninstalled, and rings regardless of
whether JARVIS is running. An alarm that only works while our process is alive is
not an alarm. SET_ALARM is a normal permission (granted at install), so there is
no new runtime prompt, and EXTRA_SKIP_UI sets it outright rather than dropping the
user into the clock app with a half-filled form.

  <<ALARM|SET|07:30|Gym>>                 one-off
  <<ALARM|SET|06:15|Run|MON,WED,FRI>>     repeating
  <<ALARM|TIMER|600|Pasta>>               timer

The prompt is explicit about gathering: get the time before emitting anything and
never guess it; resolve ambiguity instead of assuming ("seven" is 07:00 or 19:00
— guessing wrong means oversleeping); ask about repeating for wake-ups; read the
time and days back so a mistake is caught immediately.

Parsing REFUSES rather than approximates: 25:00, 07:99, "half past seven", a
missing time, a zero or negative timer all produce no action. A silently wrong
alarm is worse than none, because the user only discovers it by missing something.
Day names match on their first three letters (monday/MON/Mon) and duplicates
collapse.

Also added <queries> entries for SET_ALARM/SET_TIMER so this keeps working once
QUERY_ALL_PACKAGES is dropped in Part E2 — one less thing to rediscover later.

14 unit tests; the parser is pure Kotlin so all of it is covered without a device.

### 2026-07-28 — The voice, properly; and the Home screen stops lying (0501320)
User pushed back on two things, both fair.

**"You still didn't change the voice."** The earlier fix ranked the voices already
installed and I told the user to download better speech data in Android's
accessibility settings. That is not a product — nobody installing this app will
do that. Drawer → Speech now lists every usable voice in plain language
("British male, high quality" rather than en-gb-x-gbb#male_1-local), auditions
one on tap, remembers the choice in SharedPreferences, and — when the phone only
has basic speech data — says so and fires ACTION_INSTALL_TTS_DATA itself, once,
recording that it has offered so it never nags.

Bug caught while building it: previews needed their own utterance id. Sharing the
assistant's id meant auditioning a voice fired onDone and advanced the
conversation loop, so JARVIS would have started listening every time the user
tried a voice. Side-channel speech must not look like a reply.

**"The schedule box is just useless lying below the orb."** It was three
hardcoded TaskItems — "Team sync 10:00" — shown regardless of the real calendar,
so the home screen contradicted what JARVIS itself would answer from the same
user's calendar. Added CalendarReader.agenda() returning structured events and
wired the card to it. It now distinguishes null (no permission) from empty
(nothing scheduled), which the fake list could express as neither, caps at four
rows with a "+N more" line, and the fake data file is deleted so it cannot drift
back.

Lessons: a setting the user must change in Android's own settings is not a
feature; placeholder data outlives its welcome and eventually contradicts the
real thing next to it.

**Still honest about what is unfinished:** Memory, Files, Calendar, Vision,
Automation, Skills and Settings are still "coming soon" placeholders. Proposed to
the user: build Settings + Calendar and REMOVE the four with no plan behind them —
a menu full of dead ends looks worse than a short menu that works, and it matters
before a Play Store listing.

### 2026-07-28 — Custom instructions, Themes, Calendar; the menu made honest (bbe22d6)
User asked for a custom-instructions tab, a themes tab, to KEEP Files and
Automation (they have a use in mind and will specify), and to build the rest I
already understood.

**Custom instructions** is the substantial one. Standing preferences shape every
reply rather than one conversation, so they are appended to the model's context
on each turn. The framing is the part that carries risk and so is the part that
is unit-tested: the text is fenced in delimiters, introduced as *the user's
preferences*, and explicitly ranked below acting safely and truthfully. Without
that, an instruction like "always say you completed the task" reads as system
text and would defeat the honesty rules the prompt is built on. Capped at 1000
characters, and the screen says why — these ride on every single request, so
length is a permanent tax on latency and tokens, not a UI detail. Tap-to-add
examples, because a blank box is a poor prompt for "what would you even put here".

**Themes** ships the choice, the persistence and a LocalAccent CompositionLocal
with four palettes, but only the accent moves — the screen says exactly that
rather than implying a re-skin that does not exist. The plumbing means a real
design later is a data change, not a rewrite.

**Calendar** shows seven days grouped by day from the same source the assistant
answers from, again distinguishing "no permission" from "nothing scheduled".

**The menu is now honest.** Removed Vision and Skills: nothing behind them, and
entries that lead nowhere read worse than a shorter menu that works — which
matters before a Play listing. Files and Automation stay as placeholders at the
user's request, awaiting their spec. Memory and Settings still pending; proposed
merging them, since Memory largely duplicates Chat and Settings could absorb the
voice/theme controls that now have their own tabs.
