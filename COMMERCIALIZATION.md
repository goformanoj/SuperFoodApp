# JARVIS OS — Commercialization & Key Security

> The path from "an APK I sideload" to "a paid app on the Play Store", plus the full
> API-key security option matrix. Decisions live here; status lives in [`PROGRESS.md`](PROGRESS.md).
> Companion: [`PRODUCT_PLAN.md`](PRODUCT_PLAN.md) · [`EXECUTION_PLAN.md`](EXECUTION_PLAN.md) · [`SESSION_HANDOFF.md`](SESSION_HANDOFF.md)

## Decisions made (2026-07-27)

| Question | Decision |
|---|---|
| API key architecture | **Backend proxy** as the product path (option 5), **BYOK** as a later bonus (option 4) |
| Identity | **Firebase Auth** — anonymous for free, Google sign-in to subscribe; **Play Integrity** on every backend call |
| Monetization | **Freemium subscription** — free tier with a daily cap, paid tier unlimited |
| Public app name | **Deferred**, but planned for — see the trademark gate in Phase B |
| Sequencing | **Features first** (Parts B/C/D), then the commercial foundation (Part E) |

---

## 1. API-key security — every option

**Where we are today.** `GROQ_API_KEY` / `GEMINI_API_KEY` are injected at build time from
GitHub Actions secrets into `BuildConfig` (`app/build.gradle.kts`) and read directly by
`GroqClient` / `GeminiClient`. The key **never enters source control** ✅ — but it **is
compiled into the APK**, so anyone with the APK can extract it with a free decompiler
(jadx/apktool). Treat the current key as effectively public.

| # | Option | Key exposed? | Cost | Verdict |
|---|---|---|---|---|
| 1 | **Status quo** — build-time key in `BuildConfig` | **Yes** — extractable from the APK | $0 | Fine for personal use *only*. Keep Groq on free tier with **no payment method**, so the worst case is quota abuse and never a bill. |
| 2 | **Private repo** | Yes, but the build artifact is no longer publicly downloadable | $0 (free accounts get ~2,000 Actions min/month) | Cheap hardening of #1. Does not fix the APK itself. |
| 3 | **Obfuscation** — R8 + split strings / NDK-embedded key | Yes — delays a determined attacker by minutes, not more | $0 | Security theatre. Do it *alongside* a real fix, never *instead of* one. |
| 4 | **BYOK** — the user supplies their own key, stored in `EncryptedSharedPreferences` (Android Keystore-backed) | **No key of ours ships at all** | $0 | Real fix. Friction for mainstream users, perfect for the free/enthusiast tier. **CHOSEN** |
| 5 | **Backend proxy** — a Cloudflare Worker (or Supabase Edge / Vercel) holds the key; the app calls our endpoint | **No** — the key never leaves the server | $0 on free tiers (CF Workers ≈100k req/day) | The real commercial answer. **CHOSEN** |
| 6 | **Firebase AI Logic** (Vertex AI in Firebase) + App Check | No | GCP Blaze, pay-per-token | Less code than #5, but locks us to Gemini and to a GCP billing account. Rejected for #5's provider freedom. |
| 7 | **On-device model** — Gemini Nano / AICore, MediaPipe LLM Inference | **No key exists** | $0 | No network, no cost, no key — but limited device support and weaker quality. A future "offline mode", not the main path. |
| — | **Rotation** (applies to all) | — | — | Regenerate in Groq → update the GitHub secret → the next build re-embeds it; the old key dies. Do this the moment abuse appears. |

### Target architecture

```
app ──▶ our proxy (holds the key, enforces quota, verifies attestation) ──▶ Groq / Gemini
  └──▶ provider directly, when the user has supplied their own key (BYOK)
```

The proxy also gives us: **Play Integrity / Firebase App Check** attestation so only genuine
installs can call it, server-side rate limiting and per-tier quota, entitlement checks for the
paid tier, and one place to swap models or rotate keys **without shipping a new build**.

`Brain.kt` already selects a provider behind a single `generate()` interface, so the proxy
arrives as a third client alongside `GroqClient` / `GeminiClient` with no call-site churn.

### Until the proxy exists

Stay on option 1 + 2 discipline: Groq **free tier with no billing attached**, rotate on abuse,
never commit a key.

### Priority: the backend IS the product, BYOK is a bonus

The app must work **end to end straight from the Play Store**, exactly like every other AI app
— nobody is asked for an API key. The backend delivers that; BYOK is an optional escape hatch
for enthusiasts (realistically ~3% of users) and ships *after* the backend, not alongside it.

What actually stops the *current* build from scaling isn't Play policy, it's arithmetic: every
install shares one Groq key, and Groq's rate limits are **per account** — at a few dozen active
users everyone starts getting 429s simultaneously.

---

