# `jvmcheck` — the off-device compile + test gate

**Run this before every push.**

```sh
gradle -p scripts/jvmcheck test
```

432 tests across 42 classes, in about ten seconds once the dependencies are cached.

## What it is

A standalone `kotlin("jvm")` project that compiles the app's **non-UI sources**
against the real Android framework and runs the **pure-Kotlin test classes** — no
device, no emulator, no Android SDK.

## Why it can exist at all

`CLAUDE.md` Rule 5 says "Gradle cannot fetch through the proxy". That is only half
true, and the half that is wrong is the useful half:

- **Maven Central is reachable.** ✅
- **`dl.google.com` is refused by the network policy.** ❌ — so AGP, `androidx`
  and Compose cannot be resolved.

The 50 non-UI sources reference nothing in `ui/` and nothing in `R`. So they
type-check fine against `org.robolectric:android-all`, which is a real
`android.jar` published *to Maven Central*. Four tiny stubs (`stubs/`) stand in
for the handful of symbols that only live on Google's Maven, plus the generated
`BuildConfig`.

## What it catches, and what it does not

Catches the entire class of failure that otherwise costs a twenty-minute CI round
trip: typos, wrong signatures, dangling references after a refactor, and any
regression in the pure logic.

Does **not** cover the Compose layer, anything needing a device, or the
instrumented tiers. Those stay in CI. This is a gate, not a replacement — a green
run here still has to be confirmed by the `jarvis-debug-apk` artifact (Rule 2).

## Gotchas

- **Maven Central answers 429 under load.** Retry with backoff; it clears.
- **Only JDK 21 is present here**, so `jvmTarget` is 21. CI builds on 17 and the
  difference is invisible to everything this checks.
- **The test task's `workingDir` is the repo root** or `MelSpectrogramTest`
  cannot find its weights blob.
- **`org.tensorflow:tensorflow-lite` on Central is a placeholder that is not a
  valid zip**, so `Interpreter` is stubbed. The melspectrogram maths that
  actually matters is pure Kotlin and is tested for real.
- **A stub only needs the right SHAPE** — nothing here executes them. If one ever
  needs real behaviour, that is the signal the code under test belongs in CI.
