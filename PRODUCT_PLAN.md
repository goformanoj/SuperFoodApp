# JARVIS OS — Product Plan (north star)

> The durable vision and spec. Changes only when the vision changes.
> Companion docs: [`PROGRESS.md`](PROGRESS.md) · [`EXECUTION_PLAN.md`](EXECUTION_PLAN.md) · [`SESSION_HANDOFF.md`](SESSION_HANDOFF.md) · detailed log in [`JARVIS_MEMORY.md`](JARVIS_MEMORY.md)

## Vision
A native Android AI voice assistant — **JARVIS OS** — built **entirely from a phone, with no PC**. Claude writes the code; **GitHub Actions compiles it**; the user downloads the resulting `jarvis-debug-apk` and installs it. It should talk naturally, remember context, manage the real device calendar, and control the phone screen on command.

## Non-negotiable constraints
- **API keys are injected at build time via a GitHub Actions secret — NEVER committed** to the repo.
- Commits are authored as **`Claude <noreply@anthropic.com>`**.
- The model identifier is **never** placed in commits, PRs, code comments, or any pushed artifact.
- **Never route around** the organization egress proxy / network policy.
- Build in **small steps, one green CI build at a time**; give complete file contents when needed.

## API-key security reality (read this)
- The key **never enters the source code** — browsing the public repo or the build logs will not reveal it. ✅
- **But** the key is compiled into `BuildConfig.GROQ_API_KEY` and therefore **embedded inside the APK**. Because the repo is **public**, the APK artifact is downloadable, and anyone can extract the key from it with a free decompiler (jadx/apktool). **Treat the key as effectively public.**
- It only unlocks Groq (an LLM API) — nothing else (no phone/files/calendar/GitHub access). Worst case: someone abuses your Groq quota, or runs up a bill **only if a payment method is attached**.
- **Mitigations, in order:**
  1. Keep the Groq account on **free tier with NO payment method** → removes all financial risk (worst case is quota abuse). ← recommended
  2. **Rotate** the key if abused: regenerate in Groq → update the GitHub secret → next build re-embeds the new one; the old key dies.
  3. Optionally make the **repo private** (free GitHub accounts get ~2,000 Actions min/month) → artifacts are no longer publicly downloadable.
  4. Proper long-term fix (out of scope for a personal app): a backend proxy holds the key; the app calls your server, not Groq directly.

## Locked tech stack
- Kotlin · Jetpack Compose · Material 3
- AGP (built-in Kotlin) + `org.jetbrains.kotlin.plugin.compose` · Gradle **9.3.1** · JDK **17**
- `compileSdk`/`targetSdk` **36**, `minSdk` **26**
- `applicationId` = **`com.jarvis.os`**

## Design language
- Dark, matte-black, futuristic "OS" feel.
- Colors: background `#050B18`, cyan `#00D4FF` (primary), electric blue `#0066FF` (secondary).
- Fonts: **Orbitron** (headings) + **Inter** (body), bundled variable fonts.
- Animated **HUD orb** at center, color/motion driven by state (Idle/Listening/Thinking/Speaking/Error).
- Vector adaptive launcher icon (the orb) — no PNGs (minSdk 26).

## Brain
- **Groq** free tier is primary (`llama-3.3-70b-versatile` → `llama-3.1-8b-instant` → `gemma2-9b-it`); **Gemini** is the fallback client.
- **Conversational-first:** JARVIS thinks and talks like a capable, knowledgeable assistant; the phone-control and calendar abilities are **tools it reaches for when the user wants an action** — not a cage. Answer, explain, reason, chat.
- Honest about limits; never claims to have done something it didn't; **natural, varied replies** (no robotic "is there anything else?").

## Feature spec (the full wishlist)

### Voice I/O
- Always-on loop: **listen → think → speak → listen** (no wake word currently; a proper always-on wake word is a future upgrade).
- **Speak-then-act:** finish the spoken reply before switching apps.
- Earcon (mic beep) muted during listening so continuous listening is silent.

### Conversation
- Persisted conversation **memory/context**; smart, natural, not over-restricted.

### Calendar
- Read the **real device calendar**; add / delete / reschedule events conversationally, **with confirmation** and clarifying questions.

### Screen control (accessibility)
- **Open apps** by name.
- **Tap** on-screen controls, with a glowing cyan **outline** on the target.
- **Scroll** (via a real swipe gesture) to find targets that are below the fold.
- **Type** into fields and **submit / search** (enter).
- **Multi-step chained commands** in one instruction, e.g. *"show me a standup comedy video"* → open YouTube → tap Search → type "standup comedy" → enter. Understand compound instructions directly.

### Continuous "work session" (planned — Part B)
- After the user **opens an app AND gives a command**, keep **listening in the background** for follow-up commands while they're in the other app.
- **Stop** the background listening when the user says **"thank you Jarvis."**
- Do **NOT** background-listen if the user only opens/closes JARVIS without giving a command.
- Exactly **one microphone owner** at any moment (no repeat of the earlier mic conflict).

### Accuracy goals (planned — Part C)
- Feed the AI the **actual on-screen text** (accessibility tree) so it taps what's really there instead of guessing labels.
- **Verify** a tap changed the screen and retry if not.
- **Disambiguate** when two targets tie (ask which), instead of guessing.
- Small **per-app hints** for common flows (WhatsApp/YouTube search).

### Install / distribution
- **Fixed committed debug signing key** so every build shares one signature and updates install cleanly over each other.
- Guidance for **Google Play Protect** and the **Realme/ColorOS accessibility path**.

### Polish (planned — Part D)
- Tap-to-talk toggle (choose always-on vs press-to-talk).
- Clear on-screen transcript/reply when idle.
- First-run **permission onboarding** (mic / calendar / accessibility).

## Working method
Small steps, one green CI build at a time. **Claude tests the mechanical correctness (parsing/logic + compile) and confirms the build is green before handing off; the user does the final review of reply quality on-device.** Claude never commits secrets and commits as Claude.
