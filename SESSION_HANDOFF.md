# JARVIS OS — Session Handoff

> Everything a fresh session (or future me) needs to resume instantly. Update the "Current position" line as work ships.
> Companion: [`PRODUCT_PLAN.md`](PRODUCT_PLAN.md) · [`PROGRESS.md`](PROGRESS.md) · [`EXECUTION_PLAN.md`](EXECUTION_PLAN.md) · [`COMMERCIALIZATION.md`](COMMERCIALIZATION.md) · [`JARVIS_MEMORY.md`](JARVIS_MEMORY.md)

## Current position + immediate next
- **Shipped:** Part A (multi-step commands + typing) and the conversational-first prompt — build **#76 green**, merged to `main`. Then the project docs, and now [`COMMERCIALIZATION.md`](COMMERCIALIZATION.md) (key-security matrix + Play Store path).
- **Next:** **Part B — continuous work session** (user gave the go; features before the commercial foundation). Follow [`EXECUTION_PLAN.md`](EXECUTION_PLAN.md).

## Definition of done (a change is NOT finished until all of this is true)
1. Committed on the **session branch** and pushed with `-u origin <branch>`.
2. The **`jarvis-debug-apk` artifact appears** for that commit (this is the green signal — the job-status API lags 2–5h).
3. **`main` fast-forwarded to that commit and pushed.** Never push straight to `main`; never leave a green commit unmerged.
4. [`PROGRESS.md`](PROGRESS.md) updated and [`JARVIS_MEMORY.md`](JARVIS_MEMORY.md) appended.

## Repo & branch
- The development branch is **per-session** (Claude is assigned one, e.g. `claude/root-file-context-ko322w`). The rule is constant: develop on the session branch, fast-forward `main` once the build is green.
- `main` currently at **`4029979`**; last build **#76**.

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
- **`voice/`** — `VoiceController` (SpeechRecognizer wrapper, mutes earcon, recreates recognizer per turn), `Speaker` (TTS), `VoiceState` (OrbState + VoiceUiState).
- **`ai/`** — `Brain` (picks Groq else Gemini; holds system prompt), `GroqClient`, `GeminiClient`.
- **`calendar/`** — `CalendarActions` (parse `<<CAL|…>>`), `CalendarReader` (query Instances), `CalendarWriter` (insert/delete).
- **`control/`** — `ScreenControlService` (AccessibilityService: scored node search, outline, `runSteps` for Open/Tap/Type/Enter, swipe-scroll), `ScreenActions` (parse ordered `ScreenStep`s), `AppLauncher` (name → package → launch).
- **`ui/`** — `home/HomeScreen` (`JarvisApp` shell + drawer), `chat/ChatScreen`, `components/HudOrb`, `theme/*`.
- **`data/`** — `ChatTurn`, `ConversationStore` (SharedPreferences JSON), `Schedule` (sample tasks for the Home widget).

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
Steps run **in order**, so one instruction can chain: `<<OPEN\|YouTube>> <<TAP\|Search>> <<TYPE\|standup comedy>> <<ENTER>>`.

## Hard-won gotchas
- **One mic owner at a time** — the earlier background-wake service fighting the in-app recognizer broke everything; keep a single owner.
- **Recreate `SpeechRecognizer` per turn** — reusing one instance after `cancel()` makes some devices return only partials (heard but never responds).
- **Scroll via a real swipe gesture** — don't trust a list to advertise its scroll actions; a vertical `dispatchGesture` swipe reliably scrolls and can't flip tabs.
- **Speak-then-switch** — run screen actions in `onSpokenDone`, after TTS, or the reply gets cut off.
- **Committed fixed debug keystore** — so updates install over each other (else "App not installed").
- **CI status lag** — trust the artifact, not the reported job status.
