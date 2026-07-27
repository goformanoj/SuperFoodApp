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

### Part D — polish ⏸️
- Tap-to-talk toggle (always-on vs press-to-talk); clear idle transcript/reply; first-run permission onboarding (mic/calendar/accessibility incl. the Realme "Downloaded apps" path).

### Part E — commercialization ⏸️ (full detail in [`COMMERCIALIZATION.md`](COMMERCIALIZATION.md))
Ordered phases, after the features land:
- **E1 — key security:** BYOK settings screen (`EncryptedSharedPreferences`), then the backend proxy (Cloudflare Worker) as a third client behind `Brain.generate()`; add Play Integrity / App Check attestation and server-side quota.
- **E2 — compliance cleanups:** drop `QUERY_ALL_PACKAGES` for a `<queries>` MAIN/LAUNCHER block; in-app accessibility disclosure + consent; Data safety inputs.
- **E3 — release engineering:** release keystore + Play App Signing, `release.yml` building an AAB, `isMinifyEnabled = true` with proguard keep rules, CI-derived `versionCode`.
- **E4 — name & identity gate:** final public name + `applicationId` (⚠️ immutable after the first publish; "JARVIS" is a Marvel trademark).
- **E5 — billing:** Play Billing / RevenueCat, free-tier daily cap enforced in the proxy.
- **E6 — launch:** internal → closed test (12 testers × 14 days) → production.

### Later ⏸️
- Proper wake word (Porcupine / openWakeWord); streaming replies; device skills via real intents (alarms/timers, SMS/calls with confirm, wifi/torch/DND/media); vision (screenshot → model).

## Prioritization principle
Reliable / API-backed features before brittle screen-poking. Keep a known-good baseline; when patches pile up and it's still broken, **reset** to the last working version instead of over-patching. Simulate the human (tap/swipe/type) when an app's "proper" interface is missing or lies.
