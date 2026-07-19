# Sworth — Technical Specification (v1)

> **Status:** Design spec. No application code yet. This document defines what
> we build first and how the pieces fit, so implementation can start from a
> settled shape.

---

## 1. What Sworth is

Sworth is a **price-comparison layer** for food delivery in India. It shows the
**real, final price** of the same order on **Zomato** and **Swiggy** — every
fee, tax, and charge included — so the user can pick the cheaper platform
*before* they commit.

It is **not** a delivery service, a discovery app, a payment app, or a coupon
app. The user already knows what they want; Sworth just tells them which
platform costs less and sends them there.

The entire product is one flow:

1. **Pick a restaurant** that exists on both Zomato and Swiggy.
2. **Build a cart** (items + quantities).
3. **See both final bills side by side** — fully itemized, nothing hidden.
4. **Tap through to the cheaper side.**

Everything else (favourites, comparison history, saved addresses) exists only to
support this flow.

---

## 2. Decisions locked for v1

| Decision | Choice | Why |
|---|---|---|
| **Deliverable** | Responsive **web app** first | Fastest to build, iterate, and share; no app-store cycle. Native mobile comes later. |
| **Data source** | **Curated dataset** (hand-seeded real prices + modeled fee formulas) | Proves the flow and the comparison math with zero legal/ToS risk and zero fragility. |
| **Data architecture** | **Pluggable provider layer** | The curated source is one implementation behind a clean interface; a live source can drop in later without reworking the app. |
| **Geographic scope** | **One neighbourhood**, ~10–20 restaurants | Enough to prove the product; keeps curation tractable. |

### Why curated data, and not live prices yet

Neither Zomato nor Swiggy offers third-party access to prices or — critically —
to the **fee stack** (delivery, GST, platform, handling, surge), which is the
whole point of the product. The realistic ways to get live data are:

- **Scraping / reverse-engineered endpoints** — violates ToS, fragile, breaks on
  every platform change.
