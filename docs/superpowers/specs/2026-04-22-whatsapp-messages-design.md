# WhatsApp Messages Feature — Design Spec
**Date:** 2026-04-22
**Status:** Approved

---

## Overview

Add a dedicated Messages page to the poker tracker app that allows the club manager to compose and send WhatsApp messages to one or more players. Messages are sent via Green-API (a WhatsApp gateway service). No message history is stored — send and forget.

---

## Architecture

```
React /messages page
  → POST /api/whatsapp/send (Spring Boot)
    → Green-API REST API
      → WhatsApp → Player(s)
```

No DB schema changes. No webhook. No message history.

---

## Green-API Setup

1. Register at green-api.com
2. Create an instance and scan QR with the dedicated club WhatsApp number
3. Obtain `instanceId` and `apiTokenInstance`
4. Store in `application.properties`:
   ```
   green-api.instance-id=YOUR_INSTANCE_ID
   green-api.token=YOUR_API_TOKEN
   ```

**Send message API call (per recipient):**
```
POST https://api.green-api.com/waInstance{instanceId}/sendMessage/{token}
Content-Type: application/json

{
  "chatId": "972501234567@c.us",
  "message": "Hello!"
}
```

For group sends, the backend loops through all recipients and fires one request per player. Phone numbers are formatted as `{countryCode}{localNumber}@c.us`.

---

## Backend

### New Files

**`WhatsAppController.java`**
- Endpoint: `POST /api/whatsapp/send`
- Request body: `SendMessageRequest` — list of phone numbers + message text
- Returns: success count / list of any failures

**`WhatsAppService.java`**
- Reads `green-api.instance-id` and `green-api.token` from config
- For each phone number in the list, calls Green-API via `RestTemplate` or `HttpClient`
- Formats phone number to `{number}@c.us`
- Collects results (success/fail per number) and returns summary

**`SendMessageRequest.java` (DTO)**
```java
List<String> phoneNumbers;  // e.g. ["972501234567", "972521234567"]
String message;
```

**`SendMessageResponse.java` (DTO)**
```java
int successCount;
int failCount;
List<String> failedNumbers;
```

### Error Handling
- If Green-API returns non-200 for a recipient, log the failure and continue with remaining recipients
- Return summary of successes and failures to frontend

---

## Frontend

### New Page: `/messages`

**Layout — two panels side by side:**

#### Left Panel: Recipients
- Searchable player list pulled from existing `/api/players` endpoint
- Each row: `[ checkbox ] Player Name | Balance (colored)`
  - Positive balance: green
  - Negative balance: red
- **Filter bar:** `|balance| > / <` toggle + number input (same UX as Balance Report page)
- **Sort controls:** by Name (A-Z / Z-A) or by Balance (high-low / low-high)
- **Quick-select buttons:** "All", "All Negative", "Clear"
- **Ad-hoc recipient section** (below the list):
  - "Send to unlisted number" — name input + phone input
  - "Add" button — appends to selected recipients for this send only (not saved to DB)
- Selected count shown at bottom: "X players selected"

#### Right Panel: Message
- **Templates dropdown** — 3–5 predefined message templates, selecting one populates the textarea (user can edit after)
  - Example templates:
    - "Game this Thursday at 9pm — please confirm your attendance"
    - "Reminder: your balance is outstanding, please settle"
    - "The game has been cancelled / postponed"
- **Textarea** — free-text compose, no character limit enforced
- **Send button** — triggers confirmation dialog
  - Dialog: "Sending to X players — confirm?" with Cancel / Send
  - On confirm: shows spinner, calls backend, shows success/error toast
  - Toast: "Sent to X players" or "Sent to X players, Y failed"

---

## Data Flow

1. Page loads → fetches all players with balances from `/api/players`
2. Manager filters/sorts/selects recipients
3. Manager composes message (free text or from template)
4. Manager clicks Send → confirmation dialog
5. On confirm → `POST /api/whatsapp/send` with selected phone numbers + message
6. Backend loops recipients → calls Green-API per recipient → returns summary
7. Frontend shows toast with result

---

## Phone Number Handling

- Players already have phone numbers stored in the DB
- Backend strips any non-digit characters and formats as `{number}@c.us`
- Ad-hoc numbers entered in the UI are passed through the same formatting

---

## Out of Scope (this version)

- Message history / conversation view
- Incoming message handling / webhook
- Scheduled / automated messages
- WhatsApp template approval (Meta WABA)
- Read receipts or delivery status per message
