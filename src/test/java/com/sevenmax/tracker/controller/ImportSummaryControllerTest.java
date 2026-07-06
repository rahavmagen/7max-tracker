package com.sevenmax.tracker.controller;

import com.sevenmax.tracker.entity.ImportSummary;
import com.sevenmax.tracker.entity.PlayerTransfer;
import com.sevenmax.tracker.entity.Transaction;
import com.sevenmax.tracker.repository.ImportSummaryRepository;
import com.sevenmax.tracker.repository.PlayerTransferRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ImportSummaryControllerTest {

    @Mock ImportSummaryRepository importSummaryRepository;
    @Mock PlayerTransferRepository playerTransferRepository;

    ImportSummaryController controller;

    @BeforeEach
    void setUp() {
        controller = new ImportSummaryController(importSummaryRepository, playerTransferRepository);
    }

    private Authentication adminAuth() {
        return new UsernamePasswordAuthenticationToken("admin1", null,
            java.util.List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
    }

    private Authentication playerAuth() {
        return new UsernamePasswordAuthenticationToken("player1", null,
            java.util.List.of(new SimpleGrantedAuthority("ROLE_PLAYER")));
    }

    @Test
    void rejectsPlayerRole() {
        ResponseEntity<?> response = controller.setBankBalance(Map.of("bankBalance", "1000"), playerAuth());

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        verifyNoInteractions(importSummaryRepository, playerTransferRepository);
    }

    @Test
    void rejectsMissingBankBalance() {
        ResponseEntity<?> response = controller.setBankBalance(Map.of(), adminAuth());

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        verifyNoInteractions(importSummaryRepository, playerTransferRepository);
    }

    @Test
    void rejectsNegativeBankBalance() {
        ResponseEntity<?> response = controller.setBankBalance(Map.of("bankBalance", "-50"), adminAuth());

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        verifyNoInteractions(importSummaryRepository, playerTransferRepository);
    }

    @Test
    void rejectsNonNumericBankBalance() {
        ResponseEntity<?> response = controller.setBankBalance(Map.of("bankBalance", "abc"), adminAuth());

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        verifyNoInteractions(importSummaryRepository, playerTransferRepository);
    }

    @Test
    void storesSignedDeltaAndUpdatesSummary() {
        ImportSummary existing = new ImportSummary();
        existing.setId(1L);
        existing.setBankDeposits(BigDecimal.valueOf(17473.84));
        when(importSummaryRepository.findById(1L)).thenReturn(Optional.of(existing));

        ResponseEntity<?> response = controller.setBankBalance(
            Map.of("bankBalance", "20000", "notes", "reconciled with bank app"), adminAuth());

        assertThat(response.getStatusCode().value()).isEqualTo(200);

        ArgumentCaptor<ImportSummary> summaryCaptor = ArgumentCaptor.forClass(ImportSummary.class);
        verify(importSummaryRepository).save(summaryCaptor.capture());
        assertThat(summaryCaptor.getValue().getBankDeposits()).isEqualByComparingTo(BigDecimal.valueOf(20000));

        ArgumentCaptor<PlayerTransfer> auditCaptor = ArgumentCaptor.forClass(PlayerTransfer.class);
        verify(playerTransferRepository).save(auditCaptor.capture());
        PlayerTransfer audit = auditCaptor.getValue();
        assertThat(audit.getMethod()).isEqualTo(Transaction.Method.ADJUSTMENT);
        assertThat(audit.getAmount()).isEqualByComparingTo(BigDecimal.valueOf(2526.16)); // 20000 - 17473.84
        assertThat(audit.getNotes()).contains("17473.84").contains("20000").contains("reconciled with bank app");
        assertThat(audit.getConfirmed()).isTrue();
    }
}
