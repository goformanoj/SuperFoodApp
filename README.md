# JARVIS OS

A native Android AI assistant built with Kotlin, Jetpack Compose, and Material 3.

## Build

There is no local build required — every build runs on GitHub Actions.

- **Stack:** Kotlin, Jetpack Compose, Material 3
- **Android Gradle Plugin:** 9.1.0 (built-in Kotlin support)
- **Gradle:** 9.3.1 (AGP 9.1.0 requires 9.3.1+) · **JDK:** 17
- **SDK:** compileSdk 36, targetSdk 36, minSdk 26
- **Application ID:** `com.jarvis.os`

### Getting the APK

1. Open the **Actions** tab in GitHub.
2. Every push runs **Build JARVIS Debug APK**; open the latest green run.
3. Download the **jarvis-debug-apk** artifact, extract the APK, and install it (see the install runbook in [`SESSION_HANDOFF.md`](SESSION_HANDOFF.md)).

## Project docs

- **[`PRODUCT_PLAN.md`](PRODUCT_PLAN.md)** — the full vision, constraints, and feature spec (incl. API-key security).
- **[`PROGRESS.md`](PROGRESS.md)** — what's done, in flight, and queued.
- **[`EXECUTION_PLAN.md`](EXECUTION_PLAN.md)** — the ordered build queue and working loop.
- **[`COMMERCIALIZATION.md`](COMMERCIALIZATION.md)** — API-key security options and the Play Store launch path.
- **[`BACKEND_PLAN.md`](BACKEND_PLAN.md)** — the ordered backend build, all phases, and what each is blocked on.
- **[`SESSION_HANDOFF.md`](SESSION_HANDOFF.md)** — how to resume: CI, secrets, architecture, install runbook.
- **[`JARVIS_MEMORY.md`](JARVIS_MEMORY.md)** — detailed dated dev log.

## Status

Working voice assistant: always-on conversation (Groq), device calendar (read/add/delete/reschedule), and screen control (open apps, tap, scroll, type, multi-step commands). See [`PROGRESS.md`](PROGRESS.md) for details.
