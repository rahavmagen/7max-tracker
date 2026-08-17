package com.sevenmax.tracker.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Sends email through the Gmail REST API (HTTPS) rather than SMTP, because Railway blocks
 * outbound SMTP ports. Authenticates with a long-lived OAuth2 refresh token for the sending
 * Gmail account (7maxclub@gmail.com) and needs no verified domain — Gmail lets its own
 * account send to any recipient.
 */
@Slf4j
@Service
public class GmailEmailService {

    @Value("${gmail.oauth.client-id:}")
    private String clientId;

    @Value("${gmail.oauth.client-secret:}")
    private String clientSecret;

    @Value("${gmail.oauth.refresh-token:}")
    private String refreshToken;

    @Value("${gmail.from:7Max Club <7maxclub@gmail.com>}")
    private String from;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final HttpClient http = HttpClient.newHttpClient();

    public boolean isConfigured() {
        return notBlank(clientId) && notBlank(clientSecret) && notBlank(refreshToken);
    }

    /** Send a plain-text (UTF-8) email to all recipients. Returns true on success. */
    public boolean send(List<String> to, String subject, String body) {
        if (!isConfigured()) {
            log.warn("Gmail API not configured (client-id/secret/refresh-token missing) — skipping email");
            return false;
        }
        if (to == null || to.isEmpty()) return false;
        try {
            String accessToken = fetchAccessToken();
            if (accessToken == null) return false;

            String mime = buildMime(String.join(", ", to), subject, body);
            String raw = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mime.getBytes(StandardCharsets.UTF_8));
            String payload = MAPPER.writeValueAsString(Map.of("raw", raw));

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create("https://gmail.googleapis.com/gmail/v1/users/me/messages/send"))
                    .header("Authorization", "Bearer " + accessToken)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                log.info("Gmail API email sent to {} recipient(s)", to.size());
                return true;
            }
            log.error("Gmail API send error HTTP {}: {}", resp.statusCode(), resp.body());
            return false;
        } catch (Exception e) {
            log.error("Gmail API send failed: {}", e.getMessage());
            return false;
        }
    }

    /** Exchange the refresh token for a short-lived access token. */
    private String fetchAccessToken() throws Exception {
        String form = "client_id=" + enc(clientId)
                + "&client_secret=" + enc(clientSecret)
                + "&refresh_token=" + enc(refreshToken)
                + "&grant_type=refresh_token";
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("https://oauth2.googleapis.com/token"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            log.error("Gmail OAuth token error HTTP {}: {}", resp.statusCode(), resp.body());
            return null;
        }
        JsonNode json = MAPPER.readTree(resp.body());
        return json.has("access_token") ? json.get("access_token").asText() : null;
    }

    /** Build an RFC 2822 message with UTF-8 subject/body (base64-encoded for non-ASCII safety). */
    private String buildMime(String toHeader, String subject, String body) {
        String encodedSubject = "=?UTF-8?B?"
                + Base64.getEncoder().encodeToString(subject.getBytes(StandardCharsets.UTF_8)) + "?=";
        String encodedBody = Base64.getEncoder().encodeToString(body.getBytes(StandardCharsets.UTF_8));
        return "From: " + from + "\r\n"
                + "To: " + toHeader + "\r\n"
                + "Subject: " + encodedSubject + "\r\n"
                + "MIME-Version: 1.0\r\n"
                + "Content-Type: text/plain; charset=\"UTF-8\"\r\n"
                + "Content-Transfer-Encoding: base64\r\n"
                + "\r\n"
                + encodedBody;
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
