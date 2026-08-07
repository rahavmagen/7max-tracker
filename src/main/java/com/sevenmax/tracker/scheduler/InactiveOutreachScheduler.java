package com.sevenmax.tracker.scheduler;

import com.sevenmax.tracker.service.InactiveOutreachService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Fires the weekly inactive-players WhatsApp nudge. */
@Slf4j
@Component
@RequiredArgsConstructor
public class InactiveOutreachScheduler {

    private final InactiveOutreachService inactiveOutreachService;

    // Sunday 10:00 Israel time.
    @Scheduled(cron = "0 0 10 * * SUN", zone = "Asia/Jerusalem")
    public void weeklyInactiveNudge() {
        try {
            inactiveOutreachService.runWeekly();
        } catch (Exception e) {
            log.error("Weekly inactive-players nudge failed: {}", e.getMessage(), e);
        }
    }
}
