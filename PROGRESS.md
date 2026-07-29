# JARVIS OS — Progress

> Living status. Update this whenever something ships.
> Snapshot: session branch `claude/root-file-context-ko322w` · `main` @ `6f5043d` · last green build **#139** (artifact `jarvis-debug-apk`, 18.6 MB, confirmed) · updated 2026-07-29
> Everything is merged. Builds #134–#138 failed on a stale `ModelRouterTest`; #139 is green, which also confirms the narration tests and `SystemPromptTest` pass — `testDebugUnitTest` gates `assembleDebug`, so an artifact means the logic is right. **The user is ~20 builds behind on-device; a fresh install is by far the highest-value next step.**

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
- **Learned memory** (`e2506cb`) — `<<REMEMBER|fact>>` / `<<FORGET|topic>>` let JARVIS keep durable facts it is told once ("when I say Amazon Music I mean chow", what to call the user) and follow them thereafter. Learned facts are stored apart from typed instructions so the screen can show and delete exactly what was picked up automatically. Duplicates ignored case-insensitively; at the cap the *oldest* goes, since dropping the newest would discard what the user just said. Never stores passwords, codes or card numbers.
- **Navigation restructure** (`e2506cb`) — Settings contains Voice + Appearance as sections; Chat absorbs Memory. Drawer down from eleven entries to six, all of which do something.
- **Custom instructions** (`bbe22d6`) — standing preferences appended to the model's context every turn ("call me sir", "assume IST"), fenced and framed as the user's preferences and explicitly subordinate to acting safely and truthfully. Capped at 1000 chars because they ride on *every* request.
- **Themes** (`bbe22d6`) — four palettes with the choice, persistence and `LocalAccent` plumbing real; only the accent moves until designs are decided, and the screen says so.
- **Calendar screen** (`bbe22d6`) — seven days grouped by day from the device calendar.
- **Voice picker inside the app** (`0501320`) — Drawer → Speech lists every usable voice in plain language ("British male, high quality"), auditions on tap, remembers the choice, and offers the speech-data download when the phone only has basic voices. Ranking alone was not enough: it only helps if good voices happen to be installed, and telling users to go into Android settings is not a product.
- **Home shows the real calendar** (`0501320`) — the schedule card was three hardcoded fake events that contradicted what JARVIS itself would say. It now reads the same device calendar, and tells "no permission" apart from "nothing scheduled". Fake data source deleted.
- **Alarms and timers** (`70bd645`) — via the device's own `AlarmClock` intents, so the alarm lives in the real clock app and rings whether or not JARVIS is running. JARVIS asks for the specifics first (time, morning/evening when ambiguous, whether it repeats) and reads them back. The parser refuses anything that would set the *wrong* alarm rather than approximating it.
- **A proper voice** (`aa74c4d`) — ranks every installed TTS voice instead of taking the bland default (English only, en-GB > en-US, male, higher quality, local over network) and lowers pitch to 0.92 / rate to 0.98.

## 🔨 Part F — Files (build pending CI)
**Shipped** (`f15cf54`): "make a PDF of the important points" produces a real file in the **Files** tab — open, share or delete. PDFs render with Android's own `PdfDocument` (headings, bullets, word wrap, page breaks); notes are markdown.

Two choices driven by Part E rather than by this feature:
- **No new permissions.** Artifacts live in app-private `filesDir` and are shared through a `FileProvider` scoped to that one folder — nothing for a Play reviewer to justify, nothing new on the Data safety form, and files never leave the device unless shared.
- **Image generation is refused, not faked.** Groq has no image model, so the prompt says so plainly and offers a written alternative.

Still open in Part F:
- **Flow charts / diagrams** — specced (model describes nodes and edges, app draws to a `Canvas`), not built. No new provider needed.
- **Image generation** — blocked on choosing a paid provider.

