# JARVIS OS — Progress

> Living status. Update this whenever something ships.
> Snapshot: session branch `claude/root-file-context-ko322w` · `main` @ `6066abd` · last green build **#108** · updated 2026-07-28
> Awaiting CI: `70bd645` (alarms + timers). Everything earlier is merged.

## Status legend
✅ done & (usually) confirmed · 🔬 shipped, awaiting on-device confirmation · ⏸️ queued, not started

## ✅ Done
- **Buildable Compose app + CI** — `assembleDebug` on every push → `jarvis-debug-apk` artifact. Public repo for free CI.
- **UI** — HUD orb (state-driven), dark Material 3 theme, Orbitron/Inter fonts, vector adaptive launcher icon, nav drawer, terminal-style Chat screen.
- **Voice loop** — always-on listen → think → speak → listen (rebuilt simple & reliable; **user-confirmed working**). Muted earcon; fresh recognizer per turn.
- **Brain** — Groq primary (llama-3.3-70b → fallbacks), Gemini fallback; **conversational-first** prompt.
- **Memory** — conversation context persisted in SharedPreferences JSON.
- **Calendar** — read real device calendar; add / delete / reschedule with confirmation.
- **Screen control** — open apps ✅; tap with cyan outline ✅; scroll via **real swipe gesture**; type + enter; **multi-step sequences** (open→tap→type→enter).
- **Speak-then-switch** — finishes the reply before switching apps (no cut-off).
- **Natural replies** — removed the robotic "is there anything else?" tic; bare "Jarvis" → "Yes?".
- **Fixed debug signing key** — committed keystore so updates install cleanly (fixed "App not installed").
- **Resume-after-Settings** — returning from the accessibility settings screen resumes listening.

