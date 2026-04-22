# WhatsApp Messages Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a `/messages` page that lets the manager compose and send WhatsApp messages to any subset of players via the Green-API gateway.

**Architecture:** Spring Boot backend exposes `POST /api/whatsapp/send`; it reads Green-API credentials from `application.properties` and calls Green-API's REST endpoint once per recipient. React frontend renders a two-panel page (recipients + message compose) with balance filtering, sorting, and ad-hoc recipient support.

**Tech Stack:** Java 21 / Spring Boot, `java.net.http.HttpClient` (built-in, no new dep), React 18, axios

> ⚠️ **LOCAL ONLY** — Do NOT push to GitHub or deploy to Railway. Green-API credentials stay in local `application.properties` only.

---

## File Map

| Action | Path | Responsibility |
|--------|------|----------------|
| Modify | `src/main/resources/application.properties` | Add Green-API credentials |
| Create | `...tracker/service/WhatsAppService.java` | Call Green-API REST per recipient |
| Create | `...tracker/controller/WhatsAppController.java` | Expose POST /api/whatsapp/send |
| Modify | `src/api.js` (frontend) | Add `sendWhatsAppMessage` axios call |
| Create | `src/pages/WhatsAppMessages.jsx` | Full messages page UI |
| Modify | `src/App.jsx` | Add import, route `/messages`, NavLink |

Full paths:
- Backend root: `c:/projects/tracker/src/main/java/com/sevenmax/tracker/`
- Frontend root: `c:/projects/poker-frontend/src/`

---

## Task 1: Register Green-API account and add credentials

**Files:**
- Modify: `c:/projects/tracker/src/main/resources/application.properties`

- [ ] **Step 1: Register at green-api.com**
  1. Go to green-api.com → Sign up (free)
  2. Create a new instance
  3. Scan the QR code with the dedicated club WhatsApp number
  4. Copy your `idInstance` and `apiTokenInstance` from the dashboard

- [ ] **Step 2: Add credentials to application.properties**

Open `c:/projects/tracker/src/main/resources/application.properties` and add at the bottom:

```properties
# Green-API WhatsApp Gateway (LOCAL ONLY - do not commit)
green-api.instance-id=YOUR_INSTANCE_ID_HERE
green-api.token=YOUR_API_TOKEN_HERE
```

Replace `YOUR_INSTANCE_ID_HERE` and `YOUR_API_TOKEN_HERE` with the real values from the dashboard.

- [ ] **Step 3: Verify instance is connected**

In a browser, open:
```
https://api.green-api.com/waInstance{YOUR_ID}/getStateInstance/{YOUR_TOKEN}
```
Expected response:
```json
{"stateInstance": "authorized"}
```
If you see `"notAuthorized"`, rescan the QR code in the Green-API dashboard.

---

## Task 2: Create WhatsAppService

**Files:**
- Create: `c:/projects/tracker/src/main/java/com/sevenmax/tracker/service/WhatsAppService.java`

- [ ] **Step 1: Create the service file**

```java
package com.sevenmax.tracker.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
public class WhatsAppService {

    @Value("${green-api.instance-id}")
    private String instanceId;

    @Value("${green-api.token}")
    private String token;

    private final HttpClient httpClient = HttpClient.newHttpClient();

    /**
     * Sends a WhatsApp message to each phone number in the list.
     * Returns list of phone numbers that failed.
     */
    public List<String> sendToAll(List<String> phoneNumbers, String message) {
        List<String> failed = new ArrayList<>();
        for (String phone : phoneNumbers) {
            boolean ok = sendOne(phone, message);
            if (!ok) failed.add(phone);
        }
        return failed;
    }

    private boolean sendOne(String rawPhone, String message) {
        String chatId = formatChatId(rawPhone);
        String url = String.format(
            "https://api.green-api.com/waInstance%s/sendMessage/%s",
            instanceId, token
        );
        String body = String.format(
            "{\"chatId\":\"%s\",\"message\":\"%s\"}",
            chatId, escapeJson(message)
        );
        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                log.info("WhatsApp sent to {}", chatId);
                return true;
            } else {
                log.warn("WhatsApp failed for {} — HTTP {}: {}", chatId, response.statusCode(), response.body());
                return false;
            }
        } catch (Exception e) {
            log.error("WhatsApp exception for {}: {}", chatId, e.getMessage());
            return false;
        }
    }

    /** Strip non-digits and append @c.us */
    private String formatChatId(String phone) {
        return phone.replaceAll("[^0-9]", "") + "@c.us";
    }

    /** Escape quotes and backslashes for inline JSON string */
    private String escapeJson(String text) {
        return text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }
}
```