## 🔨 Part C tail — self-correction and a quieter reply (build pending CI)
- **Recovers from a failed step** (`53fc4e0`) — when a step fails, the executor no longer gives up on the whole sequence. It hands the model the reason it failed *and* a fresh reading of the live screen, and runs the replacement it comes back with (max two recoveries per sequence, so a wrong plan cannot loop). This is what "sense that something is not playing and figure it out" needed: the plan is rewritten from what is actually on screen, not retried blindly.
- **Model routing reverted** (`53fc4e0`) — routing commands to the small model was tried and measured over three device traces: it returned **no markers on every single command**, so each fell through to the smart model anyway. That doubles requests rather than halving them. `tierFor` now always returns SMART, with the evidence recorded at the call site. The fast tier stays where it demonstrably works — the `<<PICK>>` chooser and the Diagnostics ping, both ten-token prompts answering with one value.
- **Red build fixed** (`6f5043d`) — **CI had been failing since `53fc4e0`, four commits back, and it was not noticed.** Two `ModelRouterTest` cases still asserted commands route to `Tier.FAST` after that commit deliberately stopped doing it: 149 tests, 2 failed, so `testDebugUnitTest` gated `assembleDebug` and **no APK was produced for four commits**. The code was correct; the behaviour changed and its tests did not. Also deleted what the revert orphaned — the command-verb list, conversation cues, word-count bound and `expectsAction` were all unreachable once `tierFor` became a constant, and leftover routing heuristics imply the app still routes.
- **One system prompt, 41% smaller** (`4ad64b8`) — it was pasted into `GroqClient` **and** `GeminiClient`, two byte-identical copies free to drift, and both carried the same bug: a literal `\n` inside a Kotlin **raw** string, where `\n` is not an escape. The model had been reading the characters backslash-n since Files was added. Now one `SystemPrompt.kt` in the package. Also the diet owed since the rate-limit diagnosis: **9,196 → 5,421 chars (~2,299 → ~1,355 tokens)**, charged on *every* request — at the old size it was over half of Groq's 12,000 tokens-per-minute allowance by itself. What went is the prose explaining *why* each rule exists (moved to a KDoc, where it costs nothing per request), three restatements of the same "only claim what you emitted" rule, and a stray calendar sentence that had drifted into the alarm section. **Four tests** pin it: no literal `\n`, a length ceiling, every parseable marker is taught, and the eight rules earned from device failures are still present by phrase.
- **JARVIS no longer narrates its thought process** (`774b5cf`) — it was speaking "Here are the steps: ." aloud. The first attempt only tidied the punctuation, which is why the user saw it again. A clause ending in a colon is, in practice, always the model announcing the markers it is about to emit; since the markers are stripped, that narration describes nothing, so it is now **removed entirely** — and only when the reply actually carried markers, so "There are two options: tea or coffee." keeps its punctuation.

Still open in the UI (Part D):
- **Files** and **Automation** — the only remaining placeholders; **awaiting the user's spec**.
- **Themes needs designs** — the switch and persistence work, the looks are still just accent colours.
- Resolved: Settings now contains Voice + Appearance, Chat absorbed Memory, and Vision/Skills were removed for having nothing behind them.

Still open in Part C:
- **The Thriller-album tap** — reported false success for four attempts; now reports honestly, but the underlying cause is not yet identified.
- Tap verification + retry; disambiguation when matches tie.

## ⏸️ Queued (not started) — see [`EXECUTION_PLAN.md`](EXECUTION_PLAN.md)
- **Part D** — polish (tap-to-talk toggle; clear idle text; permission onboarding).
- **Part E** — commercialization: key security (proxy + BYOK), Play compliance cleanups, release AAB pipeline, name/`applicationId` gate, billing, launch. See [`COMMERCIALIZATION.md`](COMMERCIALIZATION.md).
- Later — proper wake word (Porcupine/openWakeWord); streaming replies; device skills (alarms/timers, SMS/calls, toggles, media); vision.

