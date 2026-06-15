package com.sevenmax.tracker.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sevenmax.tracker.entity.Player;
import com.sevenmax.tracker.repository.PlayerRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class MissingNameNotificationService {

    @Value("${app.missing-names.notification-emails:}")
    private String notificationEmails;

    @Value("${resend.api-key:}")
    private String resendApiKey;

    @Value("${resend.from-email:noreply@7max.club}")
    private String fromEmail;

    private final PlayerRepository playerRepository;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newHttpClient();

    /** Players with no name who have chips (i.e. played) — email the list if any are found. */
    public void checkAndNotify() {
        List<Player> flagged = playerRepository.findAll().stream()
            .filter(p -> p.getFullName() == null || p.getFullName().trim().isEmpty())
            .filter(p -> p.getCurrentChips() != null && p.getCurrentChips().compareTo(BigDecimal.ZERO) > 0)
            .collect(Collectors.toList());

        if (flagged.isEmpty()) return;
        sendEmail(flagged);
    }

    private void sendEmail(List<Player> flagged) {
        if (notificationEmails == null || notificationEmails.isBlank()) return;
        if (resendApiKey == null || resendApiKey.isBlank()) {
            log.warn("Resend API key not configured, skipping missing-name email");
            return;
        }
        try {
            List<String> recipients = Arrays.stream(notificationEmails.split(","))
                    .map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toList());
            if (recipients.isEmpty()) return;

            String subject = String.format("7MAX - %d player(s) with no name played", flagged.size());

            StringBuilder text = new StringBuilder("The following players played but have no name assigned:\n\n");
            for (Player p : flagged) {
                text.append(String.format("- %s | phone: %s | club ID: %s | chips: ₪%s | balance: ₪%s%n",
                    p.getUsername(),
                    p.getPhone() != null ? p.getPhone() : "-",
                    p.getClubPlayerId() != null ? p.getClubPlayerId() : "-",
                    p.getCurrentChips().toPlainString(),
                    p.getBalance() != null ? p.getBalance().toPlainString() : "0"));
            }
            Map<String, Object> body = new HashMap<>();
            body.put("from", fromEmail);
            body.put("to", recipients);
            body.put("subject", subject);
            body.put("text", text.toString());

            String bodyJson = MAPPER.writeValueAsString(body);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.resend.com/emails"))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + resendApiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(bodyJson))
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                log.info("Missing-name notification email sent for {} player(s)", flagged.size());
            } else {
                log.error("Resend API error HTTP {}: {}", resp.statusCode(), resp.body());
            }
        } catch (Exception e) {
            log.error("Failed to send missing-name notification email: {}", e.getMessage());
        }
    }
}
