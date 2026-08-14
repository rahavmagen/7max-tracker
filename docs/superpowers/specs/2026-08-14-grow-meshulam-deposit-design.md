# Grow (Meshulam) Online Deposit Integration — Design Spec
Date: 2026-08-14

## Overview

Add Grow (formerly Meshulam) as a **second** deposit method for players, alongside the existing
KashCash flow — the player picks either one on the Deposit page. Grow supports credit card, Bit,
and bank transfer, all selectable on Grow's own hosted payment page, so there's no need to build
separate UI per payment method.

Grow's API is strictly server-to-server (client-side calls are rejected), and — unlike KashCash's
iframe + `postMessage` pattern — bank transfer and most bank-side Bit/3-D-Secure flows can't
reliably complete inside an iframe. So this integration uses a **full-page redirect to Grow's
hosted checkout**, with their `notifyUrl` webhook as the **sole source of truth** for crediting
chips. The browser returning to `successUrl` is a UX nicety only, not a trust signal.

No Grow merchant account exists yet. This spec is built against Grow's documented API shape
(`https://developers.grow.business`); exact field names get confirmed and adjusted once real
sandbox credentials exist — same situation KashCash was in originally (see the
`// NOTE: adjust "token" to match actual response field name` comment still in `KashcashService`).

---

## Payment Flow (Webhook-driven, redirect-based)

```
1. Player logs in → navigates to "Deposit" tab → picks "Grow" instead of KashCash
2. Player enters amount → clicks "Pay with Grow"
3. Frontend: POST /api/grow/initiate (authenticated JWT)
4. Backend: calls Grow's createPaymentProcess (userId, pageCode, sum, successUrl, cancelUrl,
          description, pageField[fullName/phone/email], notifyUrl)
          → saves GrowInitiated record (growProcessId → playerId + amount)
          → returns { url } (Grow's hosted payment page)
5. Frontend: full-page redirect (window.location.href = url) — NOT an iframe
6. Player pays on Grow's hosted page: card, Bit, or bank transfer (their choice)
7. Grow → POST /api/grow/webhook (public, no JWT)  [may arrive before, during, or after step 8]
          → backend atomically claims the GrowInitiated row (idempotent — duplicate/retried
            webhooks are a no-op)
          → creates Transaction(type=GROW_DEPOSIT, chipsConfirmed=false, notes=growProcessId)
          → sends email + WhatsApp to the admin distribution list (same as KashCash today)
8. Grow → redirects the player's browser to successUrl (/deposit/grow/return)
          → frontend shows "Processing your deposit…" and briefly polls GET /api/grow/my
            for the new transaction, then shows "Confirmed! ₪X added" once it appears
          → if it hasn't landed within ~30s, falls back to "We'll confirm shortly — check
            your balance page" (the deposit already happened server-side via step 7
            regardless of whether the player's browser ever makes it back here)
```

Note there is **no client-driven "finalize" call** (unlike KashCash's `postMessage`-triggered
`/finalize`, which double-checks with KashCash before crediting) — a full-page redirect has no
in-page completion event to react to. The webhook is the only trigger, so its idempotent-claim
correctness matters even more here than in the KashCash flow.

---

## Admin Flow

Identical to the existing KashCash admin flow, on a separate page:

1. Admin opens "Grow Deposits" page
2. **Pending section** — GROW_DEPOSIT transactions where `chipsConfirmed=false`
3. Admin adds chips in game system (external action)
4. Admin clicks "Mark Done" → `POST /api/grow/confirm/{id}` → `chipsConfirmed=true`
5. **History section** — all GROW_DEPOSIT transactions, filterable by date, with total sum

---

## Data Model

### New Entity: `GrowInitiated`

| Field | Type | Notes |
|---|---|---|
| id | Long | PK |
| growProcessId | String | Process/token identifier from Grow's createPaymentProcess response — exact field name TBD from real API response |
| playerId | Long | FK → players |
| amount | BigDecimal | |
| processed | Boolean | Prevents duplicate webhook processing (same atomic-claim pattern as `KashcashInitiated`) |
| createdAt | LocalDateTime | |

### Modified: `Transaction`

- **New type**: `GROW_DEPOSIT` added to `Transaction.Type` enum
- **New method**: `GROW` added to `Transaction.Method` enum
- Reuses the existing `chipsConfirmed` field (already added for KashCash) — no new field needed
- `notes` field stores Grow's process identifier for reference/audit

---

## Backend Components

### New Files

**`GrowInitiated.java`** (entity + repository)
- Table: `grow_initiated`
- Repository methods: `findByGrowProcessId(String)`, `claimForProcessing(Long id)` (same
  `@Modifying @Query` atomic-UPDATE-guard pattern as `KashcashInitiatedRepository`)

**`GrowService.java`**
- `initiateDeposit(Long playerId, BigDecimal amount)` — calls Grow's `createPaymentProcess` with
  `successUrl`/`cancelUrl` pointed at frontend routes and `notifyUrl` pointed at our webhook;
  saves `GrowInitiated`; returns `{ url }`
- `handleWebhook(Map payload)` — idempotency via `claimForProcessing`; creates `Transaction`;
  sends email + WhatsApp (reuse the same notification helpers `KashcashService` already has,
  or extract a small shared `DepositNotificationService` if the duplication starts to hurt —
  starting duplicated is fine given how small each method is)
- `getPending()` / `getHistory(from, to)` / `confirmChips(id)` / `getMyDeposits(playerId)` —
  identical shape to their `KashcashService` counterparts, filtered to `GROW_DEPOSIT`

**`GrowController.java`**