- [ ] **Step 2: Restart Spring Boot and verify it starts without errors**

```bash
cd c:/projects/tracker
./mvnw spring-boot:run
```

Expected: Application starts on port 8080 with no `@Value` binding errors.

- [ ] **Step 3: Commit**

```bash
cd c:/projects/tracker
rtk git add src/main/java/com/sevenmax/tracker/service/WhatsAppService.java
rtk git commit -m "feat: add WhatsAppService for Green-API message sending"
```

---

## Task 3: Create WhatsAppController

**Files:**
- Create: `c:/projects/tracker/src/main/java/com/sevenmax/tracker/controller/WhatsAppController.java`

- [ ] **Step 1: Create the controller file**

```java
package com.sevenmax.tracker.controller;

import com.sevenmax.tracker.service.WhatsAppService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/whatsapp")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class WhatsAppController {

    private final WhatsAppService whatsAppService;

    @PostMapping("/send")
    public ResponseEntity<Map<String, Object>> send(@RequestBody SendRequest req) {
        if (req.phoneNumbers() == null || req.phoneNumbers().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "No recipients"));
        }
        if (req.message() == null || req.message().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Message is empty"));
        }
        List<String> failed = whatsAppService.sendToAll(req.phoneNumbers(), req.message());
        int total = req.phoneNumbers().size();
        int success = total - failed.size();
        return ResponseEntity.ok(Map.of(
            "successCount", success,
            "failCount", failed.size(),
            "failedNumbers", failed
        ));
    }

    record SendRequest(List<String> phoneNumbers, String message) {}
}
```

- [ ] **Step 2: Test the endpoint manually with curl**

```bash
curl -s -X POST http://localhost:8080/api/whatsapp/send \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer YOUR_JWT_TOKEN" \
  -d "{\"phoneNumbers\":[\"972501234567\"],\"message\":\"Test from tracker\"}"
```

Expected response:
```json
{"successCount": 1, "failCount": 0, "failedNumbers": []}
```

And the WhatsApp number should receive the message.

- [ ] **Step 3: Commit**

```bash
cd c:/projects/tracker
rtk git add src/main/java/com/sevenmax/tracker/controller/WhatsAppController.java
rtk git commit -m "feat: add WhatsAppController POST /api/whatsapp/send"
```

---

## Task 4: Add API function to frontend

**Files:**
- Modify: `c:/projects/poker-frontend/src/api.js`

- [ ] **Step 1: Add `sendWhatsAppMessage` to api.js**

Open `c:/projects/poker-frontend/src/api.js` and add at the bottom of the file:

```js
export const sendWhatsAppMessage = (phoneNumbers, message) =>
  api.post('/whatsapp/send', { phoneNumbers, message });
```

- [ ] **Step 2: Commit**

```bash
cd c:/projects/poker-frontend
rtk git add src/api.js
rtk git commit -m "feat: add sendWhatsAppMessage to api.js"
```

---

## Task 5: Create WhatsAppMessages page

**Files:**
- Create: `c:/projects/poker-frontend/src/pages/WhatsAppMessages.jsx`

- [ ] **Step 1: Create the page**

