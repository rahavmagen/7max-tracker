package com.sevenmax.tracker.service;

import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.sevenmax.tracker.entity.GrowInitiated;
import com.sevenmax.tracker.entity.Player;
import com.sevenmax.tracker.entity.Transaction;
import com.sevenmax.tracker.repository.GrowInitiatedRepository;
import com.sevenmax.tracker.repository.PlayerRepository;
import com.sevenmax.tracker.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Deposits via Grow payment links, created through a Make.com scenario (Custom Webhook →
 * Grow "Create Payment Link" → Webhook Response) instead of calling Grow's paid direct API.
 * Grow itself calls back {@link #handleWebhook} directly (not through Make) when payment completes.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GrowDepositService {

    @Value("${grow.make.webhook-url}")
    private String makeWebhookUrl;

    @Value("${grow.make.api-key}")
    private String makeApiKey;

    @Value("${app.grow.notification-emails:}")
    private String notificationEmails;

    @Value("${app.grow.notification-whatsapp:}")
    private String notificationWhatsApp;

    private final GrowInitiatedRepository growInitiatedRepository;
    private final PlayerRepository playerRepository;
    private final TransactionRepository transactionRepository;
    private final TransactionService transactionService;
    private final WhatsAppService whatsAppService;
    private final GmailEmailService gmailEmailService;

    // The Make.com webhook response embeds raw \r\n inside string values (from how its Body
    // template was authored), which is invalid strict JSON - tolerate unescaped control chars.
    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .enable(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS)
            .build();
    private final HttpClient httpClient = HttpClient.newHttpClient();

    // ── Initiate deposit ─────────────────────────────────────────────────────

    public Map<String, Object> initiateDeposit(Long playerId, BigDecimal amount) {
        Player player = playerRepository.findById(playerId)
                .orElseThrow(() -> new RuntimeException("Player not found: " + playerId));
        // Grow requires a valid mobile on the payment link; a missing/malformed one is rejected with
        // error 427 (invalid pageFieldSettings[phone][value]). Fail fast with a clear, actionable code
        // so the player sees "your phone is missing/invalid" instead of a generic "payment rejected".
        String phone = normalizeIsraeliMobile(player.getPhone());
        if (phone == null) {
            throw new IllegalArgumentException("INVALID_PHONE");
        }
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("amount", amount);
            // Grow requires a full name of at least two words - many players have no fullName on
            // file, and their username is often a single token (or has symbols/digits), so fall
            // back to a fixed two-word placeholder rather than the username.
            String fullName = player.getFullName() != null && !player.getFullName().isBlank()
                    ? player.getFullName() : null;
            body.put("fullName", isTwoValidNameWords(fullName) ? fullName : "Club Guest");
            body.put("phone", phone);
            // Grow requires a valid email format (it rejects a malformed one), but sendingMode=none
            // means it never actually emails it. Put the player's USERNAME as the local part so it
            // shows on Grow's confirmation and identifies who paid. Sanitize to a valid local-part
            // (usernames can contain spaces/symbols, e.g. "432 hz"), falling back to player<id>.
            String emailLocal = player.getUsername() == null ? "" : player.getUsername()
                    .replaceAll("[^A-Za-z0-9._-]+", "-")   // runs of invalid chars (space, ?, Hebrew…) -> one dash
                    .replaceAll("^[.-]+|[.-]+$", "");        // trim leading/trailing dots/dashes
            if (emailLocal.isBlank()) emailLocal = "player" + player.getId();
            body.put("email", emailLocal + "@7max.club");

            String bodyJson = MAPPER.writeValueAsString(body);
            log.info("Grow create-link REQUEST → POST {} body={}", makeWebhookUrl, bodyJson);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(makeWebhookUrl))
                    .header("Content-Type", "application/json")
                    .header("x-make-apikey", makeApiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(bodyJson))
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            log.info("Grow create-link RESPONSE ← HTTP {} body={}", resp.statusCode(), resp.body());

            if (resp.statusCode() != 200) {
                // Make/Grow rejects a bad phone with a 4xx that names the phone field (error 427).
                String bodyLc = resp.body() == null ? "" : resp.body().toLowerCase();
                if (bodyLc.contains("phone")) throw new IllegalArgumentException("INVALID_PHONE");
                throw new RuntimeException("Grow link creation failed HTTP " + resp.statusCode() + ": " + resp.body());
            }

            JsonNode json = MAPPER.readTree(resp.body());
            String paymentLinkUrl = trim(json, "paymentLinkUrl");
            String processId = trim(json, "paymentLinkProcessId");
            String processToken = trim(json, "paymentLinkProcessToken");
            if (paymentLinkUrl.isEmpty() || processId.isEmpty()) {
                throw new RuntimeException("Grow link creation: missing paymentLinkUrl/paymentLinkProcessId in response: " + resp.body());
            }

            GrowInitiated initiated = new GrowInitiated();
            initiated.setPaymentLinkProcessId(processId);
            initiated.setPaymentLinkProcessToken(processToken);
            initiated.setPlayerId(player.getId());
            initiated.setAmount(amount);
            growInitiatedRepository.save(initiated);

            log.info("Grow deposit initiated: player={}, amount={}, processId={}", player.getUsername(), amount, processId);
            return Map.of("paymentLinkUrl", paymentLinkUrl, "processId", processId);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Grow initiate failed", e);
        }
    }

    private static String trim(JsonNode json, String field) {
        return json.has(field) && !json.get(field).isNull() ? json.get(field).asText().trim() : "";
    }

    /** Normalize to a plain Israeli mobile (05XXXXXXXX) if valid, else null. Accepts +972, spaces, dashes. */
    static String normalizeIsraeliMobile(String raw) {
        if (raw == null) return null;
        String d = raw.replaceAll("[^0-9]", "");
        if (d.startsWith("972")) d = "0" + d.substring(3);
        return d.matches("05\\d{8}") ? d : null;
    }

    /** Grow requires a full name of at least two words, each at least 2 letters long. */
    static boolean isTwoValidNameWords(String name) {
        if (name == null) return false;
        String[] words = name.trim().split("\\s+");
        return words.length >= 2 && Arrays.stream(words).allMatch(w -> w.length() >= 2);
    }

    // ── Webhook (direct from Grow, not through Make) ────────────────────────────

    @Transactional
    @SuppressWarnings("unchecked")
    public void handleWebhook(Map<String, Object> payload) {
        log.info("Grow payment RECEIVED payload={}", payload);
        Object dataObj = payload.get("data");
        if (!(dataObj instanceof Map)) {
            log.warn("Grow payment: missing/invalid data object");
            return;
        }
        Map<String, Object> data = (Map<String, Object>) dataObj;

        Object statusCodeObj = data.get("statusCode");
        boolean isPaid = statusCodeObj != null && "2".equals(statusCodeObj.toString());
        if (!isPaid) {
            log.info("Grow payment: statusCode={}, ignoring (not paid)", statusCodeObj);
            return;
        }

        Object processIdObj = data.get("paymentLinkProcessId");
        if (processIdObj == null) {
            log.warn("Grow payment: missing paymentLinkProcessId");
            return;
        }
        String processId = processIdObj.toString();

        GrowInitiated initiated = growInitiatedRepository.findByPaymentLinkProcessId(processId).orElse(null);
        if (initiated == null) {
            log.warn("Grow payment: unknown paymentLinkProcessId={}", processId);
            throw new RuntimeException("Grow: unknown paymentLinkProcessId=" + processId);
        }
        // Atomically claim so a retried callback never double-credits.
        if (growInitiatedRepository.claimForProcessing(initiated.getId()) == 0) {
            log.info("Grow payment: already processed processId={}", processId);
            return;
        }

        Player player = playerRepository.findById(initiated.getPlayerId())
                .orElseThrow(() -> new RuntimeException("Player not found: " + initiated.getPlayerId()));

        Transaction tx = new Transaction();
        tx.setPlayer(player);
        tx.setType(Transaction.Type.GROW_DEPOSIT);
        tx.setAmount(initiated.getAmount());
        tx.setMethod(Transaction.Method.GROW);
        tx.setNotes(processId);
        tx.setChipsConfirmed(false);
        tx.setTransactionDate(LocalDate.now());
        transactionService.addTransaction(tx);

        sendDepositEmail(player, initiated.getAmount(), processId);
        sendDepositWhatsApp(player, initiated.getAmount());
        log.info("Grow deposit processed: player={}, amount={}, processId={}", player.getUsername(), initiated.getAmount(), processId);
    }

    private void sendDepositWhatsApp(Player player, BigDecimal amount) {
        if (notificationWhatsApp == null || notificationWhatsApp.isBlank()) return;
        try {
            List<String> numbers = Arrays.stream(notificationWhatsApp.split(","))
                    .map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toList());
            if (numbers.isEmpty()) return;
            String msg = String.format("💰 New Grow deposit: ₪%s from %s. Check the site.",
                    amount.toPlainString(), player.getUsername());
            List<String> failed = whatsAppService.sendToAll(numbers, msg);
            if (!failed.isEmpty()) log.warn("Grow deposit WhatsApp failed for: {}", failed);
        } catch (Exception e) {
            log.error("Failed to send Grow deposit WhatsApp: {}", e.getMessage());
        }
    }

    private void sendDepositEmail(Player player, BigDecimal amount, String processId) {
        if (notificationEmails == null || notificationEmails.isBlank()) return;
        try {
            List<String> recipients = Arrays.stream(notificationEmails.split(","))
                    .map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toList());
            if (recipients.isEmpty()) return;

            String subject = String.format("New Grow deposit: ₪%s from player %s",
                    amount.toPlainString(), player.getUsername());
            String body = "New Grow deposit received - check the web site.\n\n"
                    + "Player: " + player.getUsername() + "\n"
                    + "Amount: ₪" + amount.toPlainString() + "\n"
                    + "Grow processId: " + processId;

            gmailEmailService.send(recipients, subject, body);
        } catch (Exception e) {
            log.error("Failed to send Grow deposit email: {}", e.getMessage());
        }
    }

    // ── Admin queries ─────────────────────────────────────────────────────────

    public List<Map<String, Object>> getPending() {
        return transactionRepository.findPendingGrowDeposits()
                .stream().map(this::toDto).collect(Collectors.toList());
    }

    public Map<String, Object> getHistory(LocalDate from, LocalDate to) {
        List<Transaction> txs;
        if (from != null && to != null) {
            txs = transactionRepository.findGrowDepositsBetween(
                    from.atStartOfDay(), to.plusDays(1).atStartOfDay());
        } else {
            txs = transactionRepository.findAllGrowDeposits();
        }
        List<Map<String, Object>> rows = txs.stream().map(this::toDto).collect(Collectors.toList());
        BigDecimal total = txs.stream().map(Transaction::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return Map.of("rows", rows, "total", total);
    }

    @Transactional
    public void confirmChips(Long transactionId) {
        Transaction tx = transactionRepository.findById(transactionId)
                .orElseThrow(() -> new RuntimeException("Transaction not found: " + transactionId));
        if (tx.getType() != Transaction.Type.GROW_DEPOSIT) {
            throw new IllegalArgumentException("Not a Grow deposit transaction");
        }
        tx.setChipsConfirmed(true);
        transactionRepository.save(tx);
    }

    public List<Map<String, Object>> getMyDeposits(Long playerId) {
        return transactionRepository.findGrowDepositsByPlayerId(playerId)
                .stream().map(this::toDto).collect(Collectors.toList());
    }

    // ── DTO ───────────────────────────────────────────────────────────────────

    private Map<String, Object> toDto(Transaction tx) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", tx.getId());
        m.put("playerId", tx.getPlayer().getId());
        m.put("username", tx.getPlayer().getUsername());
        m.put("fullName", tx.getPlayer().getFullName());
        m.put("amount", tx.getAmount());
        m.put("growProcessId", tx.getNotes());
        m.put("chipsConfirmed", Boolean.TRUE.equals(tx.getChipsConfirmed()));
        m.put("date", tx.getCreatedAt() != null ? tx.getCreatedAt().toString() : null);
        return m;
    }
}
