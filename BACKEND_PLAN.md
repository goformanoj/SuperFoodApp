# JARVIS OS — Backend build plan (all phases)

> **The ordered build for Part E.** The *architecture* was decided in
> [`COMMERCIALIZATION.md` §1b/§1d](COMMERCIALIZATION.md) — schema, auth choice, D1-not-KV,
> tokens-not-requests. This file is the **plan of work**: what gets built, in what order, what
> each phase can and cannot be verified with, and what is blocked on the user.
>
> Companion: [`EXECUTION_PLAN.md`](EXECUTION_PLAN.md) (the whole queue) ·
> [`SESSION_HANDOFF.md`](SESSION_HANDOFF.md) (how to resume) ·
> [`PROGRESS.md`](PROGRESS.md) (status)

---

## Why now

Two things on 2026-08-18 turned this from "planned" into "the fix for a live problem".

**1. Groq retired two models in one day.** `llama-3.3-70b-versatile` (HTTP 404, morning) and
`llama-3.1-8b-instant` (retired, evening). Each removal cost a code change, a CI build, and a
reinstall by the user. In between, the fallback chain silently ran down to **one live model per
tier**, which is why a single `Empty reply from model` killed a turn outright — there was
nothing left to try. **With a backend, the model list is server config: a retirement is a
deploy.**

**2. The brain is still untestable.** Whether JARVIS emits a *good plan* can only be checked by
the user saying 50 prompts out loud ([`docs/SCREEN_CONTROL_EVAL.md`](docs/SCREEN_CONTROL_EVAL.md)),
because the model call happens **inside the phone**. Move the deciding to a server and a script
can fire all of them and assert on the markers.

A third benefit is worth more than it sounds: **the system prompt moves server-side**, so a
prompt fix becomes a deploy instead of an APK plus a reinstall. Most bugs this project has had
were prompt wording.

---

## The shape

```
Phone ──Bearer <Firebase ID token>──▶ Worker ──server-held key──▶ Groq
         + Play Integrity token         │
                                        └──▶ D1: check quota, record tokens
```

~150 lines of JavaScript on Cloudflare Workers (free tier ≈100k req/day) plus D1 (SQLite) for
counters. Nothing to keep alive, nothing to patch.

---

## Decisions carried forward — do not re-litigate

| Decision | Why |
|---|---|
| **D1, not KV** | KV is eventually consistent; two concurrent turns both read the stale total and the quota becomes a suggestion. D1 does atomic `SET x = x + n`. |
| **Meter tokens, not requests** | A screen-control turn costs many times a chat turn. Groq is OpenAI-compatible, so record the real `usage` object — never an estimate of our own. |
| **Input and output stored separately** | They are priced differently. |
| **Firebase Auth** — anonymous first launch, Google sign-in to subscribe | Zero friction to start; entitlement survives a new phone. |
| **Cap checked *before*, true cost known *after*** | A user can overshoot by one turn. Set the cap slightly under and accept it — pin it in a test as intended, rather than pretending it does not happen. |

### Correction to `COMMERCIALIZATION.md` §1d

Step 5 there says *"free on `llama-3.1-8b-instant`, pro on `llama-3.3-70b-versatile`"*.
**Both are retired.** Live and proven by this account's own traffic: **`openai/gpt-oss-20b`**
and **`openai/gpt-oss-120b`**. Fix that line in Phase 0.

> **GOTCHA: never add a model id from memory.** Guessing is exactly what put two dead models
> into `GroqClient`. Check Groq's live model list, or use ids this account's traffic proves.

---

## Phase 0 — the Worker against a fake provider

**Startable immediately. Nothing needed from the user. Fully verifiable in a Claude session.**

Confirmed in this environment: Node **v22.22.2**, npm registry reachable, and a Worker-shaped
ES module (`export default { fetch(req, env) }`) runs under **`node --test` with zero
dependencies** — Node 22 ships real `Request`/`Response` globals. A probe passed.

This is the first part of the entire project that can be genuinely tested where it is written,
rather than reasoned about and handed to the user to try.

### Files

```
backend/
  src/index.js           POST /chat, GET /health. Thin — routing and wiring only.
  src/quota.js           PURE: UTC day key, over-cap decision, remaining allowance.
  src/models.js          tier -> model list. The whole point: a retirement is a deploy.
  src/providers/groq.js  the real call, behind a one-method interface (Phase 1).
  src/providers/fake.js  deterministic stand-in, returns fixed `usage` numbers.
  src/db.js              D1 reads/writes, plus an in-memory fake of the same shape.
  test/*.test.mjs        node:test, zero deps.
  schema.sql             users + usage_daily, exactly as COMMERCIALIZATION.md §1d specifies.
  wrangler.toml          config only — never a key.
  README.md              how to run the tests, and the D1-not-KV reasoning.
```

`quota.js` and `models.js` are pure and carry the logic worth defending; everything else is
wiring. Same split as the Android side, where the pure types are the tested ones.

### `POST /chat` flow (auth stubbed until Phase 3)

1. Read `uid` from the request (stubbed).
2. Load `plan` and today's `usage_daily` row.
3. Over cap → `429` with a JSON body the app can speak aloud. **Provider never called.**
4. Choose the model from the plan.
5. Call the provider.
6. Read `usage` from the response, UPSERT it.
7. Return the reply plus remaining quota.

### Tests (all offline)

- Under cap → provider called once, reply returned, usage recorded.
- Over cap → `429`, and **the provider is never called** — the point of a cap is not spending.
- Two concurrent turns → both counted, neither lost.
- UTC midnight rollover → yesterday's total does not gate today.
- Unknown uid → row created, defaults to `free`.
- Provider error → surfaced, and nothing charged to the user's allowance.
- The cap-overshoot-by-one case, pinned as intended behaviour.