- **Browser extension using the user's own session** — the only route where
  "use the user's account" technically works (a normal web app is blocked by the
  browser's CORS / same-origin rule from reading Swiggy/Zomato APIs). Still
  against ToS and still fragile; the ban risk lands on the *user's* account.
- **Official partner feeds** — clean and reliable, but depend on partnerships
  that do not exist today.

None of these is a safe foundation for a first version. So v1 uses curated data
behind a provider interface, and the live-source decision is deferred — see
[§9 Roadmap](#9-roadmap).

---

## 3. Architecture overview

```
┌─────────────────────────────────────────────────────────┐
│  Web client (responsive SPA)                             │
│  Restaurant pick → Cart builder → Side-by-side compare   │
└───────────────────────────┬─────────────────────────────┘
                            │  HTTPS (JSON)
┌───────────────────────────▼─────────────────────────────┐
│  Sworth API (backend)                                    │
│  ┌─────────────┐  ┌──────────────┐  ┌────────────────┐   │
│  │ Restaurants │  │ Compare      │  │ Fee Engine     │   │
│  │ + matching  │  │ endpoint     │  │ (per platform) │   │
│  └─────────────┘  └──────┬───────┘  └────────────────┘   │
│                          │                               │
│                 ┌────────▼─────────┐                     │
│                 │ Provider layer   │  (pluggable)        │
│                 │  • CuratedProvider (v1)                │
│                 │  • LiveProvider   (future)             │
│                 └──────────────────┘                     │
└──────────────────────────────────────────────────────────┘
```

Key principle: **the client never knows where prices come from.** It asks the
API to compare a cart; the API resolves prices through whichever provider is
configured. Swapping curated → live is a backend concern only.

---

## 4. Tech stack (proposed)

| Layer | Choice | Notes |
|---|---|---|
| Web client | **React + TypeScript + Vite** | Mobile-first responsive layout; India is Android/mobile-web heavy. |
| Styling | Tailwind CSS (or CSS modules) | Fast, consistent, mobile-first. |
| Backend | **Node.js + TypeScript** (Express/Fastify) | Shared language with the client; simple provider interface. |
| Data (v1) | Seed files (JSON/SQLite) | Curated dataset lives here; no external calls. |
| Hosting | Static host for client + small API service | Keep it cheap and simple for v1. |

Stack is a recommendation, not a constraint — open to changing before code
starts. Nothing below depends on the exact framework.

---

## 5. Data model

Core entities (curated for v1, shaped so a live provider can populate the same
structures):

- **Restaurant**
  - `id`, `name`, `area`, `cuisines[]`
  - `platformRefs`: `{ zomato: {restaurantId, deepLink}, swiggy: {restaurantId, deepLink} }`
  - This is the **cross-platform match** — the same real restaurant linked to its
    identity on each platform.

- **MenuItem**
  - `id`, `restaurantId`, `name`, `description`
  - `prices`: `{ zomato: number, swiggy: number }` — item prices can differ per
    platform, so both are stored.
  - `matchKey` — used to line up "the same dish" across platforms.

- **Cart** (client-side, sent to API)
  - `restaurantId`, `address` (or area), `lines[]: { itemId, qty }`

- **Bill** (computed, per platform) — see [§6](#6-the-fee-engine--comparison-math)
  - `subtotal`, `deliveryFee`, `gst`, `platformFee`, `handlingCharge`,
    `discounts`, `total`, plus a full itemized `breakdown[]`.

- **ComparisonResult**
  - `{ zomato: Bill, swiggy: Bill, cheaper: 'zomato' | 'swiggy', savings }`

### The cross-platform matching problem

The hardest correctness issue is making sure we compare **the same restaurant**
and **the same items**. In v1 this is solved by **curation**: matches are
entered and verified by hand. A future live provider would need an automated
matching strategy (name + geo + menu fingerprinting) — noted now so the data
model already carries `platformRefs` and `matchKey`.

---

## 6. The fee engine & comparison math

This is where the product's value lives. The subtotal is easy; the **fees** are
the point, and they are dynamic (address, distance, cart value, time of day).

- Each platform gets its own **fee model** — a function:
  `computeBill(platform, cart, context) → Bill`
  where `context` includes address/distance, cart subtotal, and time.
- v1 fee models are **calibrated against real checkout screenshots** so the
  modeled totals track reality closely for the seeded restaurants.
- Every `Bill` returns a **full itemized breakdown** — "no hidden math" is a
  product promise, so the UI can show exactly where each rupee comes from.
- The engine is deterministic and unit-tested against the calibration
  screenshots, so we can prove accuracy.

The comparison is then trivial: compute both bills, compare totals, report the
cheaper side and the savings.

---

## 7. Core flow & screens

Mobile-first, four steps mirroring the product:

1. **Restaurant picker** — list/search of restaurants available on *both*
   platforms in the seeded area. (Filtering to "on both" is essential — a
   restaurant on only one platform can't be compared.)
2. **Cart builder** — add items and quantities, like any delivery app. Item
   prices shown neutrally (comparison happens at the bill, not per item).
3. **Side-by-side compare** — the hero screen. Two columns, Zomato vs Swiggy,
   each fully itemized down to the final total, with the cheaper side clearly
   marked and the savings called out.
4. **Tap through** — one tap opens the cheaper platform at that restaurant.

Supporting (thin in v1): favourites, comparison history, saved addresses.

---

## 8. Known constraints (set expectations now)

- **No pre-filled cart hand-off.** Neither Zomato nor Swiggy accepts a cart
  built in an outside app. Step 4 realistically **deep-links to the restaurant
  page** on the cheaper platform; the user re-adds items there. Worth designing
  the copy/UX around this rather than promising a ready-to-pay cart.
- **Curated prices are samples, not live.** v1 is a faithful, demoable product,
  but not something the public should treat as real-time truth until a live
  provider is added.
- **A pure web app cannot read the user's Swiggy/Zomato session** (CORS /
  same-origin). Live-via-user-session would require a browser extension — a
  separate track, not this web app.
- **Fee models are approximations.** Calibrated to be close, but dynamic pricing
  means occasional drift; the itemized breakdown keeps this honest and auditable.

---

## 9. Roadmap

**Milestone 1 — Spec (this document).** ✅

**Milestone 2 — Curated web MVP.**
- Provider interface + `CuratedProvider`.
- Fee engine with per-platform models, calibrated + unit-tested.
- Seed dataset: ~10–20 restaurants in one area, both platforms.
- Web client: the four screens above, responsive.
- Deep-link-out to restaurant pages.

**Milestone 3 — Validate & refine.**
- Test comparison accuracy against fresh real checkouts.
- Tighten fee models; expand seed set.

**Milestone 4 — Live data decision (deferred).**
- Choose among: browser-extension provider (user session), sanctioned feed, or
  server-side sourcing — with ToS/fragility trade-offs documented.
- Implemented as a new provider behind the existing interface; **no client
  rework.**

**Beyond — the same pattern, other categories** (from the product overview):
- Quick commerce: Blinkit / Instamart / Zepto for the same grocery cart.
- E-commerce: Amazon / Flipkart for the same product.
- The provider + fee-engine + compare architecture is category-agnostic by
  design, so these are new providers, not new apps.

---

## 10. Non-goals for v1

- No live scraping or reverse-engineered endpoints.
- No payments (all payment stays inside Zomato/Swiggy — Sworth never touches
  money).
- No food discovery / recommendations.
- No native mobile app yet (responsive web first).
- No multi-city coverage yet (one neighbourhood).

---

*End of spec.*