## 🔬 Shipped, awaiting on-device confirmation
- Multi-step commands + typing (build #76) — e.g. "show me a standup comedy video".
- Conversational-first prompt (build #76) — JARVIS talks like a real assistant, tools when you want an action.
- Swipe-scroll for the buried-chat case (WhatsApp "open the Mom chat below the fold").

## 🧪 Testing (new — build #85)
- **Unit tests in CI** ✅ — `ScreenActions`, `CalendarActions` and `DebugLog` are covered by real JUnit tests. `testDebugUnitTest` runs **before** `assembleDebug`, so a logic regression means **no artifact**. "Artifact present" now means compiles **and** the logic is right.
- **Diagnostics screen** 🔬 — self-checks (mic, calendar, speech recognition, accessibility service, AI key) plus a **Test AI** button that does a real round-trip and reports provider + latency.
- **Shareable trace** 🔬 — every turn records heard → raw reply → markers parsed → actions run → spoken. One **Share** button exports it as text. API keys are redacted before anything can leave.
- **Typed command box** 🔬 — runs the full pipeline without the microphone, so brain/marker/tap failures can be reproduced by typing.

## ✅ Part B — continuous work session (build #89, merged)
Background-listens for follow-ups after a command opens an app; stops on "thank you Jarvis"; never listens if you only open/close JARVIS. One mic owner **by construction** — `WorkSession.owner` is a single computed value, and the foreground service never opens the mic (the engine keeps the only `VoiceController`). Play-shaped from day one: `foregroundServiceType=microphone` + persistent notification with Stop.

## 🔨 Part C — accuracy (in progress; driven by real device traces)
Shipped so far:
- **Screen awareness** (#93) — `describeScreen()` renders the live accessibility tree into the model's context (clickables in `[brackets]`, fields as `field:"…"`), so it names labels that exist instead of inventing them. Passwords and OTP-shaped digits redacted; scan bounded to 400 nodes / 45 items.
- **State memory** (#94) — when JARVIS's own UI is in front it reads the app *behind* it, and otherwise reports what the user was last in and how long ago. Previously it described its own UI and concluded it was inside JARVIS.
- **Send guard** (#95) — "type it" and "send it" are different instructions; a trailing submit/Send is dropped when the user only asked to compose. Sending is irreversible, so this is enforced in code, not only in the prompt.
- **Marker robustness** (#91) — a single `>` no longer breaks parsing, and unparseable marker text can never reach the spoken reply.
- **Executor fixes** (#91, #96) — wait for the screen to actually change after Enter; demote editable fields so the search box stops winning; don't relaunch an app that is already in front; sequences supersede each other instead of interleaving; taps report real success instead of always claiming it.

- **`<<PICK>>` mid-sequence choice** (`b922b65`) — the last blind spot. The first command of a chain planned against a screen that did not exist yet, so the model had to invent a label. `<<PICK|the first video result>>` defers the decision: at that step the executor lists what is genuinely tappable and asks the model which one, via a separate tiny call carrying none of the assistant prompt. The choice is re-found by label at tap time (node handles go stale over a ~1s round trip) and discarded if a newer command has started.
- **Open-app regression + Back/Home** (`d801260`) — screen awareness had made the model conclude it could *only* act on what was visible, so it began refusing to open apps ("I can only interact with the current app"). Opening never needed the screen. Also added `<<BACK>>`/`<<HOME>>` via `performGlobalAction`, replacing the previous hunt for a control labelled "Back".
- **Typing opens its own field** (`48d7847`) — `Type` failed if and only if the app was *not* relaunched. Not relaunching was right (relaunching threw away the user's screen) but it removed a side effect the plan leaned on: relaunching reset YouTube to its home screen, where "Search" is a real button. `Type` no longer assumes an earlier step opened a field — it taps candidates in turn until one appears, inside the existing poll budget so it still fails honestly.
- **Yields the mic to playback** (`2c062ac`) — holding the mic takes audio focus, so listening paused the very song JARVIS had just been asked to play. It now steps back while audio plays (notification offers **Talk** for one turn) and resumes on its own when the audio stops. On JARVIS's own screen it keeps listening, since the user is deliberately talking to it.
- **Alarms and timers** (`70bd645`) — via the device's own `AlarmClock` intents, so the alarm lives in the real clock app and rings whether or not JARVIS is running. JARVIS asks for the specifics first (time, morning/evening when ambiguous, whether it repeats) and reads them back. The parser refuses anything that would set the *wrong* alarm rather than approximating it.
- **A proper voice** (`aa74c4d`) — ranks every installed TTS voice instead of taking the bland default (English only, en-GB > en-US, male, higher quality, local over network) and lowers pitch to 0.92 / rate to 0.98.

Still open in Part C:
- **The Thriller-album tap** — reported false success for four attempts; now reports honestly, but the underlying cause is not yet identified.
- Tap verification + retry; disambiguation when matches tie.

## ⏸️ Queued (not started) — see [`EXECUTION_PLAN.md`](EXECUTION_PLAN.md)
- **Part D** — polish (tap-to-talk toggle; clear idle text; permission onboarding).
- **Part E** — commercialization: key security (proxy + BYOK), Play compliance cleanups, release AAB pipeline, name/`applicationId` gate, billing, launch. See [`COMMERCIALIZATION.md`](COMMERCIALIZATION.md).
- Later — proper wake word (Porcupine/openWakeWord); streaming replies; device skills (alarms/timers, SMS/calls, toggles, media); vision.

## Known limitations / caveats
- **No reliable always-on wake word** — stock `SpeechRecognizer` can't do it; needs a dedicated hotword engine.
- **Screen control is best-effort** — it reads the accessibility tree, which varies per app; occasional misses are expected (no real API for other apps).
- **Claude can't test live LLM replies** — Groq is egress-blocked (403) in the build environment, and the key is a build secret. Reply quality is verified on-device by the user; Claude verifies everything mechanical.
- **CI status API lags 2–5 hours** right now — the **presence of the `jarvis-debug-apk` artifact is the reliable "green" signal** (not the job status).

## Feature status table
| Feature | Status | Build | Notes |
|---|---|---|---|
| Buildable app + CI | ✅ | — | artifact `jarvis-debug-apk` |
| HUD orb UI / theme / fonts / icon | ✅ | — | |
| Always-on voice loop | ✅ | rebuilt | user-confirmed |
| Groq brain + memory | ✅ | — | Gemini fallback |
| Calendar read/add/del/reschedule | ✅ | — | real device calendar |
| Open app | ✅ | — | confirmed |
| Tap control + outline | ✅ | — | confirmed (YouTube) |
| Scroll to find (swipe) | 🔬 | #73 | test the Mom-chat case |
| Type + Enter / search | 🔬 | #76 | |
| Multi-step chained commands | 🔬 | #76 | "standup comedy video" |
| Conversational-first replies | 🔬 | #76 | |
| Fixed signing (clean updates) | ✅ | #52 | |
| Unit tests gating CI | ✅ | #85 | parsers, DebugLog, WorkSession, SendGuard |
| Diagnostics + shareable trace | ✅ | #85 | drawer → Diagnostics; traces now drive the fixes |
| Typed command box (no mic) | 🔬 | #85 | full pipeline test |
| Continuous work session | ✅ | #89 | one mic owner by construction |
| Screen awareness (AI reads the screen) | 🔬 | #93 | needs on-device confirmation |
| State memory (app behind JARVIS) | 🔬 | #94 | |
| Type vs send kept separate | 🔬 | #95 | guard + prompt |
| Sequences supersede, honest taps | 🔬 | #96 | |
| Mid-sequence choice (`<<PICK>>`) | ✅ | pending | device-confirmed: chose "Beat It Michael Jackson" from 15 real options |
| Back / Home via global actions | 🔬 | pending | replaces hunting for a "Back" label |
| Open-app refusal regression | 🔬 | pending | it had stopped opening apps entirely |
| Better TTS voice | 🔬 | pending | ranks installed voices; premium voice is a Part E perk |
| Typing opens its own text field | 🔬 | pending | no longer depends on <<TAP\|Search>> having worked |
| Yields the mic while audio plays | 🔬 | pending | song no longer stops; notification offers Talk |
| Alarms and timers | 🔬 | pending | device clock app; asks for specifics first |
| Polish (toggle/onboarding) | ⏸️ | — | Part D |
| Key out of the APK (proxy + BYOK) | ⏸️ | — | Part E1 |
| Play compliance + release AAB | ⏸️ | — | Part E2–E3 |
| Subscription billing | ⏸️ | — | Part E5 |
