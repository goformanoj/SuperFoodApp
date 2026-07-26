# JARVIS OS — Build Memory

A running log Claude maintains and updates **after every prompt**. It captures
what was asked, what changed, and key decisions — so nothing is lost between
sessions (the build container is ephemeral).

## Project facts
- **Stack:** Kotlin, Jetpack Compose, Material 3
- **Toolchain:** AGP 9.1.0 (built-in Kotlin, no separate kotlin-android plugin),
  Compose compiler 2.2.10, Gradle **9.3.1** (AGP 9.1.0's minimum), JDK 17
- **SDK:** compileSdk/targetSdk 36, minSdk 26 · **applicationId** `com.jarvis.os`
- **Build:** GitHub Actions `.github/workflows/build.yml` → artifact `jarvis-debug-apk`.
  Builds on every push (repo is public → free CI minutes).
- **Branches:** develop on `claude/jarvis-minimal-build-4jwvo1`, mirrored to `main`
  (workflow_dispatch needs the workflow on the default branch).
- **Secrets:** never committed. `GEMINI_API_KEY` is a GitHub Actions secret,
  injected at build time into `BuildConfig.GEMINI_API_KEY`. `.gitignore` blocks
  `local.properties`, `secrets.properties`, `gemini.properties`, `*.env`,
  `google-services.json`, keystores.

## Architecture (current)
- `MainActivity` — hosts `JarvisTheme` + `VoiceHome`; drives `AssistantEngine`
  from the Activity lifecycle and requests `RECORD_AUDIO`.
- `assistant/AssistantEngine` — orchestrates the loop: listen → think → speak →
  listen. Owns the single `VoiceUiState` the UI observes.
- `voice/VoiceController` — callback wrapper over Android `SpeechRecognizer`.
- `voice/Speaker` — Android `TextToSpeech` wrapper.
- `ai/GeminiClient` — minimal Gemini REST client (no SDK), key from BuildConfig.
- `ui/home/HomeScreen` (`VoiceHome`) — menu drawer (top-left) with module
  destinations, centered `HudOrb`, scrollable schedule below.
- `ui/components/HudOrb` — procedural JARVIS HUD orb (rings, ticks, orange arc),
  reacts to mic amplitude.
- `ui/theme/*` — colors, Orbitron/Inter typography, dark theme.

## Log

### 2026-07-26 — Step 1: minimal buildable app
Created the project from an empty repo: Gradle files, wrapper, workflow, a
Compose `MainActivity` showing "JARVIS". First green build after bumping Gradle
to 9.3.1 (AGP 9.1.0's required minimum).

### 2026-07-26 — Step 2: Home screen
Added theme (Orbitron/Inter bundled fonts), a breathing orb, and a home screen
(greeting, orb, Speak button, Today's Tasks, module grid). Fixed one compile
error: opt-in to `ExperimentalTextApi` for variable-font weights.

### 2026-07-26 — Voice-first home
Replaced the top bar + Speak button with a centered HUD orb that auto-listens
on open (Android `SpeechRecognizer`, `RECORD_AUDIO`), reacting to voice level
with a live transcript.

### 2026-07-26 — Drawer + schedule + Gemini wiring + this memory file
- Added a **navigation drawer** (top-left menu button) with module destinations
  (Home, Speech, Chat, Memory, Files, Calendar, Vision, Automation, Skills,
  Settings). Real screens come in Step 3.
- Moved the **schedule (Today's Tasks) below the orb** — scroll down to reach it;
  the orb fills the first screen.
- **Wired Gemini:** speech → `AssistantEngine` → `GeminiClient` (REST) → reply is
  shown and spoken via `TextToSpeech`, then listening resumes. Key comes from the
  `GEMINI_API_KEY` GitHub secret (empty ⇒ the app tells you to set it).
- Created this memory file; will update it every prompt.
