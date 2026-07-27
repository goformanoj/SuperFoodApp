# JARVIS OS — working rules for Claude

> This file is loaded automatically at the start of every session. It is the one
> place that does not rely on remembering to open it.

## Rule 1 — keep the living docs in step with the code (non-negotiable)

**After every merge to `main`, before starting the next task**, update:

| File | What goes in it |
|---|---|
| [`PROGRESS.md`](PROGRESS.md) | Snapshot line (branch · `main` @ sha · last green build · date), what shipped, what is still open |
| [`JARVIS_MEMORY.md`](JARVIS_MEMORY.md) | A dated entry: what was built, **why**, and what the evidence showed |
| [`SESSION_HANDOFF.md`](SESSION_HANDOFF.md) | "Current position" and any new hard-won gotcha |
| [`EXECUTION_PLAN.md`](EXECUTION_PLAN.md) | Only when the plan or its order changes |
| [`PRODUCT_PLAN.md`](PRODUCT_PLAN.md) / [`COMMERCIALIZATION.md`](COMMERCIALIZATION.md) | Only when the vision or a commercial decision changes |

This is **per merge, not per session**. Batching it to the end is how it gets
forgotten — it has already happened once, when the docs fell eight commits
behind while chasing device bugs.

A `Stop` hook (`.claude/hooks/docs-current.sh`) checks this mechanically and will
say so if code has moved ahead of the docs. Do not work around the hook; update
the docs.

## Rule 2 — definition of done

A change is not finished until **all** of these are true:

1. Committed on the session branch and pushed with `-u origin <branch>`.
2. The **`jarvis-debug-apk` artifact appears** for that commit. That is the green
   signal — the job-status API lags 2–5 hours, so never trust the reported status.
3. **`main` fast-forwarded to that commit and pushed.** Never push straight to
   `main`; never leave a green commit unmerged.
4. Docs updated per Rule 1.

## Rule 3 — never in a pushed artifact

- **API keys.** Injected at build time from GitHub Actions secrets. Never committed.
- **The model identifier.** Not in commit messages, PR text, code comments, or
  anything else pushed. Commits are authored as `Claude <noreply@anthropic.com>`
  with no `Co-Authored-By` trailer naming a model.

## Rule 4 — how to debug a device failure

The user shares a trace from **Diagnostics → Share**. **Read the trace before
theorising.** It has repeatedly contradicted the obvious guess — several times the
model's plan was fine and the executor was at fault. Ask for the trace rather
than speculating, and quote the timestamps that prove the diagnosis.

## Rule 5 — testing

- Pure Kotlin logic gets **real JUnit tests** in `app/src/test`. CI runs
  `testDebugUnitTest` before `assembleDebug`, so a regression means no artifact.
- **Do not** "verify" logic by porting regexes to Python and running them. That
  tests the translation, not the code, and is thrown away. (Python is fine as a
  *pre-flight on test expectations* before pushing — never as the test itself.)
- Claude cannot run the app here: no KVM, no Android SDK, and Gradle cannot fetch
  through the proxy. Speech, TTS, and real third-party apps are confirmed by the
  user on-device.

## Rule 6 — irreversible actions need code, not just prompt wording

Sending a message cannot be undone. The prompt taught the model to treat typing
and sending as one move (every `TYPE` example ended in `<<ENTER>>`), and it sent
messages the user only asked to type. Prompts are probabilistic; guard
irreversible actions in code (see `SendGuard`) and test the guard.

## Quick orientation

- **Vision + spec:** [`PRODUCT_PLAN.md`](PRODUCT_PLAN.md)
- **Status:** [`PROGRESS.md`](PROGRESS.md)
- **Build queue:** [`EXECUTION_PLAN.md`](EXECUTION_PLAN.md)
- **Resume state, architecture, gotchas:** [`SESSION_HANDOFF.md`](SESSION_HANDOFF.md)
- **Keys + Play Store:** [`COMMERCIALIZATION.md`](COMMERCIALIZATION.md)
- **Dated dev log:** [`JARVIS_MEMORY.md`](JARVIS_MEMORY.md)