```jsx
import { useState, useEffect } from 'react';
import { getPlayers, sendWhatsAppMessage } from '../api';

const TEMPLATES = [
  { label: 'Game Invitation', text: 'Game this Thursday at 9pm — please confirm your attendance.' },
  { label: 'Balance Reminder', text: 'Reminder: your balance is outstanding, please settle.' },
  { label: 'Game Cancelled', text: 'The game has been cancelled / postponed. We will update you soon.' },
  { label: 'Game Confirmed', text: 'The game is confirmed for this week. See you there!' },
  { label: 'Custom...', text: '' },
];

export default function WhatsAppMessages() {
  const [players, setPlayers] = useState([]);
  const [selected, setSelected] = useState(new Set());
  const [filterDir, setFilterDir] = useState('>');
  const [filterAmt, setFilterAmt] = useState('');
  const [sortCol, setSortCol] = useState('username');
  const [sortDir, setSortDir] = useState(1);
  const [search, setSearch] = useState('');
  const [message, setMessage] = useState('');
  const [templateIdx, setTemplateIdx] = useState(null);
  const [adhocName, setAdhocName] = useState('');
  const [adhocPhone, setAdhocPhone] = useState('');
  const [adhocList, setAdhocList] = useState([]);
  const [sending, setSending] = useState(false);
  const [result, setResult] = useState(null);
  const [showConfirm, setShowConfirm] = useState(false);

  useEffect(() => { getPlayers().then(r => setPlayers(r.data)); }, []);

  // Filtering
  const amt = parseFloat(filterAmt) || 0;
  const filtered = players.filter(p => {
    const b = p.balance || 0;
    const passFilter = amt === 0 || (filterDir === '>' ? b > amt : b < -amt);
    const passSearch = !search || p.username?.toLowerCase().includes(search.toLowerCase()) ||
      p.fullName?.toLowerCase().includes(search.toLowerCase());
    return passFilter && passSearch;
  });

  // Sorting
  const sorted = [...filtered].sort((a, b) => {
    if (sortCol === 'username') return (a.username || '').localeCompare(b.username || '') * sortDir;
    if (sortCol === 'fullName') return (a.fullName || '').localeCompare(b.fullName || '') * sortDir;
    return ((a.balance || 0) - (b.balance || 0)) * sortDir;
  });

  const toggleSort = (col) => {
    if (sortCol === col) setSortDir(d => d * -1);
    else { setSortCol(col); setSortDir(1); }
  };
  const arrow = (col) => sortCol === col ? (sortDir === 1 ? ' ↑' : ' ↓') : '';

  const togglePlayer = (id) => {
    setSelected(prev => {
      const next = new Set(prev);
      next.has(id) ? next.delete(id) : next.add(id);
      return next;
    });
  };

  const selectAll = () => setSelected(new Set(sorted.map(p => p.id)));
  const selectAllNegative = () => setSelected(new Set(players.filter(p => (p.balance || 0) < 0).map(p => p.id)));
  const clearAll = () => setSelected(new Set());

  const addAdhoc = () => {
    if (!adhocPhone.trim()) return;
    setAdhocList(prev => [...prev, { id: `adhoc-${Date.now()}`, username: adhocName || adhocPhone, phone: adhocPhone }]);
    setAdhocName('');
    setAdhocPhone('');
  };
  const removeAdhoc = (id) => setAdhocList(prev => prev.filter(a => a.id !== id));

  const selectedPlayers = players.filter(p => selected.has(p.id));
  const allRecipients = [...selectedPlayers, ...adhocList];
  const recipientCount = allRecipients.length;

  const applyTemplate = (idx) => {
    setTemplateIdx(idx);
    setMessage(TEMPLATES[idx].text);
  };

  const handleSend = async () => {
    setShowConfirm(false);
    setSending(true);
    setResult(null);
    try {
      const phones = allRecipients
        .map(r => r.phone)
        .filter(Boolean);
      const res = await sendWhatsAppMessage(phones, message);
      setResult(res.data);
    } catch (e) {
      setResult({ error: e.message });
    } finally {
      setSending(false);
    }
  };

  const fmt = (n) => {
    if (!n && n !== 0) return '—';
    const abs = Math.abs(n).toLocaleString('he-IL', { minimumFractionDigits: 0, maximumFractionDigits: 0 });
    return (n < 0 ? '-' : '') + '₪' + abs;
  };

  return (
    <div style={{ padding: '1.5rem', maxWidth: '1200px', margin: '0 auto' }}>
      <h2 style={{ marginBottom: '1.5rem', color: '#e2e8f0' }}>WhatsApp Messages</h2>

      <div style={{ display: 'flex', gap: '1.5rem', alignItems: 'flex-start' }}>

        {/* LEFT PANEL — Recipients */}
        <div style={{ flex: '1', background: '#1e2235', borderRadius: '10px', padding: '1rem', minWidth: 0 }}>
          <h3 style={{ color: '#94a3b8', marginBottom: '0.75rem', fontSize: '0.9rem', textTransform: 'uppercase' }}>Recipients</h3>

          {/* Search */}
          <input
            placeholder="Search players..."
            value={search}
            onChange={e => setSearch(e.target.value)}
            style={inputStyle}
          />

          {/* Filter bar */}
          <div style={{ display: 'flex', gap: '0.5rem', marginBottom: '0.75rem', alignItems: 'center' }}>
            <span style={{ color: '#94a3b8', fontSize: '0.85rem' }}>|balance|</span>
            <select
              value={filterDir}
              onChange={e => setFilterDir(e.target.value)}
              style={{ ...inputStyle, width: '60px', marginBottom: 0, padding: '4px 6px' }}
            >
              <option value=">">{'>'}</option>
              <option value="<">{'<'}</option>
            </select>
            <input
              type="number"
              placeholder="Amount"
              value={filterAmt}
              onChange={e => setFilterAmt(e.target.value)}
              style={{ ...inputStyle, width: '90px', marginBottom: 0 }}
            />
          </div>

          {/* Quick-select buttons */}
          <div style={{ display: 'flex', gap: '0.5rem', marginBottom: '0.75rem', flexWrap: 'wrap' }}>
            {[['All', selectAll], ['All Negative', selectAllNegative], ['Clear', clearAll]].map(([label, fn]) => (
              <button key={label} onClick={fn} style={btnSecondary}>{label}</button>
            ))}
          </div>

          {/* Player list */}
          <div style={{ maxHeight: '380px', overflowY: 'auto', border: '1px solid #2d3148', borderRadius: '6px' }}>
            <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: '0.85rem' }}>
              <thead>
                <tr style={{ background: '#161929', color: '#94a3b8' }}>
                  <th style={{ width: '32px', padding: '6px' }}></th>
                  <th style={{ padding: '6px', textAlign: 'left', cursor: 'pointer' }} onClick={() => toggleSort('username')}>
                    Username{arrow('username')}
                  </th>
                  <th style={{ padding: '6px', textAlign: 'right', cursor: 'pointer' }} onClick={() => toggleSort('balance')}>
                    Balance{arrow('balance')}
                  </th>
                </tr>
              </thead>
              <tbody>
                {sorted.map(p => (
                  <tr
                    key={p.id}
                    onClick={() => togglePlayer(p.id)}
                    style={{
                      cursor: 'pointer',
                      background: selected.has(p.id) ? '#1a2744' : 'transparent',
                      borderBottom: '1px solid #2d3148',
                    }}
                  >
                    <td style={{ padding: '6px', textAlign: 'center' }}>
                      <input type="checkbox" checked={selected.has(p.id)} onChange={() => togglePlayer(p.id)} onClick={e => e.stopPropagation()} />
                    </td>
                    <td style={{ padding: '6px', color: '#e2e8f0' }}>
                      {p.username}
                      {p.fullName && <span style={{ color: '#64748b', marginLeft: '6px', fontSize: '0.8rem' }}>{p.fullName}</span>}
                    </td>
                    <td style={{ padding: '6px', textAlign: 'right', color: (p.balance || 0) >= 0 ? '#22c55e' : '#ef4444', fontWeight: 600 }}>
                      {fmt(p.balance)}
                    </td>
                  </tr>
                ))}
                {sorted.length === 0 && (
                  <tr><td colSpan={3} style={{ padding: '12px', textAlign: 'center', color: '#64748b' }}>No players match filter</td></tr>
                )}
              </tbody>
            </table>
          </div>

          {/* Ad-hoc recipient */}
          <div style={{ marginTop: '1rem', padding: '0.75rem', background: '#161929', borderRadius: '6px' }}>
            <div style={{ color: '#94a3b8', fontSize: '0.8rem', marginBottom: '0.5rem', textTransform: 'uppercase' }}>Send to unlisted number</div>
            <div style={{ display: 'flex', gap: '0.5rem', flexWrap: 'wrap' }}>
              <input placeholder="Name (optional)" value={adhocName} onChange={e => setAdhocName(e.target.value)} style={{ ...inputStyle, marginBottom: 0, flex: '1', minWidth: '100px' }} />
              <input placeholder="Phone e.g. 972501234567" value={adhocPhone} onChange={e => setAdhocPhone(e.target.value)} style={{ ...inputStyle, marginBottom: 0, flex: '1', minWidth: '130px' }} />
              <button onClick={addAdhoc} style={btnSecondary}>Add</button>
            </div>
            {adhocList.map(a => (
              <div key={a.id} style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginTop: '0.4rem', color: '#e2e8f0', fontSize: '0.85rem' }}>
                <span>{a.username} — {a.phone}</span>
                <button onClick={() => removeAdhoc(a.id)} style={{ background: 'none', border: 'none', color: '#ef4444', cursor: 'pointer' }}>✕</button>
              </div>
            ))}
          </div>

          <div style={{ marginTop: '0.75rem', color: '#94a3b8', fontSize: '0.85rem' }}>
            {recipientCount} recipient{recipientCount !== 1 ? 's' : ''} selected
          </div>
        </div>

        {/* RIGHT PANEL — Message */}
        <div style={{ width: '360px', background: '#1e2235', borderRadius: '10px', padding: '1rem', flexShrink: 0 }}>
          <h3 style={{ color: '#94a3b8', marginBottom: '0.75rem', fontSize: '0.9rem', textTransform: 'uppercase' }}>Message</h3>

          <label style={labelStyle}>Template</label>
          <select
            value={templateIdx ?? ''}
            onChange={e => applyTemplate(Number(e.target.value))}
            style={{ ...inputStyle }}
          >
            <option value="">— Select template —</option>
            {TEMPLATES.map((t, i) => <option key={i} value={i}>{t.label}</option>)}
          </select>

          <label style={labelStyle}>Message</label>
          <textarea
            rows={8}
            value={message}
            onChange={e => setMessage(e.target.value)}
            placeholder="Type your message here..."
            style={{ ...inputStyle, resize: 'vertical', fontFamily: 'inherit' }}
          />

          <button
            onClick={() => setShowConfirm(true)}
            disabled={sending || recipientCount === 0 || !message.trim()}
            style={{
              ...btnPrimary,
              width: '100%',
              opacity: (sending || recipientCount === 0 || !message.trim()) ? 0.5 : 1,
            }}
          >
            {sending ? 'Sending...' : `Send to ${recipientCount} recipient${recipientCount !== 1 ? 's' : ''}`}
          </button>

          {/* Result toast */}
          {result && !result.error && (
            <div style={{ marginTop: '0.75rem', padding: '0.75rem', borderRadius: '6px', background: result.failCount > 0 ? '#422006' : '#14532d', color: result.failCount > 0 ? '#fdba74' : '#86efac', fontSize: '0.85rem' }}>
              ✓ Sent to {result.successCount} players
              {result.failCount > 0 && ` — ${result.failCount} failed: ${result.failedNumbers.join(', ')}`}
            </div>
          )}
          {result?.error && (
            <div style={{ marginTop: '0.75rem', padding: '0.75rem', borderRadius: '6px', background: '#422006', color: '#fdba74', fontSize: '0.85rem' }}>
              Error: {result.error}
            </div>
          )}
        </div>
      </div>

      {/* Confirm Dialog */}
      {showConfirm && (
        <div style={{ position: 'fixed', inset: 0, background: 'rgba(0,0,0,0.6)', display: 'flex', alignItems: 'center', justifyContent: 'center', zIndex: 1000 }}>
          <div style={{ background: '#1e2235', borderRadius: '10px', padding: '2rem', maxWidth: '400px', width: '90%' }}>
            <h3 style={{ color: '#e2e8f0', marginBottom: '0.75rem' }}>Confirm Send</h3>
            <p style={{ color: '#94a3b8', marginBottom: '1.5rem' }}>
              Sending to <strong style={{ color: '#e2e8f0' }}>{recipientCount} recipient{recipientCount !== 1 ? 's' : ''}</strong> via WhatsApp. Continue?
            </p>
            <div style={{ display: 'flex', gap: '0.75rem', justifyContent: 'flex-end' }}>
              <button onClick={() => setShowConfirm(false)} style={btnSecondary}>Cancel</button>
              <button onClick={handleSend} style={btnPrimary}>Send</button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}

// Shared styles
const inputStyle = {
  width: '100%',
  padding: '7px 10px',
  background: '#0f1120',
  border: '1px solid #2d3148',
  borderRadius: '6px',
  color: '#e2e8f0',
  fontSize: '0.85rem',
  marginBottom: '0.75rem',
  boxSizing: 'border-box',
};
const labelStyle = {
  display: 'block',
  color: '#94a3b8',
  fontSize: '0.8rem',
  marginBottom: '0.3rem',
};
const btnPrimary = {
  padding: '8px 18px',
  background: '#25d366',
  color: '#000',
  border: 'none',
  borderRadius: '6px',
  cursor: 'pointer',
  fontWeight: 600,
  fontSize: '0.9rem',
};
const btnSecondary = {
  padding: '5px 12px',
  background: '#2d3148',
  color: '#94a3b8',
  border: '1px solid #3d4168',
  borderRadius: '6px',
  cursor: 'pointer',
  fontSize: '0.82rem',
};
```

