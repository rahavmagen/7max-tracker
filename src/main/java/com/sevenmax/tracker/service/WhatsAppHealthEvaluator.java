package com.sevenmax.tracker.service;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * Pure decision logic for whether a Green API instance-state reading should trigger an
 * alert email. No I/O — the caller supplies the previous/current state strings and
 * timestamps and gets back what to do. See docs/superpowers/specs/2026-08-28-whatsapp-health-check-design.md
 * for the transition table this implements.
 */
public class WhatsAppHealthEvaluator {

    public enum HealthAction { NONE, ALERT_DOWN, ALERT_REMINDER, ALERT_RECOVERED }

    private static final String AUTHORIZED = "authorized";
    private static final Duration REMINDER_INTERVAL = Duration.ofHours(24);

    public HealthAction evaluate(String previousState, String currentState, LocalDateTime lastAlertAt, LocalDateTime now) {
        boolean isHealthy = AUTHORIZED.equals(currentState);

        if (previousState == null) {
            return isHealthy ? HealthAction.NONE : HealthAction.ALERT_DOWN;
        }

        boolean wasHealthy = AUTHORIZED.equals(previousState);

        if (wasHealthy && !isHealthy) return HealthAction.ALERT_DOWN;
        if (!wasHealthy && isHealthy) return HealthAction.ALERT_RECOVERED;
        if (!isHealthy && lastAlertAt != null
                && Duration.between(lastAlertAt, now).compareTo(REMINDER_INTERVAL) >= 0) {
            return HealthAction.ALERT_REMINDER;
        }
        return HealthAction.NONE;
    }
}
