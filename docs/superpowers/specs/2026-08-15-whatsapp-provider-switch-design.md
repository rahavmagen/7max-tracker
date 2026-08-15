# WhatsApp Provider Switch (Green API ⇄ Wassenger) — Design Spec
**Date:** 2026-08-15
**Status:** Approved

---

## Overview

Green API (the current WhatsApp gateway, see [[2026-04-22-whatsapp-messages-design]]) has been getting "stuck" — messages silently failing to send with no trace in the logs. Rather than migrate outright to a new provider (and risk the same class of surprise), add **Wassenger** as a second provider behind a config flag, so either one can be active at a time and switching is a one-line env var change plus a restart — no code change, no redeploy.

Both providers are unofficial WhatsApp Web gateways (session-based, QR-linked), so this doesn't remove the underlying "Meta could disrupt an unofficial session" risk — it just means a bad day for one provider doesn't take down outreach, and switching back is instant.

---

## Architecture

```
InactiveOutreachService  ─┐
KashcashService           ├──▶ WhatsAppService.sendToAll(...)  ──▶  active WhatsAppProvider
WhatsAppController         │        (picks provider from config)         │
                           ┘                              ┌──────────────┴──────────────┐
                                                    GreenApiProvider              WassengerProvider
                                                  (existing logic,                 (new)
                                                   extracted as-is)
```

- `WhatsAppProvider` — new interface, one method: `boolean send(String phone, String message)`.
- `GreenApiProvider implements WhatsAppProvider` — today's `WhatsAppService` logic (URL building, `972...@c.us` formatting, error logging) moved here unchanged.
- `WassengerProvider implements WhatsAppProvider` — new, calls Wassenger's REST API.
- `WhatsAppService` — becomes a thin router. Keeps the exact public method callers already use (`sendToAll(List<String> phoneNumbers, String message)`), so **`InactiveOutreachService`, `KashcashService`, and `WhatsAppController` need zero changes**. Internally it picks the active provider once (from config) and delegates the send-loop/failure-collection logic it already has today.

This keeps each provider isolated (one class, one external API, independently testable) while the rest of the app keeps depending on a stable interface.

---

## Config

```properties
# Which gateway is active: green-api | wassenger
whatsapp.provider=${WHATSAPP_PROVIDER:green-api}

# Existing Green API config, unchanged
green-api.api-url=${GREEN_API_URL:}
green-api.instance-id=${GREEN_API_INSTANCE_ID:}
green-api.token=${GREEN_API_TOKEN:}

# New Wassenger config
wassenger.api-key=${WASSENGER_API_KEY:}
```

Both providers' credentials are configured simultaneously; only `whatsapp.provider` decides which one is live. Switching providers = change the Railway env var, restart — no deploy of new code required for a routine switch.

**Still needed from the user:** a Wassenger account (register at wassenger.com, link the club WhatsApp number by scanning a QR code — same flow as Green API) and the resulting API key, to be set as `WASSENGER_API_KEY` on Railway. Implementation can proceed without it (Green API stays the default/active provider until the key is supplied); Wassenger just won't be reachable for testing until then.

---

## Wassenger API

```
POST https://api.wassenger.com/v1/messages
Content-Type: application/json
Token: <wassenger.api-key>

{
  "phone": "+972501234567",
  "message": "Hello!"
}
```

Success: `201 Created`. Reference: [Wassenger developer docs](https://wassenger.com/developers).

### Phone number formatting

Green API and Wassenger want different shapes for the same underlying number:

| Provider | Format | Example |
|---|---|---|
| Green API | digits + `@c.us` | `972501234567@c.us` |
| Wassenger | E.164 with `+` | `+972501234567` |

Each provider does its own formatting from the raw stored number (strip non-digits, normalize leading `0` → `972`), same normalization step as today, different final format per provider.

---

## Logging

Today's silent-failure lesson (from the missing-name email investigation) applies here too: log which provider is active at `WhatsAppService` startup, and tag each send attempt/failure with the provider name. A stuck or failing gateway should always be visible in Railway logs — never silent.

---

## Error Handling

Same as today: a failed send for one recipient is logged and does not stop the rest of the batch. `sendToAll` returns the list of numbers that failed, unchanged behavior for callers.

No automatic failover between providers (explicitly decided against — see Out of Scope). A provider outage is surfaced via logs; switching is a manual, deliberate action.

---

## Verification

No unit-test framework currently covers `WhatsAppService`'s HTTP calls (same as Green API today). Verification is manual, via the existing `POST /api/whatsapp/send` endpoint (`WhatsAppController`):

1. Set `WHATSAPP_PROVIDER=wassenger` (once `WASSENGER_API_KEY` is available) locally.
2. Call `/api/whatsapp/send` with a real test number.
3. Confirm delivery and check logs show `wassenger` as the active provider.
4. Flip back to `green-api` and repeat, confirming both paths still work.

---

## Out of Scope (this version)

- Automatic failover between providers on send failure — manual switch only, by design (predictability over resilience for this small a volume).
- Runtime/DB-backed toggle or admin UI for switching — env var only.
- Wassenger multi-device support (device ID selection) — add only if the account ends up with more than one linked WhatsApp number; not needed for a single-device setup.
- Migrating to an official WhatsApp Business API (Meta Cloud API / Twilio) — rejected earlier in favor of this lower-friction approach; revisit separately if unofficial-gateway reliability remains a problem after this change.
- Rotating/scrubbing the Green API token currently committed in `application.properties` git history — separate, unrelated cleanup flagged during this investigation but out of scope here.
