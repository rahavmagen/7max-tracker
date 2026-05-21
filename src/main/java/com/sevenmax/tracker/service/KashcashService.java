package com.sevenmax.tracker.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sevenmax.tracker.entity.KashcashInitiated;
import com.sevenmax.tracker.entity.Player;
import com.sevenmax.tracker.entity.Transaction;
import com.sevenmax.tracker.repository.KashcashInitiatedRepository;
import com.sevenmax.tracker.repository.PlayerRepository;
import com.sevenmax.tracker.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
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

@Slf4j
@Service
@RequiredArgsConstructor
public class KashcashService {

    @Value("${kashcash.base-url}")
    private String baseUrl;

    @Value("${kashcash.username}")
    private String apiUsername;

    @Value("${kashcash.password}")
    private String apiPassword;

    @Value("${kashcash.business-id}")
    private String businessId;

    @Value("${kashcash.pos-vendor-id}")
    private String posVendorId;

    @Value("${app.kashcash.callback-url}")
    private String callbackUrl;

    @Value("${app.kashcash.notification-emails:}")
    private String notificationEmails;

    @Value("${spring.mail.username:}")
    private String fromEmail;

    private final KashcashInitiatedRepository kashcashInitiatedRepository;
    private final PlayerRepository playerRepository;
    private final TransactionRepository transactionRepository;
    private final TransactionService transactionService;
    private final JavaMailSender mailSender;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private volatile String cachedToken;

    // ── Authentication ────────────────────────────────────────────────────────

    private synchronized String authenticate() {
        try {
            Map<String, Object> bodyMap = new HashMap<>();
            bodyMap.put("username", apiUsername);
            bodyMap.put("password", apiPassword);
            bodyMap.put("businessId", businessId);
            bodyMap.put("posVendorId", posVendorId);
            String body = MAPPER.writeValueAsString(bodyMap);
            log.info("KashCash login REQUEST → POST {}/login body={}", baseUrl, body);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/login"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            log.info("KashCash login RESPONSE ← HTTP {} body={}", resp.statusCode(), resp.body());
            if (resp.statusCode() != 200) {
                throw new RuntimeException("KashCash login failed HTTP " + resp.statusCode() + ": " + resp.body());
            }
            JsonNode json = MAPPER.readTree(resp.body());
            // NOTE: adjust "token" to match actual KashCash login response field name
            cachedToken = json.get("token").asText();
            log.info("KashCash authenticated successfully");
            return cachedToken;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("KashCash authentication failed", e);
        }
    }

    private synchronized String getToken() {
        if (cachedToken == null) authenticate();
        return cachedToken;
    }

    // ── Initiate deposit ─────────────────────────────────────────────────────

    public Map<String, String> initiateDeposit(Long playerId, BigDecimal amount) {
        Player player = playerRepository.findById(playerId)
                .orElseThrow(() -> new RuntimeException("Player not found: " + playerId));
        return doInitiateWithRetry(player, amount, false);
    }

