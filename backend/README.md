# `backend/` — the JARVIS OS proxy

```
Phone ──▶ this Worker ──(server-held key)──▶ Groq ──▶ back
                │
                └──▶ D1: check the daily allowance, record what it cost
```

## Run the tests

```sh
cd backend && npm test        # node --test test/*.test.mjs
```

**No `npm install`. No dependencies at all.** A Cloudflare Worker is an ES module
exporting `fetch(request, env)`, and Node 22 ships real `Request`/`Response`
globals — so Node's own test runner drives the real handler. That is why this is
the first part of JARVIS OS that can be genuinely tested where it is written,
rather than reasoned about and handed to the user to try on a phone.

## What is real here, and what is stubbed

| | Phase 0 (now) | Later |
|---|---|---|
| Quota + token accounting | **real, tested** | — |
| Model list per plan | **real, tested** | — |
| Provider | **real Groq**, with the fallback chain | — |
| App gate | **shared secret** (`X-Proxy-Secret`) | Phase 3 replaces with Firebase |
| User identity | **stubbed** — `X-Uid` header | Phase 3: Firebase ID token |
| Storage | D1 in prod, in-memory in tests | — |

> ⚠️ **The shared secret is not real auth.** Everyone sends the same string, so
> it identifies the *app*, not a user — a person who has the app can still claim
> any uid and therefore any allowance. What it does buy is that a stranger who
> finds the URL cannot spend the Groq key at all, which is the difference between
> a closed relay and an open one. Firebase replaces it in Phase 3.
>
> The deployed entry point **fails closed**: with `PROXY_SECRET` or `GROQ_API_KEY`
> unset it returns 503 and serves nothing. "Allow when unconfigured" is the kind
> of default nobody notices until the bill does.

Full plan: [`../BACKEND_PLAN.md`](../BACKEND_PLAN.md). Architecture and the
reasoning behind each decision: [`../COMMERCIALIZATION.md`](../COMMERCIALIZATION.md) §1b/§1d.

## Secrets, set in the Cloudflare dashboard

Worker → Settings → Variables and Secrets. Never in this repo, never in `wrangler.toml`:

| Secret | What |
|---|---|
| `GROQ_API_KEY` | the provider key the phone must never hold |
| `PROXY_SECRET` | any long random string; the app sends it as `X-Proxy-Secret` |

## The three decisions worth not re-litigating

**D1, not KV.** KV is eventually consistent, so two turns arriving together both
read the same stale total, both write their own, and the quota quietly becomes a
suggestion. D1 does an atomic `SET x = x + n` in one statement. There is a test
for the concurrent case.

**Tokens, not requests.** A screen-control turn carries the agent prompt plus a
description of everything on screen, and costs many times an ordinary chat turn.
Capping requests would let one heavy user spend several times another's on the
same nominal allowance. The provider returns its own `usage` numbers — those are
recorded, never an estimate of ours.

**The cap is checked before the call; the true cost is known after.** So a user
can overshoot by at most one turn. That is deliberate, it is pinned in
`quota.test.mjs`, and the fix is to set the cap slightly under the real budget —
not to pre-charge an estimate, which would be wrong in both directions.

## Why this file exists at all

On 2026-08-18 Groq retired `llama-3.3-70b-versatile` and `llama-3.1-8b-instant`
hours apart. Each removal cost a Kotlin change, a CI build, and a reinstall by the
user — and in between, the app's fallback chain silently ran down to one live
model per tier, which is why a single empty reply killed a turn outright. In
`src/models.js` the same change is a deploy.

## Layout

```
src/quota.js           PURE — day key (UTC), cap, over-cap decision, spoken refusal
src/models.js          PURE — plan -> model list, and the retired-model guard
src/db.js              d1Store (production) and memoryStore (tests), one interface
src/providers/fake.js  deterministic stand-in, so accounting can be asserted exactly
src/index.js           routing and wiring only
schema.sql             users + usage_daily
wrangler.toml          config only — the key is a Worker secret, never in the repo
```
