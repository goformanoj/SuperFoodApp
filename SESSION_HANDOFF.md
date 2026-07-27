# JARVIS OS — Session Handoff

> Everything a fresh session (or future me) needs to resume instantly. Update the "Current position" line as work ships.
> Companion: [`PRODUCT_PLAN.md`](PRODUCT_PLAN.md) · [`PROGRESS.md`](PROGRESS.md) · [`EXECUTION_PLAN.md`](EXECUTION_PLAN.md) · [`JARVIS_MEMORY.md`](JARVIS_MEMORY.md)

## Current position + immediate next
- **Shipped:** Part A (multi-step commands + typing) and the conversational-first prompt — build **#76 green**, merged to `main` (`93fd65b`).
- **Next:** **paused before Part B** (continuous work session) awaiting the user's go. When told to continue, follow [`EXECUTION_PLAN.md`](EXECUTION_PLAN.md).

## Repo & branch
- Develop on **`claude/jarvis-minimal-build-4jwvo1`**; fast-forward `main` after a build goes green.
- `main` currently at **`93fd65b`**; last build **#76**.

## CI process
- `.github/workflows/build.yml` runs on **every push**: checkout → JDK 17 → Android SDK (platforms;android-36, build-tools;36.0.0) → `./gradlew assembleDebug` (keys injected from secrets) → upload artifact **`jarvis-debug-apk`**.
- **Confirm green by the artifact appearing.** The job-status API has been lagging **2–5 hours**; the artifact list is the reliable signal. `get_job_logs` with `failed_only` detects real compile failures (a compile error shows as a failed job even while the status lags).
- Build takes ~4 min; the *reported* status can take hours — don't mistake API lag for a hung/failed build.

## Secrets / keys
- `GROQ_API_KEY` (+ `GEMINI_API_KEY`) are **GitHub Actions secrets** → injected at build into `BuildConfig`. **Never committed.**
- **Exposure note:** the key is compiled into the APK; public repo → artifact downloadable → key extractable with a decompiler. Mitigation: keep Groq on **free tier / no billing** + **rotate** if abused. Full explanation in [`PRODUCT_PLAN.md`](PRODUCT_PLAN.md#api-key-security-reality-read-this).
- **Groq is egress-blocked (403) in the build environment**, so Claude cannot call the live LLM to test replies here.

## Commit identity
- `user.email = noreply@anthropic.com`, `user.name = Claude`. The model identifier never appears in commits or artifacts.

## How Claude tests (before handoff)
- **Deterministic logic locally** — e.g. port the `MARKER`/`CAL` regexes to Python and run cases (verified the multi-step parser this way).
- **Compile** — via CI (artifact = green).
- **Reply quality** — reviewed **on-device by the user** (Claude can't reach Groq from here).

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
