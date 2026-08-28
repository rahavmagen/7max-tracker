package com.sevenmax.tracker.scheduler;

import com.sevenmax.tracker.service.GmailEmailService;
import com.sevenmax.tracker.service.WhatsAppHealthEvaluator;
import com.sevenmax.tracker.service.WhatsAppHealthEvaluator.HealthAction;
import com.sevenmax.tracker.service.WhatsAppService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Polls Green API's instance state every 30 minutes and emails the admin list on any
 * down/recovered transition (plus a once-per-24h reminder while still down). See
 * docs/superpowers/specs/2026-08-28-whatsapp-health-check-design.md.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WhatsAppHealthScheduler {

    private final WhatsAppService whatsAppService;
    private final GmailEmailService gmailEmailService;
    private final WhatsAppHealthEvaluator evaluator = new WhatsAppHealthEvaluator();

    @Value("${app.whatsapp-health.notification-emails:}")
    private String notificationEmails;

    private String previousState;
    private LocalDateTime lastAlertAt;

    @Scheduled(fixedRate = 30 * 60 * 1000)
    public void checkHealth() {
        try {
            String currentState;
            try {
                currentState = whatsAppService.checkInstanceState();
            } catch (Exception e) {
                log.warn("WhatsApp health check failed (treating as transient, not down): {}", e.getMessage());
                return;
            }

            LocalDateTime now = LocalDateTime.now();
            HealthAction action = evaluator.evaluate(previousState, currentState, lastAlertAt, now);

            switch (action) {
                case ALERT_DOWN -> {
                    log.error("WhatsApp (Green API) is DOWN — state: {}", currentState);
                    sendAlert("WhatsApp (Green API) is down — state: " + currentState);
                    lastAlertAt = now;
                }
                case ALERT_REMINDER -> {
                    log.warn("WhatsApp (Green API) still down — state: {}", currentState);
                    sendAlert("WhatsApp (Green API) is still down — state: " + currentState);
                    lastAlertAt = now;
                }
                case ALERT_RECOVERED -> {
                    log.info("WhatsApp (Green API) recovered — state: {}", currentState);
                    sendAlert("WhatsApp (Green API) has recovered — state: " + currentState);
                    lastAlertAt = null;
                }
                case NONE -> { /* no state change worth emailing about */ }
            }
            previousState = currentState;
        } catch (Exception e) {
            log.error("WhatsApp health check tick failed unexpectedly: {}", e.getMessage(), e);
        }
    }

    private void sendAlert(String message) {
        if (notificationEmails == null || notificationEmails.isBlank()) return;
        List<String> recipients = Arrays.stream(notificationEmails.split(","))
            .map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toList());
        if (recipients.isEmpty()) return;
        gmailEmailService.send(recipients, "7MAX WhatsApp Alert", message);
    }
}
