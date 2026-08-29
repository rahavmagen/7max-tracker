package com.sevenmax.tracker.service;

import com.sevenmax.tracker.entity.Player;
import com.sevenmax.tracker.repository.PlayerRepository;
import com.sevenmax.tracker.repository.GameResultRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class MissingNameNotificationService {

    @Value("${app.missing-names.notification-emails:}")
    private String notificationEmails;

    private final PlayerRepository playerRepository;
    private final GameResultRepository gameResultRepository;
    private final GmailEmailService gmailEmailService;

    /**
     * Players with no name who have ACTUALLY PLAYED (>= 1 game result) and currently hold chips —
     * email the list if any are found. Previously this used "has chips" alone (no play-history
     * check), which wrongly flagged agent players who hold agent-given chips but never played
     * (e.g. m11223344); the play-history filter was added, but a zero-chip player who played in
     * the past and has since cashed out has nothing left to reconcile, so they're excluded too.
     */
    public void checkAndNotify() {
        java.util.Set<Long> playedIds = new java.util.HashSet<>(gameResultRepository.findPlayerIdsWithGameResults());
        List<Player> flagged = playerRepository.findAll().stream()
            .filter(p -> p.getFullName() == null || p.getFullName().trim().isEmpty())
            .filter(p -> playedIds.contains(p.getId()))
            .filter(p -> p.getCurrentChips() != null && p.getCurrentChips().compareTo(BigDecimal.ZERO) != 0)
            .collect(Collectors.toList());

        if (flagged.isEmpty()) return;
        sendEmail(flagged);
    }

    private void sendEmail(List<Player> flagged) {
        if (notificationEmails == null || notificationEmails.isBlank()) return;
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
                    p.getCurrentChips() != null ? p.getCurrentChips().toPlainString() : "0",
                    p.getBalance() != null ? p.getBalance().toPlainString() : "0"));
            }

            boolean sent = gmailEmailService.send(recipients, subject, text.toString());
            if (sent) {
                log.info("Missing-name notification email sent for {} player(s)", flagged.size());
            }
        } catch (Exception e) {
            log.error("Failed to send missing-name notification email: {}", e.getMessage());
        }
    }
}
