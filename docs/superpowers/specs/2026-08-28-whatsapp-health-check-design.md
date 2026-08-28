# WhatsApp (Green API) Health Check & Alerting — Design Spec
**Date:** 2026-08-28
**Status:** Approved

---

## Overview

Green API messages have been failing without any visible signal — the root problem, per [[2026-08-15-whatsapp-provider-switch-design]], is that a stuck/unauthorized WhatsApp session doesn't always show up as an HTTP error, so nothing in the logs or the app UI tells the admin it's time to act. (Confirmed again this week: the Green API subscription lapsed on the 26th, and nothing surfaced that fact until it was noticed manually.)

Rather than adding a second paid provider (Wassenger) or routing sends through Make.com, this adds a **direct, in-app health check**: periodically ask Green API for its own instance state and email an alert the moment it stops being `authorized`. This is a detection/alerting feature only — it does not add automatic failover to another provider; switching providers stays a manual, deliberate action (unchanged from the Aug-15 spec).

---

## Architecture

```
WhatsAppHealthScheduler (every 30 min)
    │
    ▼
WhatsAppService.checkInstanceState()  ──▶  GET {green-api}/waInstance{id}/getStateInstance/{token}
    │
    ▼
compare to last-known state (in-memory)
    │
    ├── authorized → notAuthorized/blocked/sleepMode  ⇒  send "WhatsApp is down" email, now
    ├── still not authorized after 24h since last alert ⇒  send daily reminder email
    └── notAuthorized → authorized                     ⇒  send "WhatsApp recovered" email
```

- `WhatsAppService` gains one new method, `checkInstanceState()`, alongside the existing `sendToAll`/`sendOne`. Same class, same credentials it already has (`green-api.api-url`, `instance-id`, `token`) — no new config for the API call itself.
- `WhatsAppHealthScheduler` is a new `@Component`, modeled directly on the existing `InactiveOutreachScheduler` (`@Scheduled`, try/catch around the call, `log.error` on unexpected failure).
- Alert emails go through the existing `GmailEmailService.send(...)` (already used by `GrowDepositService`) — no new email integration.
- State is held in a plain instance field on the scheduler (or service) — single Railway instance, no DB table. A restart just re-checks from scratch on the next tick, which is fine (worst case: one missed "already knew about this" dedup, not a missed alert).

---

## Config

```properties
# Reuses the existing Green API credentials — no new values needed:
# green-api.api-url, green-api.instance-id, green-api.token

# Reuses the existing shared notification list (same one KashCash/missing-names/Grow already use):
app.whatsapp-health.notification-emails=${NOTIFY_EMAILS:}
```

No new Railway env vars required.

---

## Alerting Logic

| Transition | Action |
|---|---|
| `authorized` → anything else (`notAuthorized`, `blocked`, `sleepMode`) | Send alert email immediately: "WhatsApp (Green API) is down — state: X" |
| Still not `authorized`, ≥24h since the last alert for this outage | Send a reminder email (same subject, so it doesn't get lost) |
| anything else → `authorized` | Send a one-time "WhatsApp (Green API) recovered" email |
| No change in state | No email — this runs every 30 min and must not spam |

Email channel only (not WhatsApp) — deliberately, since an alert about WhatsApp being down shouldn't depend on WhatsApp itself to arrive.

---

## Error Handling

- `checkInstanceState()` failing outright (network error, non-200, unparseable body) is logged (`log.warn`) and treated as "unknown" — it does **not** trigger a false "down" alert on a transient scheduler-side glitch, and does not overwrite the last-known good/bad state.
- Scheduler wraps the whole tick in try/catch, same as `InactiveOutreachScheduler` — one failed tick never breaks the recurring job.
- Alert email failure (Gmail API error) is logged; there's no retry queue for this — same tolerance level as the other admin-notification emails in this codebase today.

---

## Verification

No unit-test framework currently covers this kind of HTTP integration (same as `WhatsAppService` today). Manual verification:

1. Deploy with the Green API subscription still lapsed (it already is, as of the 26th) — the next scheduled tick should detect `notAuthorized` and send the "down" alert. This is a live, already-available test case.
2. Resume the Green API subscription / re-authorize the instance → confirm the next tick sends the "recovered" email.
3. Manually re-trigger a second "still down" tick within 24h of an alert → confirm no duplicate email. Wait past 24h (or adjust the check locally) → confirm the reminder fires.

---

## Out of Scope (this version)

- Automatic failover to Wassenger or any other provider — manual switch only, unchanged from [[2026-08-15-whatsapp-provider-switch-design]].
- Adding Wassenger as a second provider at all — deferred; this spec only closes the "we don't know when it's down" gap for the existing Green API integration.
- Routing sends or health checks through Make.com — considered and rejected for this specific piece: Make added real value for Grow only because it bypassed Grow's paid/gated direct API; Green API's direct API has no such gate, so Make would just be an extra hop with no upside, and a new dependency for the very thing meant to detect an outage.
- On-demand health-check endpoint/admin UI — not needed yet; the scheduled check + email is sufficient for current volume.
- Persisting health-check history to the DB — in-memory state is enough at this scale.
