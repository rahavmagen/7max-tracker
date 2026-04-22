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
