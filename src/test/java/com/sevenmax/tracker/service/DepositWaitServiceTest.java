package com.sevenmax.tracker.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.context.request.async.DeferredResult;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DepositWaitServiceTest {

    @Mock GrowDepositService growDepositService;
    @Mock KashcashService kashcashService;
    @Mock AdminDepositService adminDepositService;

    DepositWaitService depositWaitService;

    @BeforeEach
    void setUp() {
        depositWaitService = new DepositWaitService(growDepositService, kashcashService, adminDepositService);
    }

    @Test
    void waitForPendingReturnsImmediatelyWithAdminDepositTaggedBySource() {
        when(growDepositService.getPending()).thenReturn(List.of());
        when(kashcashService.getPending()).thenReturn(List.of());
        when(adminDepositService.getPending()).thenReturn(List.of(Map.of("id", 42L, "username", "someUser")));

        DeferredResult<List<Map<String, Object>>> result = depositWaitService.waitForPending(5000);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> value = (List<Map<String, Object>>) result.getResult();
        assertThat(value).hasSize(1);
        assertThat(value.get(0)).containsEntry("source", "ADMIN").containsEntry("id", 42L);
    }

    @Test
    void waitForPendingCombinesAllThreeSources() {
        when(growDepositService.getPending()).thenReturn(List.of(Map.of("id", 1L)));
        when(kashcashService.getPending()).thenReturn(List.of(Map.of("id", 2L)));
        when(adminDepositService.getPending()).thenReturn(List.of(Map.of("id", 3L)));

        DeferredResult<List<Map<String, Object>>> result = depositWaitService.waitForPending(5000);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> value = (List<Map<String, Object>>) result.getResult();
        assertThat(value).hasSize(3);
    }
}
