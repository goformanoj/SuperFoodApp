# JARVIS OS — Execution Plan (the build queue I follow)

> This is the ordered queue Claude works through **without needing new per-step instructions**.
> Default order below; **currently paused before Part B awaiting the user's "go."** Once told to continue, proceed down the list.
> Companion: [`PRODUCT_PLAN.md`](PRODUCT_PLAN.md) (why) · [`PROGRESS.md`](PROGRESS.md) (status) · [`SESSION_HANDOFF.md`](SESSION_HANDOFF.md) (how to resume)

## The working loop (every change)
1. Develop on branch **`claude/jarvis-minimal-build-4jwvo1`** (create from latest `main` if needed).
2. Make the smallest coherent change.
3. **Self-test the mechanical parts** (see checklist) and fix any error.
4. Commit as `Claude <noreply@anthropic.com>`; push with `-u origin <branch>`.
5. **Confirm green** = the `jarvis-debug-apk` artifact appears (the job-status API lags 2–5h; artifact is the reliable signal).
6. Fast-forward `main` to the green commit; push `main`.
7. Update [`PROGRESS.md`](PROGRESS.md) and append to [`JARVIS_MEMORY.md`](JARVIS_MEMORY.md).
8. Hand to the user for **on-device reply review** (the one thing Claude can't test).

## Pre-handoff checklist (run before saying "ready")
- [ ] No secrets / model id in the diff: `grep -rniE "gsk_|AIza|claude-opus" app/src *.md` → clean.
- [ ] Marker parsers still correct: port the `MARKER` (`ScreenActions`) + `CAL` (`CalendarActions`) regexes to Python and run sample cases (open/tap/type/enter chain, calendar add/del, pure chat, case-insensitive).
- [ ] No dangling references after refactors: `grep` for removed symbols.
- [ ] Manifest well-formed if touched: `python3 -c "import xml.dom.minidom; xml.dom.minidom.parse('app/src/main/AndroidManifest.xml')"`.
- [ ] Build green (artifact present).

## Ordered backlog

### Part A — multi-step commands + typing ✅ (done, build #76)
Ordered `ScreenStep` sequences (Open/Tap/Type/Enter) so one instruction can open→tap→type→search.

### Part B — continuous "work session" ⏸️ (next)
- **Goal:** after JARVIS opens an app *from a command*, keep listening for follow-up commands while the user is in that app; stop on "thank you Jarvis".
- **Approach:** a foreground service (mic type) that listens for commands. It runs **only** after an app-opening command starts a "session"; a single-owner flag hands the mic between the in-app engine (when JARVIS is foreground) and the service (when it's backgrounded) so there is never more than one listener. "thank you Jarvis" (and app close without a command) ends the session. Learn from the earlier mic-conflict failure: exactly one mic owner, always.
- **Acceptance:** open app + command → follow-ups heard; open/close only → silent; "thank you jarvis" → stops and returns the mic.

### Part C — accuracy ⏸️
- **Goal:** stop the AI guessing labels; make taps land.
- **Approach:** summarize the current window's accessibility tree (visible text/labels) and inject it into the LLM context so it taps real on-screen text; after a tap, verify the window/content changed and retry once if not; when matches tie, ask a one-line disambiguation; add small per-app hints (WhatsApp/YouTube search entry points).
- **Acceptance:** "open the chat with <name>" and "search <query>" land reliably in the common apps.

### Part D — polish ⏸️
- Tap-to-talk toggle (always-on vs press-to-talk); clear idle transcript/reply; first-run permission onboarding (mic/calendar/accessibility incl. the Realme "Downloaded apps" path).

### Later ⏸️
- Proper wake word (Porcupine / openWakeWord); streaming replies; device skills via real intents (alarms/timers, SMS/calls with confirm, wifi/torch/DND/media); vision (screenshot → model).

## Prioritization principle
Reliable / API-backed features before brittle screen-poking. Keep a known-good baseline; when patches pile up and it's still broken, **reset** to the last working version instead of over-patching. Simulate the human (tap/swipe/type) when an app's "proper" interface is missing or lies.
