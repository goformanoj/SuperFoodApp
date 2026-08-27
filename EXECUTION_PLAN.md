# JARVIS OS — Execution Plan (the build queue I follow)

> **Working rules live in [`CLAUDE.md`](CLAUDE.md)**, which loads automatically every session — including the non-negotiable rule to update these docs after every merge. A `Stop` hook enforces it.
>
> This is the ordered queue Claude works through **without needing new per-step instructions**.
> **Part B is go** (user's call: finish the features, then do the commercial foundation in Part E).
> Companion: [`PRODUCT_PLAN.md`](PRODUCT_PLAN.md) (why) · [`PROGRESS.md`](PROGRESS.md) (status) · [`COMMERCIALIZATION.md`](COMMERCIALIZATION.md) (Play Store + keys) · [`SESSION_HANDOFF.md`](SESSION_HANDOFF.md) (how to resume)

## The working loop (every change)
1. Develop on the **session branch** (Claude is assigned one per session; create it from the latest `main` if needed). **Never push straight to `main`.**
2. Make the smallest coherent change.
3. **Self-test the mechanical parts** (see checklist) and fix any error.
4. Commit as `Claude <noreply@anthropic.com>`; push with `-u origin <branch>`.
5. **Confirm green** = the `jarvis-debug-apk` artifact appears (the job-status API lags 2–5h; artifact is the reliable signal).
6. **Fast-forward `main` to the green commit; push `main`.** A green commit is never left unmerged.
7. Update [`PROGRESS.md`](PROGRESS.md) and append to [`JARVIS_MEMORY.md`](JARVIS_MEMORY.md) — **every time, not just at the end of a session.**
8. Hand to the user for **on-device reply review** (the one thing Claude can't test).

## Pre-handoff checklist (run before saying "ready")
- [ ] No secrets / model id in the diff: `grep -rniE "gsk_|AIza|claude-opus" app/src *.md` → clean.
- [ ] Unit tests updated and passing (CI runs `testDebugUnitTest` before the build; a failure means no artifact). Add a test with any new pure-Kotlin logic — do **not** go back to porting regexes to Python.
- [ ] No dangling references after refactors: `grep` for removed symbols.
- [ ] Manifest well-formed if touched: `python3 -c "import xml.dom.minidom; xml.dom.minidom.parse('app/src/main/AndroidManifest.xml')"`.
- [ ] Build green (artifact present).
- [ ] `main` fast-forwarded to the green commit and pushed.
- [ ] Docs updated: [`PROGRESS.md`](PROGRESS.md) + [`JARVIS_MEMORY.md`](JARVIS_MEMORY.md) (and the others if the plan changed).

## Ordered backlog

### Part A — multi-step commands + typing ✅ (done, build #76)
Ordered `ScreenStep` sequences (Open/Tap/Type/Enter) so one instruction can open→tap→type→search.

### Part B — continuous "work session" 🔨 (in progress)
- **Goal:** after JARVIS opens an app *from a command*, keep listening for follow-up commands while the user is in that app; stop on "thank you Jarvis".
- **Approach:** a foreground service (mic type) that listens for commands. It runs **only** after an app-opening command starts a "session"; a single-owner flag hands the mic between the in-app engine (when JARVIS is foreground) and the service (when it's backgrounded) so there is never more than one listener. "thank you Jarvis" (and app close without a command) ends the session. Learn from the earlier mic-conflict failure: exactly one mic owner, always.
- **Play-compliant from day one** (saves a rewrite in Part E): `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_MICROPHONE` permissions, `android:foregroundServiceType="microphone"`, and a persistent notification the user can see and stop.
- **Acceptance:** open app + command → follow-ups heard; open/close only → silent; "thank you jarvis" → stops and returns the mic.

### Part C — accuracy ⏸️
- **Goal:** stop the AI guessing labels; make taps land.
- **Approach:** summarize the current window's accessibility tree (visible text/labels) and inject it into the LLM context so it taps real on-screen text; after a tap, verify the window/content changed and retry once if not; when matches tie, ask a one-line disambiguation; add small per-app hints (WhatsApp/YouTube search entry points).
- **Privacy constraint (non-negotiable, see [`COMMERCIALIZATION.md`](COMMERCIALIZATION.md)):** screen text sent to a third-party LLM is a sensitive-data transfer — it needs explicit consent and **redaction of password / OTP / payment fields before anything leaves the device**.
- **Acceptance:** "open the chat with <name>" and "search <query>" land reliably in the common apps.

### Part F — Files (artifacts JARVIS makes) ⏸️
- **Goal:** "make a PDF of the important points", "draw a flow chart of this" → JARVIS produces the file, and it lands in the **Files** tab.
- **Feasible now, no new provider:**
  - **Text / Markdown** — trivial.
  - **PDF** — Android's own `android.graphics.pdf.PdfDocument` renders text to a real PDF on-device. No library, no network, no cost.
  - **Flow charts / diagrams** — the model emits a simple node/edge description; the app draws it to a `Canvas` and exports as PNG or into the PDF. Deterministic, and it cannot hallucinate a broken image.
- **Blocked on a decision — image generation.** Groq has **no image model**. Real image generation needs a second provider (OpenAI `gpt-image`, Google Imagen, Stability, Replicate), all of which cost per image and need another key. Until that is decided, "make me an image" must be answered honestly rather than faked.
- **Design:** a block marker, because a document body is multi-line and the existing single-line markers cannot carry it:
  ```
  <<FILE|pdf|Meeting notes>>
  # Heading
  - point one
  <<ENDFILE>>
  ```
- **Storage:** app-private `filesDir/artifacts` + a small JSON index (name, kind, created, size). Files tab lists them, opens via `FileProvider`, and shares. Deleting one deletes the file.
- **Acceptance:** "make a PDF of what we just discussed" → file appears in Files, opens in a PDF viewer, can be shared.

### Part G — Automation (paired devices) ⏸️
- **Goal:** pair another Android device (a tablet) with JARVIS, then drive it by voice from the phone. The Automation tab lists paired devices and their status.
- **How it can actually work:** the tablet runs the same app in **agent mode** with its own accessibility service. The phone sends it commands; the tablet executes them locally and reports back. Nothing else can drive another Android device without root or a PC — Android deliberately forbids one app controlling another device.
- **Transport options:**
  | Option | Works when | Cost | Notes |
  |---|---|---|---|
  | **Local network (same Wi-Fi)** | Both on one network | £0 | NSD/mDNS discovery + a small socket or HTTP server on the tablet. Simplest, no account needed. **Recommended first.** |
  | **Cloud relay** | Anywhere | Server cost | Needs the Part E backend and identity; the natural sequel once that exists. |
  | **Bluetooth** | In range | £0 | Fiddly pairing, short range, no real advantage over Wi-Fi. |
- **Security is the hard part, not the transport.** A device that accepts remote commands to tap and type is a remote-control channel: it needs an explicit pairing step (code shown on the tablet, entered on the phone), a shared secret kept after pairing, commands accepted only from a paired device, and a visible "being controlled" indicator on the tablet. Get this wrong and the feature is a vulnerability.
- **Dependency:** the marker protocol and executor already exist and are device-agnostic — the tablet can reuse `ScreenActions` + `ScreenControlService` unchanged. What is new is pairing, transport, and trust.
- **Acceptance:** pair a tablet; "on my tablet, open YouTube" runs there, not on the phone; the tablet shows it is being controlled; unpairing stops it.

### Part D — polish ⏸️
- Tap-to-talk toggle (always-on vs press-to-talk); clear idle transcript/reply; first-run permission onboarding (mic/calendar/accessibility incl. the Realme "Downloaded apps" path).

### Part E — commercialization ⏸️ **← NEXT, by the user's request (2026-08-05)**
**Ordered build, all phases, with what each is blocked on: [`BACKEND_PLAN.md`](BACKEND_PLAN.md).**
Architecture and gotchas (schema, endpoint flow, auth choice): **[`COMMERCIALIZATION.md` §1d](COMMERCIALIZATION.md)**.
**Phase 0 is startable with nothing from the user** — the Worker against a fake provider, fully
testable in a Claude session with `node --test` and zero dependencies.
Sequencing changed: the backend now comes **before** the remaining feature work, because the
user asked for it directly and because it is the only part of this project that can be tested
inside a Claude session rather than only on a device.

- **E0 — the Worker, in this order:** (1) `/chat` with quota + **token** accounting against a
  *fake* provider, with real unit tests; (2) the real Groq call; (3) Firebase ID-token
  verification (**the Admin SDK is Node-only and does NOT run on Workers** — verify the RS256
  JWT with WebCrypto against Google's x509 keys); (4) Android `ProxyClient` behind
  `Brain.generate()`; (5) Play Integrity; (6) Billing.
  **Meter tokens, not requests** — a screen-control turn costs many times an ordinary chat turn,
  so a request cap would be wildly unfair. Storage is **D1, not KV**: KV is eventually consistent
  and two concurrent turns would both read the stale total.
  Bonus worth taking early: **move the system prompt server-side**, so a prompt fix becomes a
  deploy instead of a new APK and a reinstall.
- **E1 — key security:** BYOK settings screen (`EncryptedSharedPreferences`) *after* the proxy;
  add Play Integrity / App Check attestation and server-side quota.
- **E2 — compliance cleanups:** drop `QUERY_ALL_PACKAGES` for a `<queries>` MAIN/LAUNCHER block; in-app accessibility disclosure + consent; Data safety inputs.
- **E3 — release engineering:** release keystore + Play App Signing, `release.yml` building an AAB, `isMinifyEnabled = true` with proguard keep rules, CI-derived `versionCode`.
- **E4 — name & identity gate:** final public name + `applicationId` (⚠️ immutable after the first publish; "JARVIS" is a Marvel trademark).
- **E5 — billing:** Play Billing / RevenueCat, free-tier daily cap enforced in the proxy.
- **E6 — launch:** internal → closed test (12 testers × 14 days) → production.

### Part H — JARVIS AI (our own tuned model) ⏸️ **parked 2026-08-26, nothing built**

Full plan: [`JARVIS_AI_PLAN.md`](JARVIS_AI_PLAN.md). A small model fine-tuned on
this app's own traffic, on device, for the marker/agent work — hosted model kept
for conversation and document writing. **A foundation model from scratch is not
in scope and never will be**; the plan says why in its first section.

- **Phase 0 — keep the data.** `DebugLog` is memory-only, capped at 300 entries,
  never written to disk, so every labelled example is deleted on restart.
  **Pure, small, gates off-device, and the only item here that gets more
  expensive by waiting.** Do this one regardless of the rest.
- **Phase 0b — manufacture the data** (synthetic screens, APK string resources,
  the emulator rig in `androidTest/e2e/`, distillation). No phone needed.
- **Phase 1 — prove it offline**, judged by the app's own parsers. **Hard gate:**
  if it does not beat `gpt-oss-20b` on marker accuracy, stop and ship nothing.
- **Phases 2–4** — on-device runtime, capability routing with escalation, and the
  commercial change. Only on the far side of Phase 1's number.

Independent of Part E: training does **not** wait for the backend.

### Later ⏸️
- Proper wake word (Porcupine / openWakeWord); streaming replies; device skills via real intents (alarms/timers, SMS/calls with confirm, wifi/torch/DND/media); vision (screenshot → model).
- **Call assistant** — appendix in [`JARVIS_AI_PLAN.md`](JARVIS_AI_PLAN.md); deferred to after the first Play Store launch.

## Prioritization principle
Reliable / API-backed features before brittle screen-poking. Keep a known-good baseline; when patches pile up and it's still broken, **reset** to the last working version instead of over-patching. Simulate the human (tap/swipe/type) when an app's "proper" interface is missing or lies.
