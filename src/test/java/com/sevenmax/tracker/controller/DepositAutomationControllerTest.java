package com.sevenmax.tracker.controller;

import com.sevenmax.tracker.service.AdminDepositService;
import com.sevenmax.tracker.service.DepositWaitService;
import com.sevenmax.tracker.service.GmailEmailService;
import com.sevenmax.tracker.service.GrowDepositService;
import com.sevenmax.tracker.service.KashcashService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DepositAutomationControllerTest {

    private static final String KEY = "sevenmax-deposit-auto-2026-qT7mN";

    @Mock DepositWaitService depositWaitService;
    @Mock GrowDepositService growDepositService;
    @Mock KashcashService kashcashService;
    @Mock AdminDepositService adminDepositService;
    @Mock GmailEmailService gmailEmailService;

    DepositAutomationController controller;

    @BeforeEach
    void setUp() {
        controller = new DepositAutomationController(depositWaitService, growDepositService, kashcashService, adminDepositService, gmailEmailService);
    }

    private Map<String, Object> body(String kind) {
        return Map.of("username", "baz55", "amount", "200", "kind", kind, "reason", "Send Out shows EpokerGishgush");
    }

    @Test
    void reportFailure_emailsHebrewManualHandlingAlertWithDepositDetails() {
        when(gmailEmailService.send(anyList(), anyString(), anyString())).thenReturn(true);

        ResponseEntity<?> resp = controller.reportFailure("grow", 5231L, body("wrong_player"), KEY);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(gmailEmailService).send(eq(List.of("rahavm@gmail.com")), subject.capture(), text.capture());
        assertThat(subject.getValue()).contains("נדרש טיפול ידני").contains("GROW").contains("baz55").contains("₪200");
        assertThat(text.getValue()).contains("החיפוש הגיע לשחקן אחר").contains("5231").contains("baz55");
    }

    @Test
    void reportFailure_unverifiedWarnsToCheckTradeRecordBeforeLoading() {
        when(gmailEmailService.send(anyList(), anyString(), anyString())).thenReturn(true);
        controller.reportFailure("grow", 5231L, body("unverified"), KEY);
        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(gmailEmailService).send(anyList(), anyString(), text.capture());
        assertThat(text.getValue()).contains("Trade Record").contains("ייתכן שהצ'יפים כבר נשלחו");
    }

    @Test
    void reportFailure_confirmFailedSaysNotToLoadAgain() {
        when(gmailEmailService.send(anyList(), anyString(), anyString())).thenReturn(true);
        controller.reportFailure("kashcash", 42L, body("confirm_failed"), KEY);
        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(gmailEmailService).send(anyList(), anyString(), text.capture());
        assertThat(text.getValue()).contains("אין לטעון שוב");
    }

    @Test
    void reportFailure_rejectsUnknownKindWithoutEmailing() {
        ResponseEntity<?> resp = controller.reportFailure("grow", 5231L, body("something_else"), KEY);
        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        verifyNoInteractions(gmailEmailService);
    }

    @Test
    void reportFailure_rejectsWrongApiKey() {
        ResponseEntity<?> resp = controller.reportFailure("grow", 5231L, body("wrong_player"), "nope");
        assertThat(resp.getStatusCode().value()).isEqualTo(401);
        verifyNoInteractions(gmailEmailService);
    }

    @Test
    void reportFailure_returns502WhenEmailNotSent_soTheCallerRetries() {
        when(gmailEmailService.send(anyList(), anyString(), anyString())).thenReturn(false);
        ResponseEntity<?> resp = controller.reportFailure("grow", 5231L, body("max_retries"), KEY);
        assertThat(resp.getStatusCode().value()).isEqualTo(502);
    }
}
