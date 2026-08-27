# JARVIS AI — our own model for our own app

> **ON HOLD — parked 2026-08-26, to be picked up next session.** Nothing has been
> built. This file is the plan and the evidence behind it, moved into the repo so
> it survives the session that produced it.
>
> Companion: [`EXECUTION_PLAN.md`](EXECUTION_PLAN.md) (the whole queue) ·
> [`BACKEND_PLAN.md`](BACKEND_PLAN.md) (Part E, which this does **not** depend on) ·
> [`COMMERCIALIZATION.md`](COMMERCIALIZATION.md) (option 7 is this, in one line) ·
> [`SESSION_HANDOFF.md`](SESSION_HANDOFF.md) (how to resume)

---


> The call-assistant plan that used to be this file is **retained unchanged at
> the bottom**, under "Appendix". It is a separate, still-deferred piece of work.

## Context

The ask: build "JARVIS AI" — our own model, with all the features, tuned for
this app, so we stop paying Groq and keep the margin.

One part of that is not achievable and the rest is, and the codebase already
contains the evidence for both.

**Not achievable: a foundation model from scratch.** A model in the class of the
`openai/gpt-oss-120b` the backend serves today was pretrained on trillions of
tokens across thousands of GPUs. That is a capital problem — millions of dollars
and a full-time team — not a scope problem. No plan makes it smaller.

**Achievable, and worth doing: a small model that is genuinely ours, fine-tuned
on this app's own traffic, running on the phone.** `ai/ModelRouter.kt` already
records the measurement that makes the case:

> *"Across three device traces llama-3.1-8b returned NO markers on a single
> command, so every command escalated to the smart model anyway… The small model
> cannot hold the marker protocol, and for a command the protocol IS the answer."*

That is a **zero-shot** failure on a **structured-output** task. It is the exact
failure fine-tuning removes: the marker protocol is a closed vocabulary of about
a dozen forms, and a 1.5B model trained on a few thousand real examples of it
will hold a format an untuned 8B cannot. The router's own comment invites this —
*"git history has them if the experiment is ever worth repeating against a
stronger small model."*

### What the money argument actually is

Cutting the Groq bill is not the win, because **the Groq bill is currently zero**
— free tier, no billing attached, per `COMMERCIALIZATION.md` option 1. There is
nothing to cut yet. The three real arguments, in order of commercial weight:

1. **Privacy, and it is a live Play Store liability.** `AssistantEngine.buildContext()`
   sends `ScreenControlService.describeScreen()` on **every turn** — the contents
   of whatever app is in front. WhatsApp messages, a bank balance, a class
   schedule, all transmitted to a third-party US API. That is a Data Safety
   declaration of *Messages / Personal info — shared with third parties*, on an
   app that already needs an accessibility service, which is among the hardest
   review categories there is. Running screen turns on-device **deletes that
   disclosure** and becomes a claim no competitor on a hosted API can make.
2. **Marginal cost that does not grow with users.** `backend/src/quota.js` sets
   `FREE_DAILY_TOKENS = 60_000` per free user per day. That is a rounding error
   at ten users and a real bill at ten thousand. On-device inference is $0 per
   user, forever, and the cost does not scale.
3. **Latency and offline.** `driveErrand` calls the model **once per step**.
   Every step is a network round trip today.

### The one thing that is urgent

`debug/DebugLog.kt` is **memory-only, capped at 300 entries, never written to
disk**. Every `HEARD → REPLY → MARKS → SCREEN` sequence is a perfectly labelled
training example — with the outcome attached, which is better than any scraped
dataset — and the app throws all of it away on every restart.

**You cannot fine-tune on data you deleted.** Phase 0 costs nothing, changes no
user-visible behaviour, and every day it is not done is a day of training data
gone. It should ship whether or not the rest of this plan ever does.

---

## How it knows about the phone (it doesn't — and it must not)

The obvious worry is that a model answers from its training data, so it cannot
know what is on *this* phone. Correct — and the app is already built so that it
never needs to. **The phone is handed to the model fresh on every turn.**