## 1b. How the backend works

```
Phone ──▶ our server (holds the key · verifies the user · counts usage) ──▶ Groq / Gemini ──▶ back
```

A ~150-line Cloudflare Worker (free tier ≈100k requests/day, nothing to maintain). The app POSTs
the conversation to `/chat`; the server attaches the secret key and returns the reply.

Beyond key safety it buys: **per-user quotas** that can't be bypassed by patching the APK;
**model/key swaps without shipping a build** (today that needs a new APK and a reinstall by every
user); abuse blocking; and real usage visibility.

Costs: we run a service, we pay the LLM bills, and conversations transit our server — which the
privacy policy must disclose.

### Multi-user behaviour
1. **Rate limits are per provider account, not per user.** Groq's free tier (~30 req/min) breaks
   around a few dozen active users → move to Groq's paid dev tier. The existing Gemini fallback
   in `Brain.kt` is free redundancy for provider outages and limit spillover.
2. **Stateless requests, no leakage.** Each call carries its own history from the device;
   conversation history stays in on-device SharedPreferences. The server stores nothing but a
   usage counter — cheaper, and a much better privacy story.
3. **Small DB only for `user_id → plan, usage, reset_at`** — free tier. ⚠️ **Superseded by §1d:**
   meter **tokens, not requests** (a screen-control turn costs many times a chat turn), and use
   **D1, not KV** (KV is eventually consistent, so concurrent turns both read the stale total).

### Authentication
| Approach | Friction | Problem |
|---|---|---|
| Anonymous install ID | None | Reinstall resets the free quota; a subscription doesn't follow the user to a new phone |
| Sign in with Google (Credential Manager) | One tap — the account is already on the phone | None material; free |
| **Firebase Auth** ← chosen | Same one tap | Wraps Google sign-in **and** anonymous, gives a `uid` plus server-side token verification with far less code |

**Decision:** Firebase Auth — **anonymous** for the free tier (zero friction on first launch),
**Google sign-in required to subscribe** so entitlement survives a new phone. The server verifies
the ID token against Google's public keys, so a user id cannot be forged.

Separately, **Play Integrity API** proves a request came from a genuine, unmodified install from
Play — that is what stops someone extracting the endpoint URL from the APK and hammering it with
curl. Auth answers *who*; Integrity answers *from a real copy of our app*.

**Subscription verification:** Play Billing hands the app a purchase token → the server verifies
it against the Google Play Developer API → sets `plan = pro`. Google pushes renewal / cancellation
/ refund events (Real-time Developer Notifications) so entitlement stays current.

---

## 1d. How to actually build it — implementation guide (2026-08-05)

> Everything below is decided and ready to build. Nothing here exists in the repo yet:
> there is no `backend/` directory, and no Firebase, Billing or Play dependency in
> `app/build.gradle.kts`. This section is the brief a fresh session can start from.

### The shape

```
Phone ──Bearer <Firebase ID token>──▶ Worker ──server-held key──▶ Groq
         + Play Integrity token         │
                                        └──▶ D1: check quota, record tokens
```

### Storage: D1, not KV

Cloudflare Worker for compute (free tier ≈100k req/day), **D1** (SQLite) for counters.
**Not KV** — KV is eventually consistent, so two concurrent turns can both read the old
total and the quota becomes a suggestion. D1 does atomic `SET x = x + n`. (If per-user
counters ever need strict serialisation under real load, Durable Objects are the upgrade;
D1 is right to start.)

```sql
CREATE TABLE users (
  uid        TEXT PRIMARY KEY,
  plan       TEXT NOT NULL DEFAULT 'free',
  created_at INTEGER NOT NULL
);
CREATE TABLE usage_daily (
  uid           TEXT NOT NULL,
  day           TEXT NOT NULL,               -- '2026-08-05', UTC
  input_tokens  INTEGER NOT NULL DEFAULT 0,
  output_tokens INTEGER NOT NULL DEFAULT 0,
  requests      INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY (uid, day)
);
```

### `POST /chat` — the whole flow

1. Verify the Firebase ID token → `uid`.
2. Verify the Play Integrity token (can be added after step 1 works).
3. Load `plan` and today's `usage_daily` row.
4. Over cap → `429` with a JSON body the app can speak aloud.
5. Choose the model from the plan — free on `llama-3.1-8b-instant`, pro on `llama-3.3-70b-versatile`.
6. Call Groq with the server-held key.
7. Read `usage` from the response and UPSERT it.
8. Return the reply, plus remaining quota so the app can show it.

### Login: Firebase Auth, and the one real gotcha

Anonymous on first launch (zero friction); upgrade to Google sign-in to subscribe, so
entitlement survives a new phone.

**The Firebase Admin SDK is Node-only and does NOT run on Cloudflare Workers.** Verify the
ID token by hand — it is a standard RS256 JWT and this is about 60 lines with no dependencies:

