# JARVIS OS — Session Handoff

## Current position — 2026-08-23

Branch `claude/phone-glitch-investigation-g6dnhk`. The UI round is done: the
themes were cut from seven to four, Orbit was rebuilt, and the orb now opens into
an endless zoom (`OrbUniverse` + the pure `UniverseMath` behind it). All of it is
Compose, so **CI is the first real compile** — check for the `jarvis-debug-apk`
artifact before believing any of it, per Rule 2.

Backend Phase 1 is still parked on the user: Connect-to-Git in Cloudflare with
root directory `backend`, `GROQ_API_KEY` and `PROXY_SECRET` as Worker secrets,
then `POST /admin/migrate`.

### New gotcha — the off-device gate can now see part of `ui/`

`scripts/jvmcheck` used to exclude `**/com/jarvis/os/ui/**` wholesale. It now
excludes everything under `ui/` **except** an explicit allow-list of files that
import no Compose (`OrbMath.kt`, `UniverseMath.kt`, and their tests). Two things
to know before touching it:

- It is a **Spec**, not an include pattern. In Gradle's pattern sets an exclude
  always beats an include, so "exclude `ui/**`, then include one file back"
  silently keeps excluding it.
- The Spec is asked about **directories** as well as files, and excluding a
  directory prunes everything beneath it. Without the `isDirectory` guard,
  `ui/components` is excluded because its *name* is not on the allow-list, and
  the files inside are never offered — which looks exactly like the Spec not
  working at all. That cost one debugging round.

Adding a file to the allow-list that imports androidx breaks the harness with a
resolution error rather than a clear one, so the bar is literally: does it import
anything from `androidx`?


> Everything a fresh session (or future me) needs to resume instantly. Update the "Current position" line as work ships.
> Companion: [`PRODUCT_PLAN.md`](PRODUCT_PLAN.md) · [`PROGRESS.md`](PROGRESS.md) · [`EXECUTION_PLAN.md`](EXECUTION_PLAN.md) · [`COMMERCIALIZATION.md`](COMMERCIALIZATION.md) · [`JARVIS_MEMORY.md`](JARVIS_MEMORY.md)

## Current position + immediate next

**`main` @ `11346ee`** — branch `claude/phone-glitch-investigation-g6dnhk` @ `cb758d2`, several
commits ahead and awaiting artifacts.

Unmerged on the branch: backend **Phase 0 + Phase 1 code** (`backend/`, 48 tests, gated by a
`backend` CI job), the confirmation fix, the mid-task interrupt fix, `SpokenText`, the orb rework,
and the **theme sweep** below.

**Next session, in order:** (1) confirm the artifacts and fast-forward `main`; (2) the user is
mid-way through Cloudflare setup — Connect-to-Git, then the two Worker secrets, then
`POST /admin/migrate`, then a real `/chat` (Phase 1 done); (3) Phase 2, the eval harness, which is
the testing payoff; (4) get a device look at the theme sweep before doing more UI.

### 📜 Long replies were CLIPPED, not merely long
`state.transcript` / `state.reply` were bare `Text` inside a Column pinned to the viewport height,
so anything past the fold was unreachable. Both now live in their own `verticalScroll` column,
`heightIn(max = height * 0.34f)`.

**GOTCHA: bound it as a FRACTION of the hero, never a fixed dp** — the orb above has to stay on
screen on a small phone, and a constant that is right on one device is wrong on the next.
**GOTCHA worth generalising: raising `max_tokens` to 900 made this worse, not better.** Longer
answers arrived and there was still nowhere to put them. A fix upstream can expose a bug downstream
that was invisible while the upstream limit was hiding it.

### 🗂️ A tab row BELOW the content it switches taxes every tab
Settings rendered three feature cards unconditionally, and the tab row came after them — so their
height was stolen from every section underneath. On a real phone the theme picker was one clipped
row at the very bottom. `ThemesScreen` is a `LazyColumn` that already filled whatever space it was
given; it simply never had any.

Now three tabs — **General / Voice / Themes** — with nothing above the row but the title, so each
section owns the screen. **RULE: nothing permanent goes above a tab row.**
**Also:** the host draws a menu icon at the top-left of every destination, so a screen's own title
must be indented past it (`start = 56.dp`) or the burger lands on the first letter.

### 🎨 THE THEMES REACHED ONE SCREEN IN SEVEN — count before believing it is taste
"the themes are just not it". It was not taste, it was arithmetic: **`HomeScreen` read
`LocalPalette`; the other six screens imported the fixed `Cyan` constant directly** — 13 references
in `SettingsScreen`, 13 in `InstructionsScreen`. So Forge (copper) or Nebula (violet) recoloured the
orb and the backdrop, and everything else stayed cyan.

Fixed with `ui/theme/JarvisTheme` — an object of `@Composable` getters, the
`MaterialTheme.colorScheme` pattern — and a sweep of all nine UI files onto it. `Color.kt`'s
constants remain as the raw values `JarvisPalette.Arc` is built from; **nothing should import them
by name again.**

**GOTCHA: the glass fill was the other half.** It was a flat 8% white, so every card looked
identical in all seven themes — the accent changed and the surface it sat on did not. It now takes
the accent at low alpha.

**GOTCHA for any future mechanical sweep of this kind:** never rewrite `import`/`package` lines (it
mangles the paths), exclude the Material `Surface(` composable from the `Surface` *colour* with a
lookahead, and replace `SurfaceGlass` **before** `Surface` or the longer name gets eaten by the
shorter. All three were verified after the fact by grep, not assumed.

### 🌌 `ThemeBackdrop` now sits behind EVERY destination
It was drawn inside `HomeContent`, so the app had one designed screen and six flat dark lists —
open Settings and the world you were just looking at vanished. That gap, rather than any individual
screen, is most of what "boring and basic" was describing. Moved into the host `Box` in `JarvisApp`.
Also cheaper: one instance means the starfield stays put while destinations change over it, instead
of re-randomising per screen.

**Mobbin was requested for design references and is unavailable** — `Mobbin MCP requires a paid
plan` on every query. Do not plan around it without checking first.

**⚠️ Compose is unverifiable off-device** (needs `dl.google.com`). `jvmcheck` passing proves only
that no non-UI source broke; the sweep itself rode on CI.

### ✅ BACKEND PHASE 0 IS DONE — `cd backend && npm test` → 25 tests, 0 failures
Quota + token accounting against a fake provider. **No dependencies, no account, no deploy.** A
`backend` job in `.github/workflows/build.yml` runs the same command in seconds.