`ScreenControlService.renderScreen()` produces this, and `buildContext()` injects
it into every request:

```
App: com.whatsapp. On screen: [Chats] [Mom] [Dad] field:"Message" [Send]
```

`[X]` is tappable, `field:"X"` is an editable box, bare text is just text. The
model is not recalling what WhatsApp looks like — it is **choosing from a menu it
was just given**, and `SYSTEM_PROMPT` says so outright: *"those are the REAL
labels: use one exactly, never invent one."*

Where it *did* answer from memory, that was already caught and fixed
structurally. From the comment in `executeScreen`: a Blinkit trace kept tapping a
control called `Search` in an app whose search box is labelled *"Search for atta,
dal, coke and more"*. The fix was not a better model — it was `driveErrand`:
**stop planning the sequence up front, look before every move.**

### So fine-tuning changes format, not knowledge

The tuned model's job is: *given this screen listing and this goal, emit one
valid marker.* That is **translation, not recall**. Small models are good at
translation and bad at recall — which is exactly the right shape here, and
exactly why the untuned 8B failed. It was not missing knowledge; it was failing
to produce the format.

It also means the on-device model can afford to be small: **world knowledge stays
in the hosted model**, where conversation and document writing live.

### Three guards already enforce accuracy, and none care where the move came from

They police a local model exactly as they police Groq today — no new safety work:

| Guard | What it does |
|---|---|
| `describeScreen()` | the live screen every turn, so labels are real rather than remembered |
| `AgentLoop.parseMove` | rejects `LEFT_APP`, `JUST_ARRIVED`, `ALREADY_FAILED`, `GOING_IN_CIRCLES`, and "done" claimed while still acting |
| `PlaybookStore` | routes that demonstrably worked, replayed rather than re-derived |

### And the phone-specific knowledge comes from the traces

A model trained on the internet has never seen the PW app. A model trained on
**your** traces has seen little else: what PW's schedule screen looks like, which
label WhatsApp uses for send, which sequences worked and which were abandoned.
That is the asset Phase 0 protects — and the one currently being deleted on every
restart.

### Which makes accuracy a number, not a hope

The parsers are the judge. The Phase 1 eval is mechanical: of N held-out turns,
how many produce a move `AgentLoop.parseMove` accepts **and** that matches the
step which actually worked on the device. If a local move is wrong anyway,
Phase 3's escalation means it costs a round trip, never a turn.

---

## Shape: hybrid, not replacement

Two models, split by **what kind of output is wanted** — not by "easy vs hard",
which is the split `ModelRouter` already tried and measured as a failure.

| | Runs where | Handles | Share of calls |
|---|---|---|---|
| **JARVIS-1** (ours, tuned) | on device | agent steps, `chooseIndex`, marker emission for commands, the follow-up screen reader | the majority |
| **Hosted** (Groq today) | backend proxy | conversation, explanation, `<<FILE>>` document writing | the minority |

A 1.5B model will not write a good PDF or explain a topic, and it should not be
asked to. **"All the features" is the wrong target for the tuned model** — narrow
and excellent beats broad and mediocre, and the hosted path already exists for
the rest.

This is the same split Apple ships: small on-device model for the common path,
large hosted model for the rest.

---

## Phases

### Phase 0 — Start keeping the data (do this first; free; nothing else depends on anything else)

| File | What |
|---|---|
| `app/.../debug/TrainingLog.kt` | new. Durable JSONL to app-private storage, off `DebugLog`'s existing call sites |
| `app/.../debug/Redact.kt` | new. Screen-content redaction — far beyond `DebugLog.redact`, which only handles API keys |
| `app/.../ui/settings/` | a real opt-in toggle, default **off**, stating plainly what is kept |
| `app/src/test/.../RedactTest.kt` | phone numbers, OTPs, card numbers, addresses never survive redaction |