    private Map<String, String> doInitiateWithRetry(Player player, BigDecimal amount, boolean retried) {
        try {
            // NOTE: adjust field names to match actual KashCash request/create API
            Map<String, Object> body = new HashMap<>();
            body.put("businessId", businessId);
            body.put("posVendorId", posVendorId);
            body.put("amount", amount);
            body.put("withIframeUrl", true);
            body.put("withAppPaymentIntentUrl", true);
            body.put("withPaymentCode", true);
            body.put("callbackurl", callbackUrl);

            String bodyJson = MAPPER.writeValueAsString(body);
            log.info("KashCash create REQUEST → POST {}/request/create body={}", baseUrl, bodyJson);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/request/create"))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + getToken())
                    .POST(HttpRequest.BodyPublishers.ofString(bodyJson))
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            log.info("KashCash create RESPONSE ← HTTP {} body={}", resp.statusCode(), resp.body());

            if (resp.statusCode() == 401 && !retried) {
                cachedToken = null;
                authenticate();
                return doInitiateWithRetry(player, amount, true);
            }
            if (resp.statusCode() != 200 && resp.statusCode() != 201) {
                throw new RuntimeException("KashCash create failed HTTP " + resp.statusCode() + ": " + resp.body());
            }

            JsonNode json = MAPPER.readTree(resp.body());
            log.info("KashCash create response fields: {}", MAPPER.writeValueAsString(json.fieldNames()));

            String appPaymentIntentUrl = json.has("appPaymentIntentUrl") ? json.get("appPaymentIntentUrl").asText() : "";

            // Extract transactionId from appPaymentIntentUrl query param (e.g. ?transactionId=uuid)
            String transactionId = null;
            if (json.has("transactionId")) {
                transactionId = json.get("transactionId").asText();
            } else if (json.has("id")) {
                transactionId = json.get("id").asText();
            } else if (!appPaymentIntentUrl.isEmpty()) {
                // Parse from: cashiclientsheet://bottomsheet?transactionId=UUID#...
                java.util.regex.Matcher m = java.util.regex.Pattern
                        .compile("[?&]transactionId=([^&#]+)")
                        .matcher(appPaymentIntentUrl);
                if (m.find()) transactionId = m.group(1);
            }
            if (transactionId == null || transactionId.isBlank()) {
                throw new RuntimeException("KashCash create: could not determine transactionId from response: " + resp.body());
            }

            // Prefer iFrameUrl — KashCash Web iframe sends postMessage on payment completion
            // Fall back to SVG QR if no iframe URL
            String iframeUrl = "";
            if (json.has("iFrameUrl") && !json.get("iFrameUrl").asText().isBlank()) {
                iframeUrl = json.get("iFrameUrl").asText();
            } else if (json.has("qrCodeAsSvg") && !json.get("qrCodeAsSvg").asText().isBlank()) {
                iframeUrl = json.get("qrCodeAsSvg").asText();
            } else if (json.has("qrCodeAsString") && !json.get("qrCodeAsString").asText().isBlank()) {
                iframeUrl = json.get("qrCodeAsString").asText();
            }

            KashcashInitiated initiated = new KashcashInitiated();
            initiated.setKashcashTransactionId(transactionId);
            initiated.setPlayerId(player.getId());
            initiated.setAmount(amount);
            kashcashInitiatedRepository.save(initiated);

            log.info("KashCash deposit initiated: player={}, amount={}, txId={}", player.getUsername(), amount, transactionId);
            return Map.of("iframeUrl", iframeUrl, "appPaymentIntentUrl", appPaymentIntentUrl);

        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("KashCash initiate failed", e);
        }
    }

    // ── Webhook ───────────────────────────────────────────────────────────────

    @Transactional
    public void handleWebhook(Map<String, Object> payload) {
        log.info("KashCash webhook RECEIVED payload={}", payload);
        // NOTE: adjust "status" field name if KashCash uses a different key
        Object statusObj = payload.get("status");
        if (statusObj == null) {
            log.warn("KashCash webhook: missing status field, payload={}", payload);
            return;
        }
        int status = Integer.parseInt(statusObj.toString());
        if (status != 1) {
            log.info("KashCash webhook: status={}, ignoring", status);
            return;
        }

        // NOTE: adjust "transactionId" field name if KashCash uses a different key
        Object txIdObj = payload.get("transactionId");
        if (txIdObj == null) {
            log.warn("KashCash webhook: missing transactionId, payload={}", payload);
            return;
        }
        String kashcashTxId = txIdObj.toString();

        KashcashInitiated initiated = kashcashInitiatedRepository
                .findByKashcashTransactionId(kashcashTxId)
                .orElse(null);
        if (initiated == null) {
            log.warn("KashCash webhook: unknown transactionId={}", kashcashTxId);
            return;
        }
        if (Boolean.TRUE.equals(initiated.getProcessed())) {
            log.info("KashCash webhook: already processed transactionId={}", kashcashTxId);
            return;
        }

        // Set processed=true BEFORE creating Transaction — prevents duplicate if webhook fires twice
        initiated.setProcessed(true);
        kashcashInitiatedRepository.save(initiated);

        finalizeWithKashCash(kashcashTxId);

        Player player = playerRepository.findById(initiated.getPlayerId())
                .orElseThrow(() -> new RuntimeException("Player not found: " + initiated.getPlayerId()));

        Transaction tx = new Transaction();
        tx.setPlayer(player);
        tx.setType(Transaction.Type.KASHCASH_DEPOSIT);
        tx.setAmount(initiated.getAmount());
        tx.setMethod(Transaction.Method.KASHCASH);
        tx.setNotes(kashcashTxId);
        tx.setChipsConfirmed(false);
        tx.setTransactionDate(LocalDate.now());
        transactionService.addTransaction(tx);

        sendDepositEmail(player, initiated.getAmount(), kashcashTxId);
        log.info("KashCash deposit processed: player={}, amount={}, txId={}", player.getUsername(), initiated.getAmount(), kashcashTxId);
    }