1. Fetch Google's public keys from
   `https://www.googleapis.com/robot/v1/metadata/x509/securetoken@system.gserviceaccount.com`
   and cache them (honour `Cache-Control`).
2. Verify the signature with WebCrypto, then check
   `iss = https://securetoken.google.com/<project-id>`, `aud = <project-id>`, and `exp`.
3. `sub` is the `uid`.

**Auth answers *who*. Play Integrity answers *is this a genuine unmodified install*** — that
is what stops someone extracting the endpoint from the APK and hammering it with curl. They
are not substitutes for each other.

### Token accounting — meter tokens, NOT requests

Groq is OpenAI-compatible, so every response carries the real numbers. Record these, never
an estimate of our own:

```json
"usage": { "prompt_tokens": 1613, "completion_tokens": 84, "total_tokens": 1697 }
```

**Correction to §1b, which specified `requests_today`: for this app that is the wrong meter.**
An ordinary chat turn is ~1,600 input tokens; a turn carrying a screen description is far
larger. Counting requests would let a heavy screen-control user cost several times another
user on the same "20/day" allowance. Cap on **tokens**; keep `requests` only as a cheap abuse
signal.

Store input and output separately — they are priced differently ($0.59 vs $0.79 per M).

```sql
INSERT INTO usage_daily (uid, day, input_tokens, output_tokens, requests)
VALUES (?1, ?2, ?3, ?4, 1)
ON CONFLICT(uid, day) DO UPDATE SET
  input_tokens  = input_tokens  + excluded.input_tokens,
  output_tokens = output_tokens + excluded.output_tokens,
  requests      = requests + 1;
```

Two honest caveats:
- The cap is checked **before** the call and the true cost is known **after**, so a user can
  overshoot by one turn. Set the cap slightly under and accept it.
- If streaming is ever added, `usage` only arrives when the request passes
  `stream_options: {include_usage: true}`.

### A benefit worth more than it sounds: move the system prompt server-side

Every prompt fix shipped this month required a new APK and a reinstall by the user. Once the
proxy owns the prompt, a prompt fix is a deploy. Given how much of this project's debugging
is prompt wording, this may be the single biggest practical win of the backend.

### Android side — small, because there is one choke point

`Brain.generate()` (`app/src/main/java/com/jarvis/os/ai/Brain.kt`) is the only place the app
talks to a model. A new `ProxyClient` mirroring `GroqClient`'s shape slots in beside the
existing two with no call-site churn, plus the Firebase Auth dependency for a cached ID token
(refresh ≈1h). `BuildConfig` keys come out once the proxy is the default path.

### Build order

| # | Step | Verifiable in a Claude session? |
|---|---|---|
| 1 | Worker: quota + token accounting against a **fake** provider, with real unit tests | **Yes** — plain JS, fully testable here |
| 2 | Real Groq call behind the same interface | Partly (needs a key + deploy) |
| 3 | Firebase project + ID-token verification | Logic yes, end-to-end no |
| 4 | Android `ProxyClient` behind a flag | No — device only |
| 5 | Play Integrity | No |
| 6 | Play Billing + entitlement webhook (Real-time Developer Notifications) | No |

**Start at 1.** The Worker is the first part of this project that can be genuinely tested in a
Claude session — it is pure JavaScript logic, unlike every Android change, which can only be
reasoned about and handed to the user to try. After the device sessions of 2026-08-04/05 that
distinction is worth using deliberately.

### Open decisions for the user

- **Free-tier cap.** Recommended **~60k tokens/day** (≈30 ordinary turns, fewer with screen
  control) on `llama-3.1-8b-instant`. 1,000 free users at 20 turns/day on the 70B model would
  cost ≈$600/month, which is what kills indie AI apps.
- **Cloudflare account** — needs creating; Worker + D1 are free tier.
- **Firebase project** — needs creating; Auth is free.
- Paid-tier price (₹199/~$2.30 assumed in §1c) and the paid-tier fair-use hard cap.

---

## 1c. Unit economics (why freemium works here)

Groq `llama-3.3-70b-versatile`: **$0.59 / M input tokens, $0.79 / M output** (verify before
launch — pricing moves). One JARVIS voice turn ≈ 1,600 input (the system prompt is long) + 80
output ≈ **$0.001 per turn**.

| User | Turns/day | LLM cost / month |
|---|---|---|
| Typical | 15 | ~$0.45 |
| Heavy | 50 | ~$1.50 |
| Free tier (capped 20/day) | 20 | ~$0.60 |