Each record: the assembled context, the utterance, the raw reply, the parsed
markers, **and the outcome** — `runSteps`'s `(ok, ranClean)`, whether recovery
fired, whether `Playbook.learn` stored the route, and whether the user
immediately rephrased (a negative label). That outcome is the reward signal and
it is why this dataset is worth more than anything scraped.

`PlaybookStore` is already a curated set of known-good request→marker pairs. It
is a seed dataset that exists today, for free.

**Privacy is not optional here.** This is the user's screen. Opt-in, default off,
on-device only, no upload without a second explicit consent. Getting this wrong
is worse than never building the model.

### Phase 0b — Manufacture the data, without touching a phone

Most of the training set should **not** come from the user's thumb. Four sources,
in order of volume:

| Source | Cost | Teaches |
|---|---|---|
| **Synthetic screens** | free, unlimited | the format and the discipline |
| **Real label vocabulary** (static APK string-resource extraction) | free | that real labels are long and awkward |
| **CI emulator harvest** (the rig already exists) | CI minutes | real screen structure, at scale |
| **Distillation** from `gpt-oss-120b` | Groq tokens, **once** | the teacher's judgement, in the student |

**Synthetic is the biggest and the most underrated.** `renderScreen` emits a
tiny, strict grammar — `App: X. On screen: [a] [b] field:"c" d` — so screen+goal
pairs can be generated programmatically **with their correct marker known by
construction**, and `ScreenActions.parse` / `AgentLoop.parseMove` verify every
one automatically. No human labelling anywhere in the loop.

**The emulator rig is already built.** `app/src/androidTest/.../e2e/A11yProbeTest.kt`
and `FixtureActivity.java` boot an API-34 emulator via
`reactivecircus/android-emulator-runner`, enable the accessibility service and
probe taps — that is exactly the harness needed to install an app, walk it, and
dump `describeScreen()` at each step, on every push, with no phone involved.

**Distillation costs tokens once, not per user.** Run the big model over the
synthetic and harvested screens; keep only outputs that parse cleanly *and* match
the known answer. That is the standard route for teaching a small model a
structured format.

**Real traces (Phase 0) remain necessary for a different reason.** Synthetic data
teaches the **format**; real traces teach the **surprises**. The Blinkit label
*"Search for atta, dal, coke and more"* is precisely the case no generator would
have invented. Volume from synthesis, corrections from reality.

**Caveats, stated plainly:**
- Automating logins to Facebook/WhatsApp at scale breaches their terms. Use own
  accounts on an emulator, or static resource extraction — which reads a file
  rather than operating an account.
- Many apps detect and refuse emulators (banking especially). Assume nothing.
- Emulator screens differ from real devices in density and OEM skin. The emulator
  gives volume; the device confirms.
- **None of this waits for the backend.** Data collection and training are
  independent of the Cloudflare Worker; the backend only changes where the
  *hosted* model is called from.

### Phase 1 — Prove it offline, before any Android work

No app changes. Fine-tune with LoRA on a rented GPU; evaluate with **the app's
own parsers as the judge** — `ScreenActions.parse`, `AgentLoop.parseMove`,
`Markers.strip`. The question is not "does the output look right", it is "does it
parse into the correct steps".

**Base model: pick an Apache-2.0 one** (Qwen 2.5 1.5B Instruct or similar).
Llama's licence carries attribution and MAU conditions and Gemma has its own
terms; for a paid Play Store app, Apache 2.0 is the clean choice. This decision
is hard to reverse later, so it is made here.

**Gate — and it is a real one:** if the tuned model does not beat
`openai/gpt-oss-20b` on marker accuracy against held-out traces from this app,
**stop and ship nothing**. The Android work below is only worth doing on the
other side of that number.

Cost: hours of GPU rental, order of $50–200. Rates change; check on the day.

### Phase 2 — On-device runtime