- [ ] **Step 2: Commit**

```bash
cd c:/projects/poker-frontend
rtk git add src/pages/WhatsAppMessages.jsx
rtk git commit -m "feat: add WhatsAppMessages page with player list, filter, and compose"
```

---

## Task 6: Wire up route and NavLink in App.jsx

**Files:**
- Modify: `c:/projects/poker-frontend/src/App.jsx`

- [ ] **Step 1: Add import at the top of App.jsx** (after the last existing import)

Find the line:
```js
import TicketAssets from './pages/TicketAssets';
```

Add after it:
```js
import WhatsAppMessages from './pages/WhatsAppMessages';
```

- [ ] **Step 2: Add NavLink in the admin nav block**

Find:
```jsx
<NavLink to="/wheel">🎡 Wheel</NavLink>
```

Add after it:
```jsx
<NavLink to="/messages">💬 WhatsApp</NavLink>
```

- [ ] **Step 3: Add Route in the admin routes block**

Find:
```jsx
<Route path="/ticket-assets" element={<TicketAssets />} />
```

Add after it:
```jsx
<Route path="/messages" element={<WhatsAppMessages />} />
```

- [ ] **Step 4: Start frontend and verify page loads**

```bash
cd c:/projects/poker-frontend
npm run dev
```

Open `http://localhost:5173/messages` — you should see the two-panel Messages page.