## ⚠️ Groq rate limits — it is TOKENS per minute (measured 2026-07-28)
The Groq dashboard settled it: requests peaked at **19 against a limit of 30**, but total tokens peaked at **~11.5K against ~12K**. The cap being hit is **tokens per minute**, so the fix is smaller requests, not fewer.

Measured cost per request before the fix:

| Part | Tokens |
|---|---|
| System prompt | ~2,000 |
| Screen description | ~300 |
| Date + calendar | ~120 |
| 20 turns of history | ~800 |
| **Total, every request** | **~3,200** |

At 12,000 TPM that is only **3–4 commands a minute**. The system prompt grew from ~1,100 to 2,001 tokens across one session, a paragraph at a time, and nothing ever measured the total.

Fixed in `2309d22`: `Test AI` no longer sends the whole assistant prompt to hear "OK" (~2,000 → ~20 tokens), and history is halved (20 → 10 turns, ~400 tokens off every request).

Fixed in `c04c7de`: **Groq's quotas are per MODEL.** A device screenshot showed `llama-3.3-70b-versatile` out of daily tokens (`Limit 100000, Used 98444, Requested 2674`) while the smaller models still had their own untouched allowance — and the client gave up anyway, because 429 did not fall through to the next model the way 404 did. Now it does, cooldowns are tracked per model, and only an all-models-limited state fails.

Fixed in `f73b3e3`: **commands are routed to `llama-3.1-8b-instant`**, keeping the 70b allowance for turns that need thinking. Conservative — an explicit request to think wins over a command verb, long utterances are conversation, unknown input goes smart. If a command produces *no* marker at all, the turn is retried once on the smart model, since the marker protocol is fiddly enough for a small model to fumble. The trace records which tier answered.

Fixed in `ec549cb`: **`gemma2-9b-it` was retired by Groq** and the lists still named it — replaced with the current production models (`openai/gpt-oss-20b` fast, `openai/gpt-oss-120b` smart, alongside the two llamas). Worse, **a retired model aborted the whole request**: Groq reports retirement as HTTP **400**, not 404, and only 404/429 fell through — so with the 8b rate limited and gemma2 dead, the chain stopped at the dead model while a working 70b sat next in line. The fallback chain existed and could not be reached. Retirement is now matched on the message text rather than the status code, and the model is dropped for the life of the process.

That screenshot also confirmed the arithmetic: `Requested 2674` against 100,000/day is **~37 commands per day** on the 70b alone.

**Still open — the biggest remaining win:** a careful editing pass on the system prompt itself, ~2,000 → ~1,200 tokens, which would roughly double the commands-per-minute headroom. Left undone deliberately: trimming prompt text carelessly is how the behaviours fixed this session regress.

## ⚠️ Groq rate limits (hit on-device, 2026-07-28)
A Diagnostics trace showed one good round-trip then **25 rate-limit failures in ~30s**. Two fixes shipped in `604d07f`: the client now surfaces Groq's own message (which limit — requests/minute vs tokens/day — and when it clears) instead of a generic "wait a moment", and it **refuses locally until the limit clears** rather than spending more rejected requests against the same quota.

Worth knowing: **Groq's limits are per account, not per user.** This is the single-user preview of the scaling problem in [`COMMERCIALIZATION.md`](COMMERCIALIZATION.md) — with a shared key behind a proxy, every user hits it simultaneously. Part E1 is the fix.

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
| In-app voice picker | 🔬 | pending | no trip to Android settings |
| Home reads the real calendar | 🔬 | pending | replaced hardcoded fake events |
| Polish (toggle/onboarding) | ⏸️ | — | Part D |
| Key out of the APK (proxy + BYOK) | ⏸️ | — | Part E1 |
| Play compliance + release AAB | ⏸️ | — | Part E2–E3 |
| Subscription billing | ⏸️ | — | Part E5 |
| Files tab (PDFs + notes) | 🔬 | pending | app-private, no new permission |
| Recovers from a failed step | 🔬 | pending | replans from the live screen, max 2 tries |
| No spoken thought process | 🔬 | pending | narration clauses stripped, not tidied |
