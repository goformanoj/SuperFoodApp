# JARVIS OS — Progress

> Living status. Update this whenever something ships.
> Snapshot: session branch `claude/root-file-context-ko322w` · `main` @ `c0ee9d3` · last green build **#95** · updated 2026-07-27

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

Still open in Part C:
- **Mid-sequence re-planning (`<<PICK>>`)** — the first command of a chain still plans blind, because the target app isn't open yet and there is nothing to read.
- **The Thriller-album tap** — reported false success for four attempts; now reports honestly, but the underlying cause is not yet identified.
- Tap verification + retry; disambiguation when matches tie.

## ⏸️ Queued (not started) — see [`EXECUTION_PLAN.md`](EXECUTION_PLAN.md)
- **Part C** — accuracy (feed AI the on-screen text; verify + retry taps; disambiguate) — with password/OTP redaction before anything leaves the device.
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
| Mid-sequence re-planning (`<<PICK>>`) | ⏸️ | — | first command of a chain still plans blind |
| Polish (toggle/onboarding) | ⏸️ | — | Part D |
| Key out of the APK (proxy + BYOK) | ⏸️ | — | Part E1 |
| Play compliance + release AAB | ⏸️ | — | Part E2–E3 |
| Subscription billing | ⏸️ | — | Part E5 |