**Run it before touching anything under `backend/`.** It is to the proxy what `scripts/jvmcheck`
is to the app.

**GOTCHA: `node --test <dir>` fails on Node 22** — it resolves the directory as a module. Use the
glob; `npm test` already does (`node --test test/*.test.mjs`).
**GOTCHA: `backend/package.json` sets `"type": "module"` on purpose.** Without it the `.js` sources
load only because Node 22 sniffs ESM syntax — a fallback, not a contract.

**⚠️ Auth is a STUB (`X-Uid` header).** Any caller can claim any uid and therefore anyone's
allowance. **Do not deploy to a public URL before Phase 3.** Said in `backend/README.md` and again
at the line in `src/index.js` that does it.

Pure and tested: `src/quota.js` (UTC day key, cap, over-cap decision, the spoken refusal) and
`src/models.js` (plan → model list, retired-model guard, cross-cover). `src/index.js` is routing
only — the same split as the Android side, where the pure types are the tested ones.

Behaviours pinned because each is a way this could be quietly wrong: over the cap the provider is
**never called**; two concurrent turns are both counted (the whole D1-not-KV argument); a provider
failure charges nothing; and the overshoot-by-one is pinned as **intended**, so nobody later
"fixes" it by pre-charging an estimate that would be wrong in both directions.

**Next: Phase 1 — the real Groq call. Blocked on a Cloudflare account.**

### 🏗️ THE BACKEND PLAN IS WRITTEN — [`BACKEND_PLAN.md`](BACKEND_PLAN.md), phases 0–6
All phases, what each is blocked on, and what is verifiable where. `COMMERCIALIZATION.md` §1d stays
the source of truth for the *architecture*; the new file is the *plan of work*.

**Phase 0 is startable with NOTHING from the user**, and it is the first part of this whole project
that can be genuinely tested where it is written. Verified in this environment: Node **v22.22.2**,
npm registry reachable, and a Worker-shaped ES module (`export default { fetch(req, env) }`) runs
under **`node --test` with ZERO dependencies** — Node 22 ships real `Request`/`Response` globals.
A probe passed. No Cloudflare account, no Firebase, no deploy, no `npm install` needed to start.

**Blockers, in order:** Cloudflare account → Phase 1. Firebase project → Phase 3. Free-tier cap
(~60k tokens/day recommended) → before go-live.

**⭐ Phase 2 is the testing answer** and should be pulled forward the moment Phase 1 lands: a script
firing every `docs/SCREEN_CONTROL_EVAL.md` prompt at `/chat` and asserting marker shapes turns the
50-prompt manual checklist into an automated eval. It does NOT cover whether a tap lands on the real
Blinkit — the backend owns the *deciding*, the phone still owns the *doing*.

**GOTCHA already banked: the Firebase Admin SDK is Node-only and does NOT run on Workers.** Verify
the RS256 JWT by hand with WebCrypto — ~60 lines, no dependencies. Biggest trap in the plan.

### 🧰 RUN THE OFF-DEVICE GATE BEFORE EVERY PUSH — it is a file now, not a recipe
```sh
gradle -p scripts/jvmcheck test     # 432 tests, 42 classes, ~10s
```
Compiles all 50 non-UI sources against a real `android.jar` and runs every pure test class. Full
notes in `scripts/jvmcheck/README.md`. **GOTCHA: Maven Central answers 429 under load** — retry
with backoff, it clears within a few minutes. Only `dl.google.com` is actually blocked, so the
Compose layer and the instrumented tiers remain CI-only. A green run here is NOT Rule 2 done —
still wait for the artifact.

### 📊 The binding constraint is TOKENS per minute, not requests
The user's Groq dashboard (2026-08-18): `openai/gpt-oss-120b` touching its **8.3K tokens/minute**
ceiling, while requests peak around 5 against a 30/min limit. An agent step carries `AGENT_PROMPT`
plus a whole screen dump, so two or three of them can saturate a minute. **Design Part E's metering
around tokens** — the plan already says meter tokens not requests, and this is the evidence for it.
Also: the dashboard is a third source of truth alongside the trace and the code. It answered a
question neither of the others could — whether a request was ever sent.

### 🔇 A silence in the log is not the absence of a bug
A trace had a **sixteen-second hole** mid-errand: app opened, nothing said, user gave up. Nothing
in the log explained it, because **every line in this app reports an event that already happened**
— so a hang leaves no trace at all. Compounding it, the transport allows 15s connect + 30s read
**per model** over a chain of more than one, so a slow step can outlast a minute and a half with
the screen tinted and JARVIS mute. Now: `errand: asking for the next move` logs BEFORE the call,
the answer logs its elapsed time, and `AGENT_STEP_TIMEOUT_MS` (25s) bounds the whole thing.
**The cause is still not known** — the dashboard shows no 429 in that window and no matching
request — and the instrumentation is deliberately the fix rather than a guess. Same move as the
`no field found — active=… windows=…` diagnostic. Read those two lines in the next trace first.

### 🚪 A guard is only as good as the paths that REACH it
"can you open YouTube" while already in YouTube **relaunched it**, throwing away the video. The
guard for exactly this — `"already in X — not relaunching"` — has existed in
`ScreenControlService` since a Blinkit search got reset by the same fault. It never ran, and the
trace says so **by omission**: no `step 1/1` line. An Open-only plan has `needsAccessibility ==
false`, so `executeScreen` took a shortcut branch calling `AppLauncher.launch` directly, skipping
the service and its guard. Now routed through `runSteps(recover = false)`.

**This is the THIRD instance of this shape**, and it is worth naming: `runSteps(opens) { ok, _ -> }`
ignoring an honest failure; `mediaCheck`'s `!busy` guard that `say()` never set; and now a guard on
a branch that never executes. **Writing the protection is the easy half.** When adding one, list
every path that reaches the thing being protected — and when a trace lacks a line you expect, that
absence is evidence.

