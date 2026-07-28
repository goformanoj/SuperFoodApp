# JARVIS OS — Session Handoff

> Everything a fresh session (or future me) needs to resume instantly. Update the "Current position" line as work ships.
> Companion: [`PRODUCT_PLAN.md`](PRODUCT_PLAN.md) · [`PROGRESS.md`](PROGRESS.md) · [`EXECUTION_PLAN.md`](EXECUTION_PLAN.md) · [`COMMERCIALIZATION.md`](COMMERCIALIZATION.md) · [`JARVIS_MEMORY.md`](JARVIS_MEMORY.md)

## Current position + immediate next
- **Shipped:** Part A, the testing rig, **Part B** (work session), and **Part C** — screen awareness, state memory, send guard, executor fixes, and now `<<PICK>>`, `<<BACK>>`/`<<HOME>>`, and a better TTS voice.
- **`main` @ `d27191e`** (green build **#117**) — everything is merged: PICK, Back/Home, the open-app fix, type recovery, the media yield, alarms, the in-app voice picker, the real calendar, custom instructions and themes.
- **Device-confirmed:** `<<PICK>>` works — it chose "Beat It Michael Jackson" from 15 real on-screen options, and supersede fired correctly on a mid-sequence interruption.
- **Next:** the user owes three answers before more UI work — what **Files** and **Automation** should do, what the **theme designs** look like, and whether **Memory/Settings** merge. Meanwhile: Part C tail (tap verification/retry, the album tap), then Part E.
- **Highest-value action:** the user is many builds behind on-device. One fresh install now carries a dozen fixes they reported and have not been able to retest.
- **Open bug:** tapping a YouTube album row did nothing while reporting success four times. Now reported honestly; cause still unknown (the outline overlay was ruled out — `FLAG_NOT_TOUCHABLE` is set).

## How the debugging loop actually works now
The user shares a trace from **Diagnostics → Share**; it shows heard → raw reply → markers parsed → per-step screen outcomes → spoken. Every fix in Part C came from one. **Read the trace before theorising** — it has repeatedly contradicted the obvious guess (e.g. the model's plan was fine and the executor was wrong).

## Definition of done (a change is NOT finished until all of this is true)
1. Committed on the **session branch** and pushed with `-u origin <branch>`.
2. The **`jarvis-debug-apk` artifact appears** for that commit (this is the green signal — the job-status API lags 2–5h).
3. **`main` fast-forwarded to that commit and pushed.** Never push straight to `main`; never leave a green commit unmerged.
4. [`PROGRESS.md`](PROGRESS.md) updated and [`JARVIS_MEMORY.md`](JARVIS_MEMORY.md) appended.

## Repo & branch
- The development branch is **per-session** (Claude is assigned one, e.g. `claude/root-file-context-ko322w`). The rule is constant: develop on the session branch, fast-forward `main` once the build is green.
- `main` position and the latest build are recorded in the "Current position" section above and in [`PROGRESS.md`](PROGRESS.md) — update them there, not here, so there is one source of truth.

## CI process
- `.github/workflows/build.yml` runs on **every push**: checkout → JDK 17 → Android SDK (platforms;android-36, build-tools;36.0.0) → `./gradlew assembleDebug` (keys injected from secrets) → upload artifact **`jarvis-debug-apk`**.
- **Confirm green by the artifact appearing.** The job-status API has been lagging **2–5 hours**; the artifact list is the reliable signal. `get_job_logs` with `failed_only` detects real compile failures (a compile error shows as a failed job even while the status lags).
- Build takes ~4 min; the *reported* status can take hours — don't mistake API lag for a hung/failed build.

## Secrets / keys
- `GROQ_API_KEY` (+ `GEMINI_API_KEY`) are **GitHub Actions secrets** → injected at build into `BuildConfig`. **Never committed.**
- **Exposure note:** the key is compiled into the APK; public repo → artifact downloadable → key extractable with a decompiler. Mitigation today: keep Groq on **free tier / no billing** + **rotate** if abused. Target architecture is a **backend proxy + BYOK** — full option matrix in [`COMMERCIALIZATION.md`](COMMERCIALIZATION.md#1-api-key-security--every-option).
- **Groq is egress-blocked (403) in the build environment**, so Claude cannot call the live LLM to test replies here.

## Commit identity
- `user.email = noreply@anthropic.com`, `user.name = Claude`. The model identifier never appears in commits or artifacts.

## How Claude tests (before handoff)
- **Unit tests in CI** — real JUnit tests for `ScreenActions`, `CalendarActions`, `DebugLog`. `./gradlew testDebugUnitTest` runs *before* `assembleDebug`, so a regression produces **no artifact**. (This replaced the old habit of porting the regexes to Python, which only tested the Python translation and was thrown away each time.)
- **Compile** — via CI (artifact = green).
- **On-device, without speaking** — the **Diagnostics** screen (drawer): self-checks, a **Test AI** round-trip button, a **typed command box** that drives the whole pipeline minus the microphone, and a **Share** button that exports the trace as text.
- **Reply quality, speech, TTS, real apps** — reviewed **on-device by the user**; Claude can't reach Groq from the build environment and there is no mic or emulator here (no KVM, no Android SDK, Gradle can't fetch through the proxy).
- **When something breaks:** ask for the shared trace rather than guessing. It shows heard → raw reply → markers parsed → actions run → spoken, with keys redacted.

## User install runbook
1. **Uninstall** any existing JARVIS.
2. Download the `jarvis-debug-apk` artifact and **extract the APK from the zip** (don't install from inside the zip).
3. Install → **Google Play Protect** will warn ("App blocked"): tap **More details → Install anyway**, or temporarily turn off Play Protect scanning.
4. Grant **Microphone** + **Calendar**.
5. For screen control, enable the accessibility service: **Settings → Accessibility & convenience → Accessibility → General tab → Downloaded apps → JARVIS Screen Control → On**.
6. Updates now install cleanly over the previous version (shared signing key) — no uninstall needed after the first time.

## Architecture map (`app/src/main/java/com/jarvis/os/`)
- **`MainActivity.kt`** — entry Activity; hosts Compose UI; drives `AssistantEngine` from the lifecycle; requests mic + calendar permissions.
- **`assistant/AssistantEngine.kt`** — core loop (listen → think → speak → listen); owns `VoiceUiState`; runs calendar + screen markers; defers screen actions until after TTS (speak-then-act).
- **`voice/`** — `VoiceController` (SpeechRecognizer wrapper, mutes earcon, recreates recognizer per turn), `Speaker` (TTS; ranks voices, remembers the user's pick, previews on a separate utterance id), `VoicePreference` (pure scoring + human labels), `VoiceSettings` (persisted choice), `VoiceState` (OrbState + VoiceUiState).
- **`ai/`** — `Brain` (picks Groq else Gemini; `generate` and `choose`), `GroqClient` (also `chooseIndex` for `<<PICK>>`, via a `systemOverride`), `GeminiClient`. **The system prompt is duplicated in both clients — patch them together or they drift.**
- **`calendar/`** — `CalendarActions` (parse `<<CAL|…>>`), `CalendarReader` (`upcomingEvents` for the AI, `agenda` for the UI), `CalendarWriter` (insert/delete).
- **`alarm/`** — `AlarmActions` (parse `<<ALARM|…>>`), `AlarmSetter` (device clock app via `AlarmClock` intents).
- **`control/`** — `ScreenControlService` (AccessibilityService: scored node search, outline, `runSteps` with a supersede token, `describeScreen` for the AI's context, text-entry recovery), `ScreenActions` (ordered `ScreenStep`s), `AppLauncher`, `WorkSessionService` (foreground service; notification with Talk/Stop — it never opens the mic).
- **`assistant/`** — `AssistantEngine` (the loop), `WorkSession` (single mic-owner rule), `SendGuard` (type ≠ send).
- **`debug/`** — `DebugLog` (redacted ring buffer), `Diagnostics` (self-checks + AI round-trip).
- **`ui/`** — `home/HomeScreen` (`JarvisApp` shell + drawer; schedule card reads the real calendar), `chat/ChatScreen`, `debug/DiagnosticsScreen`, `speech/SpeechScreen` (voice picker), `components/HudOrb`, `theme/*`.
- **`data/`** — `ChatTurn`, `ConversationStore` (SharedPreferences JSON). *(`Schedule.kt` held fake sample tasks and was deleted.)*

## Command-marker protocol (what the LLM emits, what the app parses)
The model puts these on their own lines; the app **strips them before speaking** and executes them.
| Marker | Meaning | Parsed by | Executed by |
|---|---|---|---|
| `<<CAL\|ADD\|Title\|YYYY-MM-DD\|HH:MM\|60>>` | add event | `CalendarActions` | `CalendarWriter` |
| `<<CAL\|DEL\|Title\|YYYY-MM-DD\|HH:MM>>` | delete event | `CalendarActions` | `CalendarWriter` |
| `<<OPEN\|AppName>>` | launch an app | `ScreenActions` | `AppLauncher` |
| `<<TAP\|Label>>` | tap a control (scrolls to find it) | `ScreenActions` | `ScreenControlService` |
| `<<TYPE\|text>>` | type into the focused field | `ScreenActions` | `ScreenControlService` |
| `<<ENTER>>` | submit / search | `ScreenActions` | `ScreenControlService` |
| `<<PICK\|description>>` | look at the screen and choose what matches | `ScreenActions` | `ScreenControlService` + `Brain.choose` |
| `<<BACK>>` / `<<HOME>>` | system back / home | `ScreenActions` | `performGlobalAction` |
| `<<ALARM\|SET\|HH:MM\|Label\|MON,WED>>` | set an alarm (days optional) | `AlarmActions` | `AlarmSetter` → clock app |
| `<<ALARM\|TIMER\|seconds\|Label>>` | start a timer | `AlarmActions` | `AlarmSetter` → clock app |
| `<<REMEMBER\|fact>>` | keep a durable fact about the user | `MemoryActions` | `UserPreferences` |
| `<<FORGET\|topic>>` | drop what was remembered | `MemoryActions` | `UserPreferences` |
| `<<BACK>>` / `<<HOME>>` | system back / home | `ScreenActions` | `performGlobalAction` |
Steps run **in order**, so one instruction can chain: `<<OPEN\|YouTube>> <<TAP\|Search>> <<TYPE\|standup comedy>> <<ENTER>> <<PICK\|the first video result>>`.
Use `<<TAP>>` for a control already visible; use `<<PICK>>` whenever the target will not exist until an earlier step runs — "the first result" is an intent, not a label.

## Hard-won gotchas
- **One mic owner at a time** — the earlier background-wake service fighting the in-app recognizer broke everything; keep a single owner.
- **Recreate `SpeechRecognizer` per turn** — reusing one instance after `cancel()` makes some devices return only partials (heard but never responds).
- **Scroll via a real swipe gesture** — don't trust a list to advertise its scroll actions; a vertical `dispatchGesture` swipe reliably scrolls and can't flip tabs.
- **Speak-then-switch** — run screen actions in `onSpokenDone`, after TTS, or the reply gets cut off.
- **Committed fixed debug keystore** — so updates install over each other (else "App not installed").
- **CI status lag** — trust the artifact, not the reported job status.
- **One sequence at a time** — a new command must supersede the one still running, or two chains interleave and undo each other (seen as aimless scrolling and stray taps). `runSteps` bumps a token and clears pending callbacks.
- **Never relaunch an app that is already in front** — it resets the app to its home screen and throws away the results the user is looking at.
- **Never report a tap as successful unless it was** — `seek` used to always call `onDone(true)`, so a dead tap was announced as done and repeating the command repeated the same non-event. A false success is far worse than a reported failure: it hides the bug from both the user and the model.
- **The prompt teaches by example** — every `TYPE` example ended in `<<ENTER>>`, so the model treated typing and sending as one move and sent messages the user only asked to type. Irreversible actions need a code-level guard, not just prompt wording.
- **Anything remembered silently must be visible and deletable** — learned facts persist without the user asking again, so the Custom instructions screen lists each one with a tap to forget. A store the user cannot inspect is one they cannot correct.
- **When a capped list is full, drop the OLDEST** — capping by discarding the newest would throw away the thing the user just said, which is the one they are most likely to be testing.
- **User-supplied text that reaches the prompt must be fenced and framed** — custom instructions are wrapped in delimiters, introduced as *the user's preferences*, and explicitly ranked below acting safely and truthfully. Without that framing an instruction like "always say you completed the task" reads as system text.
- **Anything appended to every turn is a permanent cost** — standing instructions ride on every single request, so the cap is a product decision, not a UI detail, and the screen says why.
- **A setting the user must change in Android's settings is not a feature** — ranking installed TTS voices only helps if good ones are installed. If the app needs something configured, it has to offer that itself; nobody installing an app goes hunting through accessibility settings.
- **Placeholder data outlives its welcome** — the Home schedule card showed three invented events for months, so the screen contradicted the assistant reading the same user's real calendar. Wire UI to the real source early, and make "no permission" and "nothing there" look different.
- **A preview must not look like a reply** — auditioning a TTS voice fired the same completion callback as a spoken answer, which would have started the listen loop on every tap. Give side-channel speech its own utterance id.
- **Prefer the platform's own intent over reimplementing it** — alarms go through `AlarmClock.ACTION_SET_ALARM`, so they live in the user's real clock app and ring even if JARVIS is closed or uninstalled. An alarm that only works while our process is alive is not an alarm.
- **Refuse rather than approximate on anything with a real-world consequence** — a silently wrong alarm (25:00, "half past seven") is worse than no alarm, because the user only finds out by missing something.
- **Listening and playing audio are mutually exclusive** — holding the mic takes audio focus, so the media app pauses exactly as it would for a phone call. No code fix avoids this; the only question is which one wins. While a session runs and audio plays, JARVIS yields and offers **Talk**. Hands-free re-engagement during playback needs a real wake word — that is the strongest argument for building one.
- **TTS comes out of the music stream** — `AudioManager.isMusicActive` is true while JARVIS is speaking, so any media check must skip its own speech or it hears itself and stands down permanently.
- **Removing a behaviour removes its side effects too** — not relaunching an app already in front was correct on its own terms, but relaunching had been resetting the app to its home screen, which is where the search button lives. Typing then failed *only* when the app was not relaunched. Before deleting a behaviour, ask what else was quietly leaning on it; the trace shows the correlation if you look for it instead of at the symptom.
- **Steps must not depend on an earlier step having guessed right** — `Type` assumed `<<TAP|Search>>` had opened a field, and that tap can "succeed" on the wrong node. A step that needs a precondition should establish it itself.
- **Telling the model what it CAN see implies what it cannot** — after screen awareness landed, "use the real on-screen labels" was over-generalised into "I can only act on what is visible", and it stopped opening apps entirely. State the powers that do not depend on the screen (`OPEN`, `BACK`, `HOME`) explicitly, every time.
- **"female" contains "male"** — a naive `contains("male")` voice check picks female voices about half the time. Exclude the negative first.
- **Don't describe JARVIS's own UI as "the screen"** — `rootInActiveWindow` returns JARVIS when it is in front; read the window behind it instead.
