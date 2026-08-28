package com.sevenmax.tracker.service;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static com.sevenmax.tracker.service.WhatsAppHealthEvaluator.HealthAction.*;
import static org.assertj.core.api.Assertions.assertThat;

class WhatsAppHealthEvaluatorTest {

    private final WhatsAppHealthEvaluator evaluator = new WhatsAppHealthEvaluator();
    private final LocalDateTime now = LocalDateTime.of(2026, 8, 28, 12, 0);

    @Test
    void firstCheckEver_alreadyBroken_alertsDown() {
        assertThat(evaluator.evaluate(null, "notAuthorized", null, now)).isEqualTo(ALERT_DOWN);
    }

    @Test
    void firstCheckEver_healthy_noAlert() {
        assertThat(evaluator.evaluate(null, "authorized", null, now)).isEqualTo(NONE);
    }

    @Test
    void wasHealthy_nowBroken_alertsDown() {
        assertThat(evaluator.evaluate("authorized", "notAuthorized", null, now)).isEqualTo(ALERT_DOWN);
    }

    @Test
    void wasBroken_nowHealthy_alertsRecovered() {
        LocalDateTime lastAlert = now.minusHours(1);
        assertThat(evaluator.evaluate("notAuthorized", "authorized", lastAlert, now)).isEqualTo(ALERT_RECOVERED);
    }

    @Test
    void stillBroken_recentAlert_noReminderYet() {
        LocalDateTime lastAlert = now.minusHours(2);
        assertThat(evaluator.evaluate("notAuthorized", "notAuthorized", lastAlert, now)).isEqualTo(NONE);
    }

    @Test
    void stillBroken_24hSinceLastAlert_sendsReminder() {
        LocalDateTime lastAlert = now.minusHours(25);
        assertThat(evaluator.evaluate("notAuthorized", "notAuthorized", lastAlert, now)).isEqualTo(ALERT_REMINDER);
    }

    @Test
    void stillHealthy_noAlert() {
        assertThat(evaluator.evaluate("authorized", "authorized", null, now)).isEqualTo(NONE);
    }

    @Test
    void otherUnhealthyStates_blockedAndSleepMode_alertDown() {
        assertThat(evaluator.evaluate("authorized", "blocked", null, now)).isEqualTo(ALERT_DOWN);
        assertThat(evaluator.evaluate("authorized", "sleepMode", null, now)).isEqualTo(ALERT_DOWN);
    }
}