### 🔊 The work session was DEAF for as long as the app played audio — fixed
`audio started — pausing listening` at 19:39:04, `audio stopped` at 19:39:18. Fourteen seconds in
which the recogniser had stood down (correct — holding the mic takes audio focus and would pause
the user's video) **and** the background wake word was off too, because it was gated on
`!session.isActive`. Nothing was listening. The only way back in was the notification's Talk
button — a tap, in a feature whose promise is that you can keep talking. On YouTube that is the
whole time.

New pure `WorkSession.wantsHotword` = `micGranted && !jarvisVisible && (!sessionActive ||
yieldedToMedia)`. `yieldedToMedia` already names the safe window exactly — session up, off screen,
not speaking, Talk not tapped — so the recogniser is definitionally not listening and the one-owner
rule holds. The wake word takes no audio focus, so the video keeps playing.

**GOTCHA: this had to go in `applyMicOwner`, not `pause`.** The state is entered and left by the
media check, long after `pause()` ran; deciding it once on the way out of the foreground leaves the
gap exactly as it was.
**GOTCHA: a mid-session detection must NOT bring JARVIS to the front.** The session exists so the
user can talk without leaving the app they are in. `HotwordService.onDetectedInSession` claims the
mic in place — the same one-turn claim the Talk button makes — after a `MIC_HANDOFF_MS` gap, since
the detector's `AudioRecord` release races a too-eager recogniser into `RECOGNIZER_BUSY`.
`HotwordOwnershipTest` walks **every** combination of visible × session × media × speaking rather
than the four cases that came to mind; the last time two things could hold that mic, the feature
had to be reverted.

### 🤖 `llama-3.3-70b-versatile` is RETIRED — and good fallbacks hid it
`model llama-3.3-70b-versatile is retired — dropping it` on the first turn, and the dashboard shows
the matching **404** in every traffic cluster. It led `SMART_MODELS`, and `retired` only remembers
within one process, so every fresh launch paid a round trip to rediscover a permanent fact. Removed
from both tiers; `GroqClient.RETIRED_UPSTREAM` + `GroqModelListTest` keep it out.
**GOTCHA: a system with good fallbacks hides its own dead weight.** Nothing user-visible broke,
which is exactly why this could have sat there indefinitely — only the provider's own books showed
it. Check the dashboard, not just the trace.

### ✅ Voice barge-in is CONFIRMED on device — and needs no volume tuning
`barge-in — heard "Hey Jarvis" over the reply` → `interrupted — listening`. The expected problem
(JARVIS's own TTS masking the wake word at speaker-to-mic distance) **did not occur**; do not lower
TTS volume to chase it. Natural replies confirmed in the same trace ("Looking up pic now.", "Any
time at all.").

**STILL UNVERIFIED — and my first test for it could not have worked.** I asked the user to say
"open YouTube" and cut in before it opened. The reply is "Opening YouTube.", about a second and a
half, and the Open fires the moment it ends — a one-second window, which is a reflex challenge, not
a test. The real test needs a LONG reply *carrying* a queued app-open: "explain how a jet engine
works, then open YouTube", interrupted partway through. If YouTube opens anyway, `pendingScreen =
null` is broken. **Design the window before asking for the measurement.**

### 🗣️ Canned replies — the wording was the symptom
`Acknowledgement` (pure, 12 tests) replaced `"On it."`, `"Yes?"`, `"Anytime."` and the stock
calendar/alarm/file confirmations.

**GOTCHA: those lines only ever fired when the MODEL SAID NOTHING.** The system prompt already asks
for one short natural sentence before the markers, and `clean.isNotBlank()` has always won. So
"make the chat more natural" is not a prompt problem and not a wording problem — it is *what the app
does with a marker-only reply*. Fix the floor, not the ceiling.

The floor now names the action (`Opening Spotify`, derived from the plan so it cannot name the wrong
app) and repeats a search query back, which doubles as confirmation it heard the words right.
Variety comes from a **turn counter, not `Math.random`** — same reason `OrbMath.unitRandom` exists:
determinism keeps the type pure and lets tests pin exact strings.

**Do NOT remove `SendGuard` / `SpendGuard` / `AlarmGuard` when asked to make the chat natural.** None
of them alters a word; they only subtract irreversible *steps*. Their contributed sentences are now
short, which is the part that was actually making replies robotic. Rule 6.

### 🎙️ Barge-in — tap CONFIRMED on device; voice built but unverified
`TurnState` (pure, 14 tests) replaced the `busy` boolean, which was doing three unrelated jobs at
once. `Speaker` now overrides **`onStop`** and numbers every utterance. `engine.interrupt()` stops
speech, idles the turn *synchronously*, clears `pendingScreen`, and starts listening.
Voice trigger is `BargeInListener` + `MicOwner.BARGE_IN` — the wake word, **not** an energy VAD
(see the 2026-08-16 memory entry for why a VAD detects JARVIS himself every time).

**GOTCHA: `applyMicOwner`'s early return must let `BARGE_IN` through.** That owner exists *because*
the turn has the microphone gated, so the obvious `if (turn.micGated) return` skips the one branch
that needs to run. Speaking state is **derived** there from the turn phase rather than announced by
each speak path, so `WorkSession` and `TurnState` cannot drift.

**GOTCHA: a listener that clears its own running flag on detection lies to its caller.** `stop()`
then reports "nothing was recording", the hand-off gap is skipped, and the recogniser opens the
input while the audio thread is still inside `recorder.release()`. Break out of the loop instead and
leave the flag set, so `stop()` **joins** — which both reports honestly and guarantees the recorder
is gone before anything else asks for the mic. Same class: the model load runs on the audio thread
and a short reply can finish inside it, so re-check `running` after the load *and* after
constructing the `AudioRecord`, or a stopped listener still seizes the microphone.

**STILL UNVERIFIED after the 15:41 device trace:** every interrupt in it landed on a reply with
`screen=none`, so **`pendingScreen = null` has never actually been exercised**. Say "open YouTube",
interrupt "On it." — if YouTube opens anyway, that is the bug.
**EXPECTED to need tuning:** JARVIS's own TTS may *mask* the wake word at speaker-to-mic distance.
Symptom in a trace is `barge-in armed` with no `barge-in — heard` after it; the lever is lowering
TTS volume (`Speaker.doSpeak` passes `null` params today) while the listener is armed.

### ✅ E2E tap probe — GREEN as of `70d4c76`. Read Gradle's verdict, never the jobs API
The 2026-08-14 `kotlin-stdlib` fix **never could have worked**, and the failure log says why:

```
Process: com.jarvis.os.test, PID: 2416
NoClassDefFoundError: Failed resolution of: Lkotlin/jvm/internal/Intrinsics;
  at FixtureActivity$Recorder.tapped
DexPathList[[zip file ".../com.jarvis.os.test-.../base.apk"]]
```

**GOTCHA: this job is `continue-on-error`, so the jobs API reports `conclusion: success` even when
the probe fails.** Its step conclusions do too. The only trustworthy signal is Gradle's own line in
the log — `BUILD SUCCESSFUL` / `BUILD FAILED` — plus the presence or absence of a `FAILED` test line.
Same family as "the job-status API lags 2–5 hours, so wait for the artifact": in this repo, never
take a green tick as evidence.

**GOTCHA: the instrumentation and an androidTest-APK Activity are DIFFERENT PROCESSES.** The test
runs in `com.jarvis.os` — it reads `ScreenControlService.instance`, a static that only exists there.
An Activity declared in the androidTest manifest launches into `com.jarvis.os.test`. So **a static
field cannot carry a result between them**: the original fixture's `@Volatile` `Recorder` could only
ever have read `null`, and the `Intrinsics` crash was masking that. Report through the
**accessibility tree** instead — it crosses processes by design, and asserting on it is a stronger
claim anyway, since it is the same mechanism JARVIS uses to read a screen. Beware the general shape:
fixing a crash can convert it into a silent wrong answer with the identical failure message.

**GOTCHA: AGP strips from the androidTest APK any dependency already present in the app APK.** That
is normally correct — the test process usually shares the app's classloader — but `FixtureActivity`
is launched as a *separate process* (`com.jarvis.os.test`) whose dex path is the test APK **alone**.
So `androidTestImplementation("org.jetbrains.kotlin:kotlin-stdlib")` is accepted, builds green, and
packages nothing. Declaring a dependency is not the same as shipping one.
**FIX (not yet done): write `FixtureActivity` in Java.** Java emits no `Intrinsics` null-checks, so
the dependency stops existing rather than needing to be packaged. ~20 lines.

**The tap itself is PROVEN.** The same stack trace reads
`TextView.performAccessibilityActionClick` → `View.performClick` → the fixture's own `onClick`: a
real accessibility tap reached a third-party-package fixture and invoked its handler. All three of
the tier's foundational unknowns are now settled — service binds, fixture presents a distinct
package, tap lands. Only the *recording* of the tap fails.

### 🔬 Gradle CAN fetch here — Maven Central works, Google's Maven does not
`CLAUDE.md` Rule 5's "Gradle cannot fetch through the proxy" is only half true. **Maven Central is
reachable; `dl.google.com` 403s on the network policy**, which is why `androidx`/Compose and the real
`tensorflow-lite` cannot be resolved. But the **50 non-UI sources reference nothing in `ui/` or `R`**,
so they type-check off-device against the real framework, and the 45 pure-JVM test classes run:
**402 tests, 0 failures.** Recipe — a plain `kotlin("jvm")` Gradle project with
`sourceSets["main"].kotlin.setSrcDirs(listOf("<repo>/app/src/main/java", "stubs"))`, excluding
`**/com/jarvis/os/ui/**` and `MainActivity.kt`, `compileOnly("org.robolectric:android-all:16-robolectric-13921718")`
+ `kotlinx-coroutines-core`, and ~30 lines of stubs for `BuildConfig`, `ContextCompat`,
`org.tensorflow.lite.Interpreter`, `MainActivity`, and the three `androidx.compose.runtime` symbols
`AssistantEngine` uses (`State`, `MutableState`, `mutableStateOf`). Set the test task's `workingDir`
to the repo root or `MelSpectrogramTest` cannot find its weights blob. **Use this before every push.**
Caveats: only JDK 21 is present (set `jvmTarget` to match), the Compose layer is still unverifiable
here, and `org.tensorflow:tensorflow-lite` on Central is a placeholder that is not a valid zip.

### 🧠 Learning layer — half built
`control/ControlVocabulary` (pure, 9 tests) translates a generic label the model asks
for into the literal one a given app uses, keyed by package FRAGMENT. Wired into
`ScreenControlService.seek()` **strictly additively** — only after the requested label
failed to match confidently, only acting on a match ≥ `GOOD_SCORE`. It therefore cannot
break a tap that already worked, and a stale seed just fails like a wrong label.
**GOTCHA: do not seed a label the existing prefix scoring already matches** (Instagram's
"Search", WhatsApp's "Search…" both score 90) — the entry never fires and the table then
implies coverage it does not have.
**NEXT on this:** the *learned* half — record the literal that actually worked, on the
same `ok && clean` gate `Playbook` uses; and `AppRegistry` (spoken name → app) so
nicknames stop depending on prompt injection.

### ⏭️ E2E tap tier — assumption #1 SETTLED (the service DOES bind); tap still unproven
The probe's diagnostics diagnosed it in one cycle: the settings write **stuck**, the
service was **installed**, accessibility was **globally on**, and the enabled-services
list was **empty**. Nothing failed — something switched it off. That something is
**`UiAutomation`, which suppresses every other accessibility service by default**, and
the probe's own `shell()` helper is what connected it. The instrumentation used to
enable the service was disabling it. Fixed with
`getUiAutomation(FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)` behind one accessor.

**GOTCHA: `UiAutomation` suppresses all other accessibility services.** Any instrumented
test needing a real `AccessibilityService` bound MUST use
`getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)` and must never
touch the unflagged `instrumentation.uiAutomation` first — the flag applies only as the
connection is created, so one unflagged call anywhere reintroduces the suppression.

**The app was never at fault** — no manifest, `exported`, permission or timeout change was
needed, and each of those was a plausible thing to have guessed at.

**Run #249 then moved the failure PAST the bind assertion**, which settles the tier's first
unknown: a real `AccessibilityService` CAN be enabled and bound from an instrumented test.
It died later and smaller — `ActivityNotFoundException {com.jarvis.os/…FixtureActivity}` —
because the Intent was built from `targetContext` while the fixture lives in the androidTest
APK (`com.jarvis.os.test`). Fixed by building it from the **test** context (`80af271`). That
exception is itself the first hard evidence the two packages are genuinely distinct, which is
what assumption #2 requires. **Still unproven: that a real tap reaches the fixture.**
Next: confirm green, then grow the suite to the classes pure tests cannot reach (unrendered
screen, failed `Open`, first-move `Back`, right control among decoys), ≤~8 tests, non-gating.

### Previously — probe built, RAN, and FAILED at assumption #1
`A11yProbeTest` (branch only, non-gating job `e2e-tap`) reported **"the accessibility
service never bound"**: writing `enabled_accessibility_services` by shell did not bind
`ScreenControlService` within 15s on an API 34 `default` image. The two questions after
it — does an androidTest-APK Activity present a package the executor will act on, does a
real tap reach the fixture's click handler — **never ran and are still open**. Cause is
**not** diagnosed and deliberately not guessed; the probe now reports whether the settings
write stuck, what `AccessibilityManager` lists as enabled/installed, and the test/target
processes, and waits 40s. **Next session: read those facts first.** If the write stuck and
the service is installed but never bound, the settings route is not enough on this image
and the tier needs a different way in (the recorded fallback is a separate `:fixtures`
module + an `adb install` CI step, but that fallback was for the PACKAGE constraint, not
this one — do not reach for it before the facts are in).

**GOTCHA: `continue-on-error` on a STEP makes the jobs API report that step as `success`
even when it failed.** It was set on both the probe step and its job, so all four jobs
looked green while the test was red; the failure was visible only in the raw log. Put it
on the **job** only. Same family as "in progress is not evidence of progress" — trusting
the signal the API offers first over the one that is true.

**GOTCHA: a probe whose failure does not name which assumption broke is barely worth the
emulator cycle.** "Never bound" cost a whole run and said only "something is wrong".

### 2026-08-14 device trace — eight bugs fixed, one race behind most of them
**The root cause: the errand loop decided its first move ~1s after launching an app, before the
app had drawn.** The proof is one line — `choosing … from 1 on-screen options` one second after
`Open(YouTube)`, whose feed has dozens. The model was handed a splash screen and answered the only
ways it could: a reflexive `<<BACK>>` or a `<<PICK>>` against a list of one. Three errands died on
their opening move. Fixed with `AgentLoop.looksUnrendered()` + a bounded re-look, applied **only
before the first action**. Then: `JUST_ARRIVED` joins `GOING_IN_CIRCLES` in `AgentLoop.NUDGEABLE`
(a first-move Back is a habit, not a dead end); a failed leading `Open` now stops the errand and
says so (`couldNotOpenMessage`) instead of driving on; **`say()` now sets `busy`** (TTS rides the
music stream, so the media check was hearing JARVIS and yielding the mic after every agent-loop
line); a marker that was a sentence's subject no longer leaves a headless spoken fragment;
stripped marker chains no longer leave blank-line holes; the playbook no longer learns **or
replays** complaints and no longer re-learns its own replays; and search-box chrome (`Clear`,
`Cancel`, …) is kept from the `<<PICK>>` chooser. Prompt gained "ask only what you need to act
now, never for details you cannot set" (five turns were lost to delivery-slot/address questions
`AskGuard` correctly dropped). 24 frozen regressions in `DeviceTrace0814Test`.

**GOTCHA: reporting a failure honestly is only half the job — something has to READ the report.**
`Open(app=Search)` failed exactly as the 2026-08-09 fix intended, and `runSteps(opens) { ok, _ -> }`
ignored `ok` and drove the errand anyway.

**GOTCHA: a guard that depends on a flag is only as good as the paths that set it.** `mediaCheck`'s
`!busy` guard against hearing JARVIS's own TTS was written on 2026-07-28 and was correct for
`ask()` and `speakAck()` — and `say()`, the agent loop's only speech path, never set the flag at all.

**GOTCHA: an app is FOREGROUND long before it has DRAWN.** `APP_OPEN_MS` (1200ms) buys the former,
not the latter. Anything that reads the screen right after a launch must check it is worth reading.

**⏭️ Asked for and NOT built: deliberate scrolling.** "Could you scroll down into my playlist and
play Cut" made the loop thrash `Tap(Peak) → Tap(Find) → Tap(View Library) → Tap(Peak) → …` until
the repeat guard stopped it. `<<TAP>>` scrolls only while hunting a *named* label, so a list cannot
be browsed. There is no scroll verb in `AGENT_PROMPT`. This is the obvious next feature.

**Doc correction (2026-08-14):** the "recreate `SpeechRecognizer` per turn" gotcha below is **stale**.
The shipped `VoiceController.stopListening()` calls `cancel()` and reuses one instance — because the
voice loop was deleted and rebuilt that way on 2026-07-26, and *that* rebuild is the version the user
confirmed working. The real culprits were the custom silence hints and a divergent "Yes?" hand-off,
both long gone. Do not "fix" it back.

### Previous position (2026-08-09)
**`main` @ `b1158b6`** — green build **#237** (all 3 CI jobs; emulator green on attempt 1).
**Shipped & merged this session: five device-trace bugs fixed in code.** An on-device eval surfaced
real failures; each now has a frozen regression test. (1) **Alarm across turns** — `AlarmGuard.apply` now
takes the prior assistant turn so "set an alarm for tomorrow" → "6 o'clock" is honoured (it was dropped
because the guard only saw the latest utterance). (2) **Phantom `<<BACK>>`/`<<HOME>>` out of a just-opened
app** — new `AgentLoop.JUST_ARRIVED` guard refuses a Back/Home before any in-app tap/type. (3) **Typed the
user's conversational sentence** — `parseMove` refuses a `<<TYPE>>` echoing the whole sentence-length goal.
(4) **`<<OPEN|non-app>>` silent success** — `ScreenControlService` Open branch now `failed()`s on null
resolution. (5) **PICK chose the "Reels" nav tab** — new pure `control/PickFilter` strips nav chrome before
the chooser. **GOTCHA: a guard tested only on single utterances is untested for two-turn flows** (alarms,
confirmations, "which one?" answers live on the SECOND turn — pass the prior turn as context). **GOTCHA: the
errand test harness's leading `<<OPEN|app>>` reply already IS the engine's `errandSteps = opens` seed — write
Open-then-move scenarios; do NOT pre-seed `taken` (it doubles the Open and trips the repeat/count guards).**
Earlier this session (merged to `main`): negation-aware guards (`assistant/Negation.kt`) and `SpendGuard`
per-action authorization. **GOTCHA: `SpendGuard` withholds an irreversible tap only when the user did NOT name
that action un-negated — spend taps need a spend word, call/share/delete need their verb, send/post belong to
`SendGuard`.** On-device eval prompts + a §F regression re-verify list live in `docs/SCREEN_CONTROL_EVAL.md`.

### ⏭️ NEXT UP — designed, NOT started (awaiting one decision): E2E test pipeline + learning layer + 100 scenarios
The user asked for (a) an **automated end-to-end test that exercises real screen tapping** (so a commercial
product isn't validated by hand-running 50 prompts), (b) JARVIS to **learn apps/buttons/symbols**
(chosen: **pre-seed a curated set + learn from successful runs**), and (c) **100 more accuracy scenarios**.
A full plan was researched (3 Explore agents mapped the a11y mechanics, the learning substrate, and the
build/test infra). **Key finding: the pyramid has NO emulator test that drives a tap** — the two
instrumented tests only load a model + render one Compose screen. Three mergeable phases:

- **Phase 1 — E2E fixture tap tier (the missing pipeline).** Enable the real `ScreenControlService` on the
  emulator via `uiAutomation.executeShellCommand("settings put secure enabled_accessibility_services
  com.jarvis.os/com.jarvis.os.control.ScreenControlService")` + `accessibility_enabled 1`, poll
  `isRunning()`, then drive hand-built `List<ScreenStep>` through the public `runSteps(...)` (`:115`)
  against fake-app fixtures, asserting the fixture's own click callbacks fired (the `InstructionsScreenUiTest`
  `onSave` pattern). **HARD CONSTRAINT: fixtures must present a DIFFERENT package than `com.jarvis.os`** —
  `awaitApp`(`:597`)/`userAppRoot`(`:357`) reject `frontPkg == packageName`, so `src/debug` fixtures (same
  package) fail for taps. Use androidTest-APK activities (package `com.jarvis.os.test`) — **de-risk with a
  1-test PROBE first** asserting `rootInActiveWindow.packageName == "com.jarvis.os.test"` + one Tap fires;
  fallback = a separate `:fixtures` module (`com.jarvis.os.fixtures`) + `adb install` CI step. Needs
  `androidx.test.uiautomator:uiautomator` + `androidx.test:rules` added to `androidTestImplementation`.
  `Pick` needs an `onPick` stub (no LLM); don't use `Open` in fixtures (it launches a real app).
  **⚠️ The team ALREADY tried a11y-in-emulator and found it flaky, with a hard ~8-test VM capacity ceiling
  (10 crashed it; orchestrator+hardening reverted).** So: keep the E2E suite SMALL (≤~15), fixtures render
  **no HudOrb/infinite animation**, put it in a **SEPARATE `instrumented-e2e` CI job that is
  `continue-on-error` (non-gating) until proven stable**. The value calculus changed — the recent executor
  bugs (phantom BACK, PickFilter, silent OPEN, typed-goal) are integration bugs pure `ScreenMatch` tests
  can't catch — but the flakiness constraint is real.
- **Phase 2 — learning layer (build FIRST among features, per the user).** Generalise the `Playbook` route
  model (SharedPreferences+JSON, learns only clean non-irreversible runs). New `control/AppRegistry` (+store):
  spoken-name/alias → canonical app; pre-seed inline (youtube/yt, insta, blinkit…), learn from clean OPENs,
  **plug into `AppLauncher.resolvePackage`** (`:35`, before the live `rank`) → makes nicknames deterministic
  (today they only work via a prompt-injected fact) + validates OPEN targets (reinforces the Fix-4 honest
  failure). New `control/ControlVocabulary` (+store): `(package,intent) → the literal label that worked`
  (e.g. Blinkit `search` → `"Search for atta, dal, coke and more"` — the case in `AgentLoop.kt:130`);
  pre-seed + learn from clean drives; **plug in at resolve-time** in `ScreenControlService` before `seek`
  (`:614`), rewriting a generic `Tap("Search")` to the learned literal (kills the "tapped Search, missed
  the real box" class; skips an LLM round-trip). Symbols = extend nav-label set + a contentDescription
  synonym table (**no vision** — user didn't pick it). Capture rides the SAME gate as `Playbook`
  (`ok && ranClean`, `AssistantEngine.kt:1002`). Stores follow the prefs+JSON+companion-object pattern
  (Robolectric + plain-JUnit split).
- **Phase 3 — 100 scenarios**: ~80 deterministic (tier 1, incl. the learning layer) + ~10–15 E2E fixture
  flows (tier 2) + expand `docs/SCREEN_CONTROL_EVAL.md` to ~100 and mark rows now covered so the human list
  shrinks + an OPTIONAL non-gating LLM-plan-quality eval job (real Groq, marker-shape assertions, reports a %).

**⚠️ OPEN DECISION before starting:** the user said "learning feature first" but then pushed hard on the
testing pipeline. I recommend **Phase 1 (E2E harness) first** (it's test infra that also verifies the
learning layer on real taps), then Phase 2, then Phase 3 — but Phases 1 & 2 are independent, so confirm the
order with the user before building. Each phase is its own mergeable increment (Rule 2). Honest boundary
that survives all of it: no CI test can cover **the LLM choosing a good plan on the real Spotify/Blinkit** —
that stays device-eval; the E2E tier owns the **execution/tap** layer, tier 1 owns the deterministic logic.

**The test pyramid is COMPLETE** — all six layers live: pure JVM, Robolectric, Python wake-word check,
emulator (openWakeWord load+detect **and** Compose UI: `InstructionsScreenUiTest`),
trace-replay, lint. The screen-control a11y-binding emulator test was deliberately skipped (flaky, no
value over `ScreenMatchTest`) — documented device-only ceiling. A2 also shipped earlier: `control/ScreenMatch`
+ Groq/Gemini parsers `internal`, covered by `ScreenMatchTest` + `Groq/GeminiClientParseTest`.
This position folds in: the wake-word LOAD fix (Kotlin melspectrogram — see the wake-word section below,
now CI-confirmed on the emulator, no device); speech keeping the recogniser's n-best alternatives
(`voice/Transcript`, JUnit-tested); the rebuilt custom-instructions screen; and the translucent "JARVIS is
controlling the screen" tint (accessibility overlay, no new permission). **Next: Phase A2 of the test
pyramid** — extract `control/ScreenMatch` + widen the Groq/Gemini JSON parsers to `internal`, with unit
tests (see the plan). On-screen drawing + screen-control loop remain device-unconfirmed (Rule 5). New
gotcha: memory can hold a *typed* fact ("peak") that contradicts an *auto-learned* one ("Pic") from a
mishearing — deletable
on the redesigned instructions screen.

**Wake word is BUILT — in-app asleep/awake (`voice/WakeWord` + engine `awake` flag).** Rebuilt
without repeating the 2026-07-26 failures: NO second recogniser (avoids `RECOGNIZER_BUSY`), the
"Yes?" ack reuses the normal speak→listen path (`speakAck`), and no custom silence hints. Asleep,
the engine ignores everything but "Hey JARVIS"; on wake it says "Yes?" or runs the trailing
command, stays awake 18s, then sleeps. A work session counts as engaged; "thank you Jarvis" and
the notification Stop sleep it. The orb is tap-to-wake while idle. **This is NOT a true mic-off
hotword** — stock Android keeps the recogniser running while asleep, it just won't act. The
mic-hardware-off version is **openWakeWord** — now BUILT (below). Porcupine was built and reverted
(Picovoice needs a company email); do NOT reach for it again. Do NOT reintroduce a background second
recogniser — that is the older reverted design. In-app matcher is unit-tested; on-device unconfirmed.

**Mic-off "Hey Jarvis" from ANY app is BUILT via openWakeWord (key-free, this branch).**
`voice/OpenWakeWord` runs 3 bundled TFLite models (assets, `noCompress "tflite"`, ~3.6 MB) — a port
of openWakeWord's streaming pipeline **pre-verified in Python with litert** (mel input resize to
1760 → [1,1,8,32]; transform `mel/10+2`; embedding window 76 step 8; predict window 16; feed raw
int16-as-float, NOT normalised; threshold 0.5; scores logged). `control/HotwordService` = mic
foreground service running it in the background; on detection it launches MainActivity (singleTask,
`EXTRA_WOKE_BY_HOTWORD`) → `engine.wakeFromHotword()` → "Yes?" → command via the recogniser.
One-owner by lifecycle: engine starts it in `pause()` only when backgrounded + asleep +
`!session.isActive`, stops in `resume()`; recogniser and hotword never run together. Default-on
`UserPreferences.backgroundWake` + Settings toggle + notification Stop. **DEVICE-ONLY UNKNOWNS to
check from a trace:** (1) does a real "Hey Jarvis" cross 0.5 — tune `OpenWakeWord.DETECT_THRESHOLD`;
(2) `ForegroundServiceStartNotAllowedException` on the `pause()` start (Android 12+) — if so, switch
to an always-running service that gates capture rather than start/stop; (3) Realme battery killer —
exempt JARVIS from battery optimisation. Foreground wake still uses the in-app gate above.

**Wake-word LOAD — was the on-device blocker, caught in CI then fixed by a Kotlin melspectrogram (2026-08-09).**
The `CONV_2D ... BytesRequired overflowed` crash that kept openWakeWord from loading is an Android-runtime
shape-inference bug on the melspectrogram model's DYNAMIC-length input. The emulator load test
(`OpenWakeWordInstrumentedTest`) proved — no device — that it reproduces on EVERY Android runtime:
`tensorflow-lite` 2.16.1/2.17.0 (frozen; 2.17.0 is Maven Central's last) AND LiteRT 1.0.0/2.1.0. The
Python `ai-edge-litert` runtime is the only one that infers this graph correctly. **GOTCHA for future me:
this is NOT a runtime-version problem — do not keep bumping runtimes hoping for a fix; they all overflow.**
**Fix:** the melspectrogram (a textbook librosa power-to-db mel: frame→512-tap DFT→power→mel matrix→
`10log10`/clamp) is reimplemented in pure Kotlin (`voice/MelSpectrogram.kt`) using the model's OWN weights,
extracted by `scripts/owwtest/extract_melspec_weights.py` into `assets/openwakeword/melspec_weights.bin`
(the source `melspectrogram.tflite` now lives in `scripts/owwtest/reference_model/`, OUT of the APK, so the
weights stay reproducible). The two FIXED-shape feature models (embedding, wake word) stay on the standard
`tensorflow-lite:2.17.0` — they were never the problem. Verified in Python before pushing: the Kotlin math
matches the model to ~1.5e-5 and gives identical end-to-end detection (0.998 positive / 0.000 silence); the
`scripts/owwtest/run.py` CI check now runs that exact shipped math. `OpenWakeWord.lastLoadError` records the
real cause of any future load failure. Emulator load+detect guards it in CI. Device-only ceiling: real accents/noise.

### ⚠️ Read this first: the screen-control loop is NOT working on a device
Two device sessions (2026-08-04 Blinkit, 2026-08-05 Zepto) both ended with the errand
unfinished. The second was **worse than the first** — the user's words: *"it did anything it
liked, it just clicked sooo many random buttons… what trash have you built"*. That regression
is bounded now, but **nothing about the loop has been confirmed working on a device.** Do not
build on top of it until a trace says it behaves.

Three things are still open, in priority order:

1. **The typing fix is an unconfirmed diagnosis.** Blinkit's search screen had a focused field,
   a visible caret and the keyboard up, and `Type` still failed with "no editable field
   appeared". Two candidate causes were addressed — only `rootInActiveWindow` was searched (which
   can be the IME once the keyboard shows) and a node had to report `isEditable` (Blinkit
   evidently does not set it). Now every application window is searched, the IME is skipped, and
   anything accepting `ACTION_SET_TEXT` counts. **The failure path now logs what it saw**
   (`no field found — active=… windows=[…] focus=… editable=… setText=…`), so the next trace
   should name the cause outright. One line from the last trace already shows the diagnostic
   working: `active=com.jarvis.os windows=[w3/w3/app] no input focus` — JARVIS's own window was
   in front, which is a *different* bug worth chasing.
2. **The errand loop may still need reverting.** If the next trace still shows nonsense, the
   correct move is to **put plan-first execution back as the default** and let the loop handle
   only failures, which is what it was originally built for. This was offered to the user and
   they have not yet said. Do not defend the design further — the evidence is two bad sessions.
3. **Learned playbook routes on the user's device are poisoned.** Three were stored from runs
   that had failed into recovery — `"did you are"`, `"search box"`, `"the search box is right ya
   just type"`. The learning bug is fixed (`runSteps` now reports `ok` and `clean` separately and
   learning requires `clean`) but the bad entries already on the phone are not. They should be
   cleared, or a similar phrase will replay broken steps **without asking the model at all** and
   look exactly like the bug persisting.

### What the user asked for next: the backend
The user's last request was the **backend, login system, and per-user token tracking**, and then
to write everything down because they are starting a fresh session. The full implementation brief
is **[`COMMERCIALIZATION.md` §1d](COMMERCIALIZATION.md)** — Worker + D1 schema, the Firebase
Admin-SDK-does-not-run-on-Workers gotcha, the token-metering design, and a six-step build order.

**Start at step 1: the Cloudflare Worker with quota + token accounting against a fake provider.**
It is plain JavaScript and therefore **the first part of this project that can actually be tested
inside a Claude session** — every Android change so far could only be reasoned about and handed
to the user to try, which is how two bad device sessions happened. Use that.

Nothing exists yet: there is no `backend/` directory and no Firebase/Billing/Play dependency in
`app/build.gradle.kts`.

**Owed by the user before step 3:** a Cloudflare account, a Firebase project, and a decision on
the free-tier cap (recommended ~60k tokens/day on `llama-3.1-8b-instant`).

### Shipped and device-confirmed
Part A, the testing rig, **Part B** (work session), **Part C** (screen awareness, state memory,
send guard, `<<PICK>>`, `<<BACK>>`/`<<HOME>>`), the navigation restructure, learned memory,
custom instructions, alarms, the voice picker, **Part F — Files**, and **the six themes**.
Device-confirmed 2026-07-29: type vs send, no spoken thought process, chats below the fold, the
mic yielding while audio plays, `<<REMEMBER>>`, PDF creation, and `<<PICK>>` choosing "Beat It
Michael Jackson" from 15 real options.

### Also shipped this session, unconfirmed on device
- **Launcher icon** from the user's badge (adaptive, `minSdk 26` so `mipmap-anydpi-v26` is always
  used). **Gotcha: the assembly is NOT circular** — horizontal wing bars reach r≈460 in the source
  while the ring is r=390, so sizing from the vertical radius sliced them off flat and the user
  spotted it. **Measure the widest axis, never one radius.** Widest content is 64dp of the 108dp
  layer (≤132px against a 144px mask edge). Frame and wordmark deliberately absent — both sit
  outside the mask. Full badge kept at `store/ic_launcher-playstore.png` for the Play listing.
  `<monochrome>` layer ships for Android 13+.
- **Michroma is the display face** (OFL, `res/font/michroma.ttf`). **The badge's lettering is not
  a real typeface** — an AI render whose "A" has no crossbar, which essentially no text font does,
  so an exact match cannot be obtained. Michroma is **static, one weight**: never ask it for
  600/700 or the renderer synthesises a faux-bold that smears it. It is much wider than Orbitron,
  so every size came down and each was checked against "Custom instructions" at headlineSmall on a
  320dp screen. Orbitron is still bundled — reverting is one line.
- **`SpendGuard`** — a plan for "add some bread" ended in `Tap(Checkout)`. Truncates at the first
  irreversible tap unless the user's own words asked. **Strict where `AlarmGuard` is generous**,
  because here a loose reading is what *permits* the purchase.

### Known-missing capabilities
- **The agent cannot deliberately scroll.** Tapping a label scrolls to find it, but a product list
  cannot be browsed. Next feature once the loop is trusted.
- **No marker to open JARVIS's own artifact** ("show me the PDF you just made").
- **`<<OPEN|unknown>>` fails silently** — it should report failure like taps do.
- **No build stamp.** `versionCode 1`, `versionName "1.0"`, shown nowhere, so neither the user nor
  Claude can tell which build a device is running. Every shared trace is unidentified. Deriving it
  from the CI run number and showing it in Diagnostics was offered and never built. **This keeps
  costing real diagnostic time — build it early.**
- Flow charts for Files (specced), **Part G — Automation** (specced, not started).
- **The theme brief is bounded** — five rewrites established that 100% resemblance to a
  photorealistic render without shipping that render is not achievable. Do not attempt a sixth
  hand-drawn pass. The one untried option is `drawVertices` texture-mapping; offered, awaiting the
  user's call. Also owed by the user: a decision on a paid provider for image generation.

## Rules that keep being re-learned
- **Do not give an unproven change the whole critical path.** The agent loop had never once driven a real errand end to end, and every multi-step command was routed through it. The result was 18 useless taps per command on a stranger's shopping app. Ship a new mechanism behind the old one, or bounded, until a trace says it works.
- **A step budget does not buy correctness — it only bounds damage.** The loop's allowance was raised 10 → 18 reasoning that a real errand needs nine steps plus room for mistakes. That was simply permission for eighteen wrong taps. It is 8 now, and the test asserting `MAX_STEPS >= 14` was inverted to `<= 10` because it had encoded the mistake.
- **Success is not progress.** The loop's repeat guard only caught steps that FAILED. Almost all the thrashing was steps that SUCCEEDED and moved nothing — `Open(Zepto)` three times running, each logging "already in Zepto — not relaunching". Guard against *repetition*, not just failure, and detect a screen that has stopped changing.
- **Anything that loops needs a cancellation token.** `driveErrand` recursed through service callbacks with no token, so an abandoned errand kept choosing steps underneath the next command — the user asked a question at 15:48:32 and the old loop was still tapping at 15:48:46.
- **Diagnostic strings must never reach the user's ears.** "that step has already failed here" was written for the log and got spoken aloud. Internal reasons are now a named set that `blockedMessage` refuses to speak.
- **Measure the widest axis, not one radius.** The icon's assembly was sized from its vertical radius and treated as circular; its horizontal wing bars reach r≈460 against a ring of r=390, so they were sliced off flat and the user saw it on the home screen. A single max-radius figure cannot show that content is anisotropic — and I had reported that figure as "verified".
- **Rendering a preview is not the same as looking at the source.** The icon was inspected under three masks at three sizes and the clipping was still missed, because those previews showed a circular crop whose clipped edges were already gone. Overlaying candidate boundaries on the ORIGINAL is what made it obvious.
- **Do not name a cause you have not seen.** The bare `$` in `AlarmGuard` was a genuine hazard, was reported to the user as the fix, and fixed nothing — `compileDebugKotlin` had succeeded in every run, and the failure was in TEST sources all along. A plausible defect found while searching is not evidence that it is THE defect.
- **When the evidence is unreadable, fix that first.** Six red builds (#163–#169) went by while `--stacktrace` buried the `e:` lines under 120 lines of Gradle frames. Removing it turned three failed diagnoses into one successful fetch. Making the evidence legible is cheaper than guessing at it, and should have been the FIRST move, not the fourth. The failing task name (`compileDebugUnitTestKotlin`, not `compileDebugKotlin`) was on screen the whole time and would have narrowed it to test sources immediately.
- **Add a parameter where it is READ, not only where it is declared.** `systemOverride` was threaded into `GeminiClient.generate()`'s public signature, but the line consuming it was in a private helper two calls down — a main-source compile error that cost builds #179–#180. `GroqClient` had already solved the identical problem by passing it down each hop; the fix was to copy that, not to invent a second shape. This is the same lesson as the entry below, hit from the other direction: not "a fresh regex where a plain string would do", but "a fresh signature where an existing call chain already carried the value".
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