The hard constraint: a 1.5B model at 4-bit is **roughly 1GB**. The app currently
bundles 3.6MB of TFLite. It cannot go in the APK — Play's base-module limit is
far below that. So: **Play Asset Delivery (on-demand) or CDN download on first
run**, with the hosted path serving until it arrives, and serving forever on
devices that cannot run it. `minSdk = 26` reaches phones with 2GB of RAM that
never will.

Runtime: MediaPipe LLM Inference API is the least work; llama.cpp via JNI is the
most control. Either way, **heed the scar in `app/build.gradle.kts:119`** — a
dynamic-shape TFLite graph crashed on *every* Android runtime tried and had to be
reimplemented in Kotlin. Prove the chosen runtime on the CI emulator before the
device, and prefer the one with proven shape handling.

### Phase 3 — Route by capability, with automatic escalation

`ModelRouter.tierFor` returns a constant today. This is where routing returns,
on the new axis. The fallback is the important half: **if the on-device model
emits something the parsers reject, escalate to hosted automatically** — the same
shape as `GroqClient`'s existing `retired` / `blockedUntil` chain. A local model
that fails must cost a round trip, never a turn.

`Brain.generate()` is already one interface over providers, so this arrives as a
third client with no call-site churn — exactly as `COMMERCIALIZATION.md`
predicted for the proxy.

### Phase 4 — The commercial change

The paid tier stops being "more tokens" and becomes "the big model for real
conversation", with the assistant's core working free, offline, and privately for
everyone. That is an easier sell than a quota, and the Data Safety change is
worth more at review time than the token saving is on the balance sheet.

Up-front cost is real and should be stated: GPU time per training run,
engineering time for the runtime and delivery, and **retraining is recurring** —
the model is only as current as the traces it last saw.

---

## Verification

- Phase 0: `gradle -p scripts/jvmcheck test` — redaction and the record format are
  pure and gate off-device in seconds.
- Phase 1: held-out accuracy measured **through the app's own parsers**, reported
  against `gpt-oss-20b` on the same set. One number decides whether Phase 2 starts.
- Phase 2: CI emulator first, per the TFLite scar. Device confirmation by the
  user, as always.
- Phase 3: a trace showing a local step, a rejected local step, and its escalation
  to hosted — all three in one session.

## Open

1. **Base model licence** — recommendation: Apache 2.0. Decided in Phase 1, hard
   to reverse after.
2. **Delivery** — Play Asset Delivery vs CDN. Deferred to Phase 2; both keep the
   model out of the APK.
3. **Whether the eval gate is passed at all.** Genuinely unknown until Phase 1
   runs, and the plan is built so that finding out is cheap.

---
---

# Appendix — JARVIS as a call assistant (separate, still deferred)

## Context

The ask: JARVIS places calls and takes them. *"Call the nearest Domino's"*,
*"call mom and tell her I'll be late"* — talk back and forth, bring the result
back. Two follow-up decisions shape this plan: **it ships after the first Play
Store launch**, and **it has to be as close to free as possible**.

Both change the answer, so this is not the earlier plan with the phases
reordered. The cost question in particular has an answer that removes most of
the feature's expense by not making most of the calls.

---

## Can it be free? Partly — and the free part covers most real requests

Three meters run on a real phone call, and they are not alike:

| Cost | Free? | Why |
|---|---|---|
| **The number** | No | A PSTN number is rented monthly from a carrier. There is no permanent free tier anywhere. |
| **The minutes** | No | Carriers bill per minute in both directions. Calling an Indian mobile costs real money every time. |
| **The AI** (STT + LLM + TTS) | **Nearly** | Groq already runs this app's LLM on a free tier. Whisper on Groq is cheap. Google and Azure both give large free monthly TTS quotas; Piper self-hosts for nothing. |

So the intelligence is close to free and **the phone line is not**. No
architecture avoids that — it is a carrier charge, not a software one.

### The lever that actually matters: don't call when you can tap

JARVIS already drives apps on the phone through `ScreenControlService`. For a
large share of what someone would ask a call assistant to do, **there is a free
path that already works**:

