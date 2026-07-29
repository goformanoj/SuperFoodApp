# JARVIS OS — Session Handoff

> Everything a fresh session (or future me) needs to resume instantly. Update the "Current position" line as work ships.
> Companion: [`PRODUCT_PLAN.md`](PRODUCT_PLAN.md) · [`PROGRESS.md`](PROGRESS.md) · [`EXECUTION_PLAN.md`](EXECUTION_PLAN.md) · [`COMMERCIALIZATION.md`](COMMERCIALIZATION.md) · [`JARVIS_MEMORY.md`](JARVIS_MEMORY.md)

## Current position + immediate next
- **Shipped:** Part A, the testing rig, **Part B** (work session), **Part C** (screen awareness, state memory, send guard, `<<PICK>>`, `<<BACK>>`/`<<HOME>>`, step recovery), the navigation restructure, learned memory, custom instructions, alarms, the voice picker, **Part F — Files**, and **the six themes** (`dd7bf2c`) — six orb geometries, each animated, with theme-driven backgrounds, a backdrop and a mic-driven waveform.
- **`main` @ `31f3b05`** (green build **#161**, artifact confirmed) — everything is merged: learned memory, the navigation restructure, the rate-limit and retired-model fixes, Files, step recovery, the narration fix, the shared/slimmed system prompt, and the red-build fix.
- **Device-confirmed (2026-07-29 session):** type vs send, no spoken thought process, chats below the fold, the mic yielding while audio plays, `<<REMEMBER>>`, PDF creation, and `<<PICK>>` choosing "Beat It Michael Jackson" from 15 real options.
- **Awaiting CI:** `992bcd9` — `AlarmGuard`, `AskGuard` and the alarm-volume check from that session's traces, plus the compile fix. **Builds #163–#165 were red** (bare `$` in a Kotlin string); `main` never left green at `31f3b05`.
- **Next, from the same traces:** a marker for "open the PDF you just made" (the prompt now prevents damage but the capability is missing), and `<<OPEN|unknown>>` reporting failure instead of silence. Bigger: the user's "look how many tries it took me" — recovery fires but plans badly, once emitting `Open(app=Open)`.
- **Next:** flow charts for Files (specced, no new provider needed), then **Part G — Automation** (specced, security shape fixed, not started). Still owed by the user: a decision on a paid provider for image generation. *(Theme designs are no longer owed — the user supplied six and all six are built.)*
- **Themes are merged and green** (`31f3b05`) — filled luminous bands, per-theme backdrops, ring colour sampled from the references (`ThemeArt`). **The open risk is framerate, not looks:** Reactor and Forge draw 5–6 rings x 24 filled quads. If it stutters, cut `CHUNKS` in `Orb3DRenderer` before redesigning anything — one line.
- **The theme brief is now bounded.** Five rewrites established that 100% resemblance to a photorealistic render, without shipping that render, is not achievable: it needs the source 3D scene, which a 2D image does not contain. Do not attempt a sixth hand-drawn pass. The one untried option is texture-mapping the renders onto the 3D rings via `drawVertices` — genuine 3D motion with the source's own pixels, at the cost of shipping the images as textures. Offered to the user, awaiting their call.
- **Unfixable from here, worth adding:** the app has no build stamp (`versionCode 1`, `versionName "1.0"`, shown nowhere), so neither the user nor Claude can tell which build a device is running. Every shared trace is unidentified. Deriving the version from the CI run number and showing it in Diagnostics was offered and not yet built.
- **Highest-value action:** the user is well behind on-device — **11 commits changed app code since build #117**, the last point `main` was green before this session. One fresh install carries every fix they reported and could not retest.
- **The system-prompt diet is done** (`4ad64b8`) — ~2,299 → ~1,355 tokens, one shared `SystemPrompt.kt` instead of a copy in each client, and the literal `\n` bug both copies carried is gone. Next cheap win on the same axis: **step recovery re-sends the whole prompt** when it only needs the SCREEN section — a `systemOverride` there would roughly halve a recovery's cost.
- **Open bug:** tapping a YouTube album row did nothing while reporting success four times. Now reported honestly, and step recovery should now replan around it; cause still unknown (the outline overlay was ruled out — `FLAG_NOT_TOUCHABLE` is set).

## Rules that keep being re-learned
- **When the codebase already solves a problem, copy that solution.** `AlarmGuard` was written with a fresh `Regex("(^|\\W)…(\\W|$)")` for word matching; the trailing `$)` is a bare dollar in an escaped Kotlin string and broke the build for three commits. `SendGuard` does the identical job with plain `startsWith`/`endsWith`/`contains` and has always compiled. A Python pre-flight cannot catch this class at all — it tests the algorithm, and the fault was in Kotlin's lexer.
- **Guard irreversible or real-world actions in code, never in the prompt.** Three times now: sending a message (`SendGuard`), setting an alarm (`AlarmGuard`), and acting while asking (`AskGuard`). Each was "taught" in the prompt first and each still happened on a device, because the prompt is probabilistic and the cost of the miss is borne by the user hours later.
- **Four attempts at the orb, and the lesson is the same each time: fix the APPROACH, not the output.** Flat vectors could not reach a photorealistic render. Sprites were an exact likeness that could not move. Sliced sprite bands sheared, because a flat image has no depth to rotate through. Only real 3D geometry — tilted circles, perspective, depth shading, additive light — actually *is* rings. Each dead end was recognisable in advance by asking "can this medium express the thing being asked for?"
- **Pre-flight the arithmetic, not just the logic.** Kotlin's `%` truncates toward zero and returns a NEGATIVE remainder for a negative input. Four rings spin backwards; their arc phase went negative and would have lit those rings solidly. A Python pre-flight of the exact expression caught it before CI, let alone before the device.
- **Look at the output before shipping it.** Mirroring to repair the artwork seemed obviously right and produced a lens-shaped artifact in all six themes; one contact sheet caught it. Every image step in this work was verified by rendering it and looking, not by reasoning about it.
- **When a look cannot be reached, change the approach, not the effort.** Two passes hand-drew the orbs from vector shapes against photorealistic references. More shapes was never going to close that gap; shipping the render did, and deleted 785 lines doing it.
- **"In progress" is not evidence of progress.** The job-status API lags 2–5 hours; a status that has not moved means *unknown*, not *running*. **Check `list_workflow_run_artifacts`** — on 2026-07-29 four commits were built on top of a red branch and reported to the user as "still building", when one artifact check would have shown the failures immediately.
- **A behaviour and its tests change together.** Reverting the model routing left two tests asserting the old FAST routing, which gated `assembleDebug` and produced no APK for four commits. If a test still passes after a deliberate behaviour change, it was testing the wrong thing.
- **A tidier version of the wrong output is still the wrong output.** "Here are the steps: ." was fixed once by repairing the punctuation, and the user saw it again. When something should not be spoken, delete it — don't clean it up.
- **Ask "is this thing unusable?", not "is this status code in my list?"** The same fallback bug was fixed three times (404, then 429, then 400) before being fixed by condition rather than by code.
- **Never state a number you have not measured.** "~20 builds behind" was repeated from a vague docs note and asserted to the user as fact; the real figure was 11 code commits. If a number is an estimate, say so, or go and count.
- **A preview must carry the information the choice depends on.** Three of the six themes differ mainly in how they *move*; a static colour swatch would have shown six near-identical circles. The picker runs the real renderer for that reason.
- **Check the medium can reach the target before starting.** Two passes went into hand-drawing photorealistic renders with vector shapes. The second was much better than the first and still did not resemble the references, because it could not: volumetric glass and thousands of particles are not something a Canvas converges on. When the goal is "exact replica", ship the original — any pipeline that regenerates it (vectors, 3D, anything) is an approximation by definition.
- **When the brief is a set of images, ask "what is in the image that is not in mine?"** — useful, but only *after* confirming the medium can get there at all.
- **Separate renderers made the redraw cheap.** Six independent styles plus a shared `OrbPrimitives` vocabulary meant re-cutting them against the art was an afternoon; one parameterised orb would have been a rewrite.

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
| `<<REMEMBER\|fact>>` / `<<FORGET\|topic>>` | keep or drop a durable fact about the user | `MemoryActions` | `UserPreferences` |
| `<<FILE\|pdf\|Title>>` … `<<ENDFILE>>` | make a PDF or note (**block** marker, multi-line) | `ArtifactActions` | `ArtifactWriter` → Files tab |
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
- **Prefer a design that adds no permission** — Files stores artifacts in app-private `filesDir` and shares them via a scoped `FileProvider`, so a whole feature landed with nothing new to justify at Play review and nothing added to the Data safety form. Reach for the permission-free shape first; it is usually available.
- **A block of content needs a block marker** — every command marker stops at a newline by construction, so a multi-line document could not travel in one. `<<FILE|kind|title>> … <<ENDFILE>>` exists for that, and tolerates a missing end marker because the model drops closing markers often enough that losing a whole document over one would be the wrong trade.
- **Ask "is this thing unusable?", not "is this status code in my list?"** — a per-model fallback was built so one bad model could not take the assistant down, then a status code I had not anticipated (400 for a retired model, not 404) walked straight past it and killed the request while a working model sat next in line. Fixing this class one status code at a time does not converge; match on what the provider *says*.
- **Model IDs rot** — providers retire models with little warning, and a hardcoded list silently becomes wrong. Treat an unusable model as routine, not exceptional.
- **Provider quotas are usually per model, not per account** — a 429 naming one model says nothing about the others. Falling through to the next model turns a dead assistant into a slightly less capable one.
- **Route work to the cheapest model that can do it** — "open YouTube" does not need a 70b model. But guard the downgrade: if the small model produces no action where one was expected, retry once on the big one, and record which tier answered so a quality regression is visible rather than guessed at.
- **Measure what rides on every request** — the system prompt grew from ~1,100 to 2,001 tokens across one session, each addition individually justified, and nothing ever checked the total. At ~3,200 tokens per request against a 12,000 tokens-per-minute cap that left only three or four commands a minute. Before adding to the prompt, price the whole thing.
- **A health check must be cheap** — `Test AI` went through the full assistant prompt to hear "OK", so the diagnostic cost as much as a real turn and pressing it while rate limited made the problem worse. Diagnostics should never consume the resource they are diagnosing.
- **Never replace a provider's error with your own summary** — Groq's 429 states which limit was hit and when it clears; the code substituted "wait a moment", so a hard daily quota was indistinguishable from a 2-second burst limit and retrying looked sensible when it could not work. Surface the upstream message; add context, do not discard it.
- **A failing call must get harder to repeat, not easier** — nothing blocked retries during a rate limit, so 25 more rejected requests went against the same exhausted quota. Record when the limit clears and refuse locally until then.
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
