package com.sevenmax.tracker.service;

import com.sevenmax.tracker.entity.AgentLedgerEntry;
import com.sevenmax.tracker.entity.LastSettlementDate;
import com.sevenmax.tracker.entity.Player;
import com.sevenmax.tracker.repository.AgentLedgerEntryRepository;
import com.sevenmax.tracker.repository.LastSettlementDateRepository;
import com.sevenmax.tracker.repository.PlayerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentServiceTest {

    @Mock PlayerRepository playerRepository;
    @Mock AgentLedgerEntryRepository agentLedgerEntryRepository;
    @Mock LastSettlementDateRepository lastSettlementDateRepository;

    AgentService agentService;

    @BeforeEach
    void setUp() {
        agentService = new AgentService(
            playerRepository, null, null, null, null,
            agentLedgerEntryRepository, lastSettlementDateRepository, null
        );
    }

    private Player player(Long id, Boolean isAgent, Long agentId, String username) {
        Player p = new Player();
        p.setId(id);
        p.setIsAgent(isAgent);
        p.setAgentId(agentId);
        p.setUsername(username);
        return p;
    }

    @Test
    void resolveSuperAgentNames_regularPlayerUnderTopLevelAgent_mapsToThatAgent() {
        Player agent = player(1L, true, null, "TopAgent");
        Player p = player(2L, false, 1L, "regularPlayer");
        when(playerRepository.findAll()).thenReturn(List.of(agent, p));

        Map<Long, String> result = agentService.resolveSuperAgentNames();

        assertThat(result.get(2L)).isEqualTo("TopAgent");
    }

    @Test
    void resolveSuperAgentNames_playerUnderSubAgent_rollsUpToSuperAgent() {
        Player superAgent = player(1L, true, null, "SuperAgent");
        Player subAgent = player(2L, true, 1L, "SubAgent");
        Player p = player(3L, false, 2L, "regularPlayer");
        when(playerRepository.findAll()).thenReturn(List.of(superAgent, subAgent, p));

        Map<Long, String> result = agentService.resolveSuperAgentNames();

        assertThat(result.get(3L)).isEqualTo("SuperAgent");
        assertThat(result.get(2L)).isEqualTo("SuperAgent");
    }

    @Test
    void resolveSuperAgentNames_topLevelAgentThemselves_mapsToNull() {
        Player agent = player(1L, true, null, "TopAgent");
        when(playerRepository.findAll()).thenReturn(List.of(agent));

        Map<Long, String> result = agentService.resolveSuperAgentNames();

        assertThat(result.get(1L)).isNull();
    }

    @Test
    void resolveSuperAgentNames_playerWithNoAgent_mapsToNull() {
        Player p = player(1L, false, null, "regularPlayer");
        when(playerRepository.findAll()).thenReturn(List.of(p));

        Map<Long, String> result = agentService.resolveSuperAgentNames();

        assertThat(result.get(1L)).isNull();
    }

    @Test
    void resolveAgentReportingFrom_explicitCallerDate_alwaysWins() {
        LocalDate callerFrom = LocalDate.of(2026, 3, 1);

        LocalDate result = agentService.resolveAgentReportingFrom(1L, callerFrom);

        assertThat(result).isEqualTo(callerFrom);
    }

    @Test
    void resolveAgentReportingFrom_noCallerDate_startsDayAfterAgentsOwnLatestOpeningDate() {
        // The OPENING's effectiveDate is the day the settlement itself happened (what an admin
        // naturally types) - that day's activity is already baked into the balance being carried
        // forward, so the new period must start the NEXT day to avoid double-counting it.
        AgentLedgerEntry opening = new AgentLedgerEntry();
        opening.setEffectiveDate(LocalDate.of(2026, 1, 10));
        when(agentLedgerEntryRepository.findByAgentIdAndType(1L, AgentLedgerEntry.Type.OPENING))
            .thenReturn(List.of(opening));

        LocalDate result = agentService.resolveAgentReportingFrom(1L, null);

        assertThat(result).isEqualTo(LocalDate.of(2026, 1, 11));
    }

    @Test
    void resolveAgentReportingFrom_retroactiveCorrectionWithEarlierDate_stillWinsOverOlderLaterDatedEntry() {
        // Regression test: an admin sets an OPENING (id=5, dated 2026-08-15), then later realizes it
        // was wrong and corrects it with a NEW entry (id=13, dated 2026-08-09 - an EARLIER date than
        // the mistake). The correction (most recently created) must win, even though its effective
        // date is earlier than the entry it's replacing - "newest OPENING is the baseline" per
        // AgentLedgerEntry's own docstring, not "furthest-dated OPENING wins".
        AgentLedgerEntry wrongEntry = new AgentLedgerEntry();
        wrongEntry.setId(5L);
        wrongEntry.setEffectiveDate(LocalDate.of(2026, 8, 15));
        AgentLedgerEntry correction = new AgentLedgerEntry();
        correction.setId(13L);
        correction.setEffectiveDate(LocalDate.of(2026, 8, 9));
        when(agentLedgerEntryRepository.findByAgentIdAndType(1L, AgentLedgerEntry.Type.OPENING))
            .thenReturn(List.of(wrongEntry, correction));

        LocalDate result = agentService.resolveAgentReportingFrom(1L, null);

        assertThat(result).isEqualTo(LocalDate.of(2026, 8, 10));
    }

    @Test
    void resolveAgentReportingFrom_noCallerDateAndNoOpeningEntry_startsDayAfterGlobalDate() {
        when(agentLedgerEntryRepository.findByAgentIdAndType(1L, AgentLedgerEntry.Type.OPENING))
            .thenReturn(List.of());
        LastSettlementDate global = new LastSettlementDate();
        global.setDate(LocalDate.of(2026, 2, 1));
        when(lastSettlementDateRepository.findById(1L)).thenReturn(Optional.of(global));

        LocalDate result = agentService.resolveAgentReportingFrom(1L, null);

        assertThat(result).isEqualTo(LocalDate.of(2026, 2, 2));
    }

    private AgentLedgerEntry ledgerEntry(LocalDate effectiveDate) {
        AgentLedgerEntry e = new AgentLedgerEntry();
        e.setEffectiveDate(effectiveDate);
        return e;
    }

    @Test
    void resolveAgentLastCheckpoint_onlyOpeningExists_returnsOpeningDate() {
        when(agentLedgerEntryRepository.findByAgentIdAndType(1L, AgentLedgerEntry.Type.OPENING))
            .thenReturn(List.of(ledgerEntry(LocalDate.of(2026, 1, 10))));
        when(agentLedgerEntryRepository.findByAgentIdAndType(1L, AgentLedgerEntry.Type.PAYMENT))
            .thenReturn(List.of());

        LocalDate result = agentService.resolveAgentLastCheckpoint(1L);

        assertThat(result).isEqualTo(LocalDate.of(2026, 1, 10));
    }

    @Test
    void resolveAgentLastCheckpoint_onlyPaymentExists_returnsPaymentDate() {
        when(agentLedgerEntryRepository.findByAgentIdAndType(1L, AgentLedgerEntry.Type.OPENING))
            .thenReturn(List.of());
        when(agentLedgerEntryRepository.findByAgentIdAndType(1L, AgentLedgerEntry.Type.PAYMENT))
            .thenReturn(List.of(ledgerEntry(LocalDate.of(2026, 9, 23))));

        LocalDate result = agentService.resolveAgentLastCheckpoint(1L);

        assertThat(result).isEqualTo(LocalDate.of(2026, 9, 23));
    }

    @Test
    void resolveAgentLastCheckpoint_paymentMoreRecentThanOpening_returnsPaymentDate() {
        when(agentLedgerEntryRepository.findByAgentIdAndType(1L, AgentLedgerEntry.Type.OPENING))
            .thenReturn(List.of(ledgerEntry(LocalDate.of(2026, 1, 1))));
        when(agentLedgerEntryRepository.findByAgentIdAndType(1L, AgentLedgerEntry.Type.PAYMENT))
            .thenReturn(List.of(ledgerEntry(LocalDate.of(2026, 9, 23))));

        LocalDate result = agentService.resolveAgentLastCheckpoint(1L);

        assertThat(result).isEqualTo(LocalDate.of(2026, 9, 23));
    }

    @Test
    void resolveAgentLastCheckpoint_openingMoreRecentThanPayment_returnsOpeningDate() {
        when(agentLedgerEntryRepository.findByAgentIdAndType(1L, AgentLedgerEntry.Type.OPENING))
            .thenReturn(List.of(ledgerEntry(LocalDate.of(2026, 9, 24))));
        when(agentLedgerEntryRepository.findByAgentIdAndType(1L, AgentLedgerEntry.Type.PAYMENT))
            .thenReturn(List.of(ledgerEntry(LocalDate.of(2026, 9, 1))));

        LocalDate result = agentService.resolveAgentLastCheckpoint(1L);

        assertThat(result).isEqualTo(LocalDate.of(2026, 9, 24));
    }

    @Test
    void resolveAgentLastCheckpoint_neitherExists_returnsNull() {
        when(agentLedgerEntryRepository.findByAgentIdAndType(1L, AgentLedgerEntry.Type.OPENING))
            .thenReturn(List.of());
        when(agentLedgerEntryRepository.findByAgentIdAndType(1L, AgentLedgerEntry.Type.PAYMENT))
            .thenReturn(List.of());

        LocalDate result = agentService.resolveAgentLastCheckpoint(1L);

        assertThat(result).isNull();
    }
}