| Endpoint | Auth | Description |
|---|---|---|
| `POST /api/grow/initiate` | PLAYER (JWT) | Initiate deposit, returns hosted payment `url` |
| `POST /api/grow/webhook` | Public (no JWT) | Grow's `notifyUrl` callback — always returns 200 |
| `GET /api/grow/pending` | ADMIN/MANAGER | List pending deposits |
| `GET /api/grow/history?from=&to=` | ADMIN/MANAGER | Full history with date filter |
| `POST /api/grow/confirm/{id}` | ADMIN/MANAGER | Mark chips as added |
| `GET /api/grow/my` | PLAYER (JWT) | Player's own Grow deposit history |

No `/api/grow/finalize` endpoint — not applicable to a redirect-only flow (see Payment Flow note
above).

### Modified Files

**`Transaction.java`**
- Add `GROW_DEPOSIT` to `Type` enum
- Add `GROW` to `Method` enum

**`SecurityConfig.java`**
- Permit `/api/grow/webhook` without JWT (same as `/api/kashcash/webhook`)

### Configuration (`application.properties` / Railway env vars)

```properties
# Grow (Meshulam) API — not yet obtained, placeholders until account is registered
grow.base-url=https://sandbox.meshulam.co.il/api/light/server/1.0
grow.user-id=<from Grow onboarding>
grow.page-code=<from Grow onboarding>

app.grow.callback-url=https://<prod-domain>/api/grow/webhook
app.grow.success-url=https://max7.vercel.app/deposit/grow/return
app.grow.cancel-url=https://max7.vercel.app/deposit

# Reuses existing notification config (spring.mail.*, resend.*, app.kashcash.notification-emails
# / notification-whatsapp) — same distribution list as KashCash deposits, no new setup needed
```

Switches to `https://api.meshulam.co.il/api/light/server/1.0` for production once verified in
sandbox.

---

## Frontend Components

### Modified: `Deposit.jsx` (existing route: `/deposit`, PLAYER only)

- Add a second payment button next to "Pay with KashCash": **"Pay with Grow (Card / Bit / Bank
  Transfer)"**
- On click: `POST /api/grow/initiate` with the amount → `window.location.href = data.url`
  (full-page redirect, no iframe)
- Below the payment forms: player's own GROW_DEPOSIT history (date, amount, chips status) —
  same table pattern as the existing KashCash history section, just filtered to the other type

### New: `GrowDepositReturn.jsx` (route: `/deposit/grow/return`, PLAYER only)

- Shown after Grow redirects the player back
- Polls `GET /api/grow/my` every ~3s for up to ~30s looking for a transaction matching the
  amount/recent timestamp
- Found → "✅ Confirmed! ₪X added — chips will be credited shortly"
- Not found within the window → "We'll confirm your deposit shortly — check your balance page"
  (never shows a hard failure here; the webhook already ran server-side independent of this page)

### New: `GrowDeposits.jsx` (route: `/grow-deposits`, ADMIN/MANAGER only)

Same structure as the existing `KashcashDeposits.jsx`:
- **Pending Deposits**: Date | Username | Full Name | Amount | Grow Process ID | Action
- **History**: date-filtered, with a total row

### Modified: `App.jsx`
- Add `<NavLink to="/grow-deposits">Grow Deposits</NavLink>` in ADMIN nav
- Add route for `/deposit/grow/return` (PLAYER) and `/grow-deposits` (ADMIN/MANAGER)

### Modified: `api.js`
```js
initiateGrowDeposit(amount)       // POST /api/grow/initiate
getPendingGrowDeposits()          // GET  /api/grow/pending
getGrowHistory(from, to)          // GET  /api/grow/history
confirmGrowDeposit(id)            // POST /api/grow/confirm/{id}
getMyGrowDeposits()                // GET  /api/grow/my
```

---

## Error Handling

- `initiateDeposit` failure (network error, bad/missing credentials before onboarding is
  complete) → clear error returned to frontend, same as `KashcashService.initiateDeposit` today
- Webhook idempotency: identical atomic-claim guard as `KashcashInitiatedRepository
  .claimForProcessing` — a retried/duplicate webhook is a safe no-op, never double-credits
- Webhook endpoint always returns HTTP 200 even on internal errors (logged) so Grow doesn't
  retry indefinitely — same pattern as the KashCash webhook
- Unrecognized process identifier in a webhook payload → logged and ignored (mirrors KashCash's
  "unknown transactionId" handling)

---

## Testing

- No live sandbox access until the Grow account is registered. Before that:
  - Compile + manually verify request/response parsing against the documented field shapes
  - POST a synthetic payload to `/api/grow/webhook` locally to verify crediting + idempotency
    end-to-end against the local DB (same approach used to originally verify KashCash)
- Once real sandbox credentials exist: one live end-to-end sandbox deposit test (card, Bit, and
  bank transfer each, since they're genuinely different flows on Grow's hosted page), matching
  the verification rigor used for the KashCash production rollout, before flipping production
  env vars.

---

## Setup Checklist (before go-live)

- [ ] Register a Grow (Meshulam) business account, obtain `userId` + `pageCode`
- [ ] Confirm exact `createPaymentProcess` response field names (hosted URL, process identifier)
      and `notifyUrl` webhook payload shape against the real sandbox — adjust `GrowService`
      parsing to match (same "verify against real API, don't trust docs blindly" lesson learned
      from KashCash's login response)
- [ ] Register the webhook URL with Grow: `https://<prod-domain>/api/grow/webhook`
- [ ] Set Railway env vars: `GROW_USER_ID`, `GROW_PAGE_CODE`, `GROW_BASE_URL`
- [ ] Test with Grow's sandbox environment first — all three payment methods (card, Bit, bank
      transfer)
- [ ] Ask Grow directly about settlement timing/fees to your bank account (not in their public
      API docs — a contract/business term, not an API concern)