Google's cut is **~15%** of subscription revenue (10% service fee + 5% for using Play Billing,
under the 2026 structure). So at **₹199 / ~$2.30 per month** we keep ~$1.95 against ~$0.45 of
cost — roughly **75% margin**; ~85% at $4.99. **Yes: subscription revenue pays the API bills,
Google's cut, and leaves profit.** Groq being this cheap is what makes the model viable.

Two levers that matter more than they sound:
- **Prompt caching halves input cost**, and our system prompt is byte-identical on every call —
  that is the majority of token spend.
- **Put the free tier on the cheap model** (`llama-3.1-8b-instant`). 1,000 free users at 20
  turns/day on the 70B model would cost ~$600/month; that number is what kills indie AI apps.
- Keep a **fair-use hard cap** on the paid tier so a single abusive account can't invert the margin.

---

## 2. Play Store launch path

### Phase A — Account & legal
- $25 one-time developer account + identity verification.
- **Personal accounts created after 13 Nov 2023 must run a closed test with ≥12 testers opted
  in for 14 continuous days** before they can apply for production access
  ([Play Console Help](https://support.google.com/googleplay/android-developer/answer/14151465)).
  Budget that calendar time — it is not a formality.
- Hosted **privacy policy** and **terms of service** URLs (required, and referenced by the
  Data safety form).

### Phase B — Name & identity  ⚠️ hard gate
- **"JARVIS" is a Marvel/Disney trademark.** A takedown is a real risk once the app gets
  traction — and losing a listing *after* launch also loses the reviews and installs. Keep
  JARVIS as the internal codename and wake word; pick an original public name before publishing.
- **`applicationId` can never change after the first publish.** It is currently
  `com.jarvis.os`. This must be decided *before* the first upload, not after.
- Listing assets: icon, feature graphic, screenshots, short/long description.

### Phase C — Policy compliance (the actual blockers)
- **AccessibilityService.** Play permits the API, but only apps genuinely designed to help
  users with disabilities may set `isAccessibilityTool=true`; every other use must complete
  the accessibility declaration in Play Console **and** implement a clear in-app disclosure
  with affirmative user consent before enabling. Undeclared or deceptive use can mean app
  suspension or developer-account termination
  ([Play Console Help](https://support.google.com/googleplay/android-developer/answer/10964491)).
  Hands-free device control is a defensible position, but the declaration must be truthful and
  the disclosure real. Prepare a reviewer-facing demo video.
- **`QUERY_ALL_PACKAGES`** is a restricted permission requiring its own declaration. **Fix it
  cheaply:** replace it with a `<queries>` block for `ACTION_MAIN` + `CATEGORY_LAUNCHER`, which
  is all `AppLauncher` actually needs to resolve an app name to a package. Removes an entire
  review hurdle for a few lines of manifest.
- **Background microphone (Part B).** Needs `FOREGROUND_SERVICE` +
  `FOREGROUND_SERVICE_MICROPHONE`, `android:foregroundServiceType="microphone"`, a persistent
  notification, and a foreground-service declaration in Console. **Build it this way from day
  one** so Part B never has to be redone.
- **Screen text to the LLM (Part C).** Sending on-screen accessibility text to a third-party
  model is a sensitive-data transfer. It needs explicit consent, a matching Data safety
  disclosure, and **redaction of password / OTP / payment fields before anything is sent**.
  This is a design constraint on Part C, not an afterthought.
- **Data safety form** covering voice audio, calendar data, conversation content, screen text,
  and the third-party LLM processor.

### Phase D — Release engineering
- Generate a **release keystore**; store it base64-encoded in a GitHub secret with its
  passwords; enrol in **Play App Signing**.
- Play requires an **AAB**, not an APK → add `.github/workflows/release.yml` running
  `bundleRelease`. The existing debug workflow stays for personal installs.
- Enable `isMinifyEnabled = true` on the release build type (currently `false`) and write the
  `proguard-rules.pro` keep rules for Compose and the JSON models.
- Derive `versionCode` from the CI run number so uploads never collide; `versionName` semver.
- Optional: automate uploads with Gradle Play Publisher.

### Phase E — Monetization (freemium subscription)
- **Free tier:** ~20 AI requests/day on the cheap model, enforced **server-side in the proxy**
  (never in the app, which is trivially patched) — or unlimited via BYOK.
- **Paid tier:** unlimited on the 70B model plus premium features, with a fair-use hard cap.
- Play Billing Library, or RevenueCat to cut the boilerplate. The proxy verifies entitlement
  before spending tokens. See the unit economics in §1c for pricing.

### Phase F — Launch sequence
Internal testing → closed testing (12 testers × 14 days) → apply for production → staged rollout.

---

## Sources
- [App testing requirements for new personal developer accounts — Play Console Help](https://support.google.com/googleplay/android-developer/answer/14151465)
- [Use of the AccessibilityService API — Play Console Help](https://support.google.com/googleplay/android-developer/answer/10964491)