| Request | Free path that exists today | Needs a real call? |
|---|---|---|
| "Order a pizza from Domino's" | Open the Domino's app and order | **No** |
| "Tell mom I'll be late" | Send it on WhatsApp | **No** |
| "Book a table at that restaurant" | Their app, or Zomato/Swiggy | Usually no |
| "Ask the clinic if Dr X is in on Saturday" | — | **Yes** |
| "Call the local hardware shop about stock" | — | **Yes** |
| Anything with only a landline | — | **Yes** |

A call is needed when the other party has **no digital surface** — a small shop,
a clinic, a landline. That is a genuinely useful feature and a much smaller one
than "JARVIS makes calls".

**Design consequence:** the router prefers the free path and only falls through
to a paid call when there is no app for it. Cheapest call is the one not placed,
and this also makes the feature *faster* — driving the Domino's app beats
sitting through their IVR.

### Making the calls that remain as cheap as possible

Three options, and the choice can be deferred because they sit behind one
interface:

**a. BYO telephony key** — the user brings their own Twilio/Exotel account, the
way the app already handles a Groq key. Cost to us: zero. Cost to them: their
own metered bill, which is the cheapest possible rate with no margin on top.
This fits the existing BYOK architecture exactly and is the right v1.

**b. Cheapest assembled stack** — Twilio/Exotel for the line, Groq Whisper for
STT, Groq for the LLM, Google/Azure free-tier TTS. Lowest per-minute cost by a
wide margin. The catch is real: you build turn-taking, barge-in and endpointing
yourself, over a WebSocket media stream, in a Durable Object. That is weeks of
the hardest kind of real-time work.

**c. ElevenLabs Agents** — roughly ten times the per-minute cost for roughly a
tenth of the work, because turn-taking, DTMF for IVR menus, voicemail detection
and transcripts all come built in. Their workspace is already connected (checked
— it is empty, so nothing is set up).

**Recommendation: (a) for v1, structured so (b) or (c) can be swapped in.**
`backend/src/providers/` already does exactly this for Groq — `groq.js` and
`fake.js` behind one interface. Telephony gets the same treatment.

### Free for development, regardless

Twilio's trial credit calls *verified* numbers only, with a spoken trial
preamble — useless for calling Domino's, perfectly good for building. And 95% of
this feature can be built and tested against a **fake provider** with no
telephony at all, exactly as `providers/fake.js` does for Groq today.

> Rates change and I have not verified current ones. Do not budget from anything
> in this file — check ElevenLabs, Twilio and Exotel's Indian rates on the day.

---

## The constraint that decides the architecture

**An Android app cannot touch the audio of a carrier call.** Since Android 10,
`VOICE_CALL` / `VOICE_DOWNLINK` / `VOICE_UPLINK` need `CAPTURE_AUDIO_OUTPUT`,
which is `signature|privileged` — system and carrier apps only. No Play Store
path exists. `ANSWER_PHONE_CALLS` can accept or reject a call and an
`InCallService` can control call state, but neither gives the audio; a
self-managed `ConnectionService` gives audio only for VoIP calls the app itself
hosts.

So the call happens **in the cloud**. JARVIS gets a number, the server places and
answers, the app is a remote control and a results inbox. Any on-device attempt
burns weeks and then hits a wall.

---

## Why after launch is the right call

Not just sequencing — three of these are real risks to the launch itself:

1. **Review scrutiny.** An app that autonomously places calls invites a harder
   first review. Attaching that to the launch build risks delaying the launch.
2. **`READ_CONTACTS` is a sensitive permission** needing its own declaration and
   justification. A first submission is the worst time to add one.
3. **Per-minute cost before there is revenue** is the wrong order.
4. The backend is not live yet — Phase 1 is still parked on Cloudflare
   Connect-to-Git, the Worker secrets and `POST /admin/migrate`.

---

## Safety, in code rather than prompt