### Also in Phase 0

- Add a **`backend` job to `.github/workflows/build.yml`** running `node --test backend/test`,
  so the Worker is gated the way `testDebugUnitTest` gates the app. No SDK, no emulator, seconds.
- Fix the retired-model line in `COMMERCIALIZATION.md`.

**Done when:** `node --test backend/test` is green locally and in CI.

---

## Phase 1 — the real provider

**Blocked on: a Cloudflare account (Worker + D1 are free tier).**

1. `src/providers/groq.js` — the real call behind the same one-method interface, so nothing
   above it changes.
2. Groq key stored as a Worker secret (`wrangler secret put`), never in the repo.
3. `wrangler d1 create` + apply `schema.sql`.
4. Deploy; smoke-test `GET /health` and one real `/chat`.
5. **The model list now lives here.** Today's two retirements would each have been a one-line
   deploy.

**Verifiable:** logic yes, end-to-end needs the deploy.
**Done when:** a real `/chat` returns a real Groq reply and the usage row increments.

---

## Phase 2 — the eval harness ⭐ the testing payoff

**Blocked on: Phase 1. Worth pulling forward the moment it lands.**

This is the answer to "can the backend solve the testing problem".

1. `scripts/eval/run.mjs` — posts each prompt from `docs/SCREEN_CONTROL_EVAL.md` to `/chat`.
2. Per-row **marker-shape assertions**. Example, row B1 *"order milk and bread on blinkit"*:
   must emit `<<OPEN|Blinkit>>`, must **not** emit a checkout tap.
3. Reports a percentage; runs as a **non-gating** CI job at first (it costs real tokens and the
   model is not deterministic).
4. Grow the checklist toward 100 rows and mark the ones now covered, so the manual list shrinks.

**What this fixes:** plan quality, guard behaviour against the *real* model, and prompt
regressions — change a word, run 100 scenarios, see what broke, in minutes.

**What it does NOT fix:** whether a tap lands on the real Blinkit UI, speech recognition
("Claude" heard as "Cloud"), TTS, mic, barge-in, or an app that redesigned itself last week.
**The backend owns the deciding; the phone still owns the doing.**

**Bonus available here:** the server sees the real screen descriptions the phone sends. Logged
with consent and redaction, they become a replay corpus — today that exists only as
`DeviceTrace0814Test`, 24 tests hand-written from one shared trace.

---

## Phase 3 — identity

**Blocked on: a Firebase project (Auth is free).**

1. Firebase project; anonymous auth on first launch.
2. **Verify the ID token by hand.** It is a standard RS256 JWT, ~60 lines with no dependencies:
   fetch Google's x509 keys, cache them per `Cache-Control`, verify with WebCrypto, then check
   `iss`, `aud` and `exp`. `sub` is the `uid`.
3. Replace the stubbed uid; per-user quota becomes real.
4. Google sign-in required to subscribe, so entitlement survives a new phone.

> **GOTCHA: the Firebase Admin SDK is Node-only and does NOT run on Cloudflare Workers.**
> This is the single biggest trap in the whole plan.

**Done when:** a forged uid is rejected and a real one is quota-limited.

---

## Phase 4 — the app switches over

**Device-only verification.**

1. `ProxyClient` mirroring `GroqClient`'s shape, slotted in beside the existing two behind
   `Brain.generate()` (`app/src/main/java/com/jarvis/os/ai/Brain.kt`) — the app's only call site
   for a model, so there is no call-site churn. **Do not touch `AssistantEngine`.**
2. Firebase Auth dependency for a cached ID token (refresh ≈1h).
3. Behind a flag, with the direct path as fallback while it proves out.
4. **`SystemPrompt.kt` moves server-side** — from here a prompt fix is a deploy.
5. Remove the `BuildConfig` keys once the proxy is the default path.

**Done when:** the app talks only to the Worker, and the APK contains no key.

---

## Phase 5 — abuse and attestation

1. **Play Integrity** — proves a request came from a genuine, unmodified install from Play.
   Auth answers *who*; Integrity answers *from a real copy of our app*. They are not
   substitutes: without it, anyone can pull the endpoint out of the APK and hammer it with curl.
2. Abuse signals — keep `requests` as a cheap counter even though the cap is on tokens.

---

## Phase 6 — money

1. Play Billing; purchase token verified against the Google Play Developer API → `plan = pro`.
2. Real-time Developer Notifications for renewal / cancellation / refund, so entitlement stays
   current.
3. Free-tier daily cap enforced in the proxy.

---

## Risks, stated plainly

| Risk | Mitigation |
|---|---|
| **Cost** — LLM bills move from the user's free Groq tier to ours | Cap on tokens; ~60k/day recommended. 1,000 free users × 20 turns/day on a large model ≈ $600/month, which is what kills indie AI apps. |
| **Privacy** — conversations and screen text transit our server | Privacy policy disclosure + Data safety form. Screen text needs consent and **redaction of password / OTP / payment fields before anything is sent** (already a non-negotiable in `COMMERCIALIZATION.md`). |
| **A new single point of failure** — Worker down means JARVIS down | Keep the direct/BYOK path as a fallback through Phase 4; Gemini client already exists as provider redundancy. |
| **Rate limits are per provider account, not per user** | Groq's free tier breaks around a few dozen active users → paid dev tier before any real launch. |

---

## Open decisions for the user

- **Free-tier cap** — recommended **~60k tokens/day** on the smaller model.
- **Paid price** and the paid-tier fair-use hard cap.
- **Cloudflare account** (Phase 1) and **Firebase project** (Phase 3).

## Status

Nothing built yet — there is no `backend/` directory and no Firebase, Billing or Play
dependency in `app/build.gradle.kts`. **Phase 0 is startable with nothing from the user.**