- [ ] **Step 5: Commit**

```bash
cd c:/projects/poker-frontend
rtk git add src/App.jsx
rtk git commit -m "feat: register /messages route and WhatsApp NavLink"
```

---

## Task 7: End-to-end smoke test

- [ ] **Step 1: Start backend**
```bash
cd c:/projects/tracker
./mvnw spring-boot:run
```

- [ ] **Step 2: Start frontend**
```bash
cd c:/projects/poker-frontend
npm run dev
```

- [ ] **Step 3: Test single send**
  1. Open `http://localhost:5173/messages`
  2. Select one player who has a phone number stored
  3. Type a test message
  4. Click Send → confirm dialog → Send
  5. Verify: toast shows "Sent to 1 players"
  6. Check the phone received the WhatsApp message

- [ ] **Step 4: Test group send**
  1. Click "All Negative" to select all players with negative balance
  2. Use the compose area and click Send
  3. Verify toast shows correct count

- [ ] **Step 5: Test filter**
  1. Set `|balance| < 500` — verify list shows only players with balance < -500
  2. Set `|balance| > 100` — verify only players with balance > 100

- [ ] **Step 6: Test ad-hoc number**
  1. Enter a name and phone in the "Send to unlisted number" section
  2. Click Add — verify it appears below with a ✕ button
  3. Send a message — verify the ad-hoc number receives it

---

## ⚠️ Reminder

This feature is **local-only**. Do NOT push to GitHub or deploy to Railway until explicitly decided. The `application.properties` Green-API credentials must stay local.