CLAUDE.md Rule 6 exists because the prompt taught the model that typing and
sending were one move, and it sent a message the user only asked to type.
`SendGuard` and `Confirmation` were the fix. **A call is worse than a message**:
real-time, reaches a third party who never agreed to talk to software, and
ordering commits money.

1. **`CallGuard`** — same shape as `Confirmation`. "Call mom" is a *proposal*.
   Nothing dials without a confirmation naming the resolved number.
2. **The model never supplies a number.** It emits `<<CALL|Mom|brief>>`; the
   *system* resolves the contact. A hallucinated number that gets dialled calls a
   stranger. No match means asking, never guessing.
3. **Money needs a second confirmation.** v1 asks and reports back; placing an
   order is a fresh approval. `SpendGuard` already exists.
4. **Disclosure, no impersonation.** "Calling on behalf of Manoj", never "this is
   Manoj". Partly decency, partly that people hang up on suspected scams. Naming
   it plainly: the transcript records someone who did not consent to being
   recorded, and two-party-consent norms apply even where no statute forces them.
5. **Kill switch** — end a live call from the app at any moment.

---

## Phases

### Before launch — free, and worth doing now

Only one phase, and it costs nothing: no accounts, no permissions, no manifest
change, nothing user-visible, nothing for a reviewer to object to.

**Phase A — the pure logic.**

| File | What |
|---|---|
| `app/.../assistant/CallGuard.kt` | new. Propose vs dial; what a confirmation authorises |
| `app/.../assistant/CallRequest.kt` | new. `CallRequest`, `CallOutcome`, `CallStatus` |
| `app/.../assistant/CallRouter.kt` | new. **The cost lever**: app-path vs call-path |
| `app/src/test/.../CallGuardTest.kt` | bare "call mom" never dials; a confirmation authorises *that* number and brief, not a re-planned one; ambiguous contacts refuse; no-target utterances cannot call |
| `app/src/test/.../CallRouterTest.kt` | anything with a known app routes to the free path |

Reuses `Confirmation.answerFor`, `Negation`, `SpendGuard`, `AppAliases` and the
`pendingConfirm` mechanism in `AssistantEngine.ask()`. Added to jvmcheck's pure
list so it runs in seconds here rather than in CI.

Deliberately **not** in Phase A: the `<<CALL>>` marker in `SystemPrompt.kt`. Ship
the logic dormant; wiring the marker is a one-line change later. Nothing in the
launch build can place a call.

### After launch

| Phase | What | Blocked on |
|---|---|---|
| **B** | Backend call endpoints against a **fake** telephony provider: `calls.js` (pure state machine), `/call`, `/call/:id`, `/call/:id/end`, `/webhook/call-ended`, a `calls` table, tests for over-cap-never-dials and duplicate-webhook-never-double-charges | nothing — free |
| **C** | Real telephony behind the same interface; BYO key first | Cloudflare Phase 1, a number |
| **D** | Calls screen: live status, transcript, result card. The confirmation card shows who, the **resolved number**, and the brief **verbatim** | C |
| **E** | Inbound. The agent answers JARVIS's number; covering the user's own number is **carrier** call forwarding (`**61*<number>#`), which the app can prefill but not perform | D |
| **F** | IVR/DTMF, voicemail, daily caps, number allow-listing, abuse controls | E |

---

## Verification

- Phase A runs fully off-device: `gradle -p scripts/jvmcheck test`.
- Phase B: `cd backend && npm test`. Both already gate in CI.
- Phase C's smoke test is one outbound call to the user's own verified second
  number — free on a Twilio trial — saying one sentence and hanging up.
- D and E are device-only, as always.

## Open, and only needed at Phase C

1. **BYO key or we pay?** Recommendation: BYO for v1. Zero cost, cheapest rate,
   and it matches how the Groq key already works.
2. **Country and provider.** Exotel is the Indian option; a US Twilio number
   calling an Indian mobile reads as spam and costs more.
3. **How far the agent may commit.** Recommendation: v1 asks and reports; an
   actual order needs a second approval in the app.