    private void finalizeWithKashCash(String kashcashTxId) {
        try {
            // NOTE: adjust endpoint path and field name to match actual KashCash finalize API
            String body = MAPPER.writeValueAsString(Map.of("businessId", businessId, "transactionId", kashcashTxId));
            log.info("KashCash finalize REQUEST → POST {}/request/finalize-payment body={}", baseUrl, body);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/request/finalize-payment"))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + getToken())
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            log.info("KashCash finalize RESPONSE ← HTTP {} body={}", resp.statusCode(), resp.body());
        } catch (Exception e) {
            log.error("KashCash finalize failed for txId={}: {}", kashcashTxId, e.getMessage());
        }
    }

    private void sendDepositEmail(Player player, BigDecimal amount, String kashcashTxId) {
        if (notificationEmails == null || notificationEmails.isBlank()) return;
        try {
            String[] recipients = Arrays.stream(notificationEmails.split(","))
                    .map(String::trim).filter(s -> !s.isEmpty()).toArray(String[]::new);
            if (recipients.length == 0) return;
            SimpleMailMessage msg = new SimpleMailMessage();
            msg.setFrom(fromEmail);
            msg.setTo(recipients);
            msg.setSubject("New KashCash Deposit - Action Required");
            msg.setText(String.format(
                    "Player: %s (%s)\nAmount: \u20aa%s\nTime: %s\nKashCash TxID: %s\n\nPlease add chips to the player's account.",
                    player.getUsername(),
                    player.getFullName() != null ? player.getFullName() : "",
                    amount.toPlainString(),
                    LocalDateTime.now(),
                    kashcashTxId
            ));
            mailSender.send(msg);
            log.info("KashCash deposit email sent for player={}", player.getUsername());
        } catch (Exception e) {
            log.error("Failed to send KashCash deposit email: {}", e.getMessage());
        }
    }

    // ── Admin queries ─────────────────────────────────────────────────────────

    public List<Map<String, Object>> getPending() {
        return transactionRepository.findPendingKashcashDeposits()
                .stream().map(this::toDto).collect(Collectors.toList());
    }

    public Map<String, Object> getHistory(LocalDate from, LocalDate to) {
        List<Transaction> txs;
        if (from != null && to != null) {
            txs = transactionRepository.findKashcashDepositsBetween(
                    from.atStartOfDay(), to.plusDays(1).atStartOfDay());
        } else {
            txs = transactionRepository.findAllKashcashDeposits();
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
        if (tx.getType() != Transaction.Type.KASHCASH_DEPOSIT) {
            throw new IllegalArgumentException("Not a KashCash deposit transaction");
        }
        tx.setChipsConfirmed(true);
        transactionRepository.save(tx);
    }

    public List<Map<String, Object>> getMyDeposits(Long playerId) {
        return transactionRepository.findKashcashDepositsByPlayerId(playerId)
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
        m.put("kashcashTxId", tx.getNotes());
        m.put("chipsConfirmed", Boolean.TRUE.equals(tx.getChipsConfirmed()));
        m.put("date", tx.getCreatedAt() != null ? tx.getCreatedAt().toString() : null);
        return m;
    }
}
