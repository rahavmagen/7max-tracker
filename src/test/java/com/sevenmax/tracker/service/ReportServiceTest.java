package com.sevenmax.tracker.service;

import com.sevenmax.tracker.entity.Player;
import com.sevenmax.tracker.repository.PlayerRepository;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ReportServiceTest {

    @Mock PlayerRepository playerRepository;
    @Mock PlayerService playerService;

    ReportService reportService;

    /** A minimal in-memory fake of "the players table", shared by both mocks, so a player
     *  created mid-test (via playerService.createPlayer) becomes findable by a later
     *  playerRepository.findByClubPlayerIdSafe call within the SAME parseClubOverview run —
     *  exactly like a real DB would behave across sequential passes in that method. */
    private final Map<String, Player> byClubId = new HashMap<>();
    private final AtomicLong nextId = new AtomicLong(1000L);

    @BeforeEach
    void setUp() {
        reportService = new ReportService(
            null, null, null, playerRepository, null, null, playerService,
            null, null, null, null, null, null
        );

        when(playerRepository.findByClubPlayerIdSafe(any())).thenAnswer(inv -> {
            Player p = byClubId.get(inv.getArgument(0));
            return p != null ? List.of(p) : List.of();
        });
        // Not every test reaches these two - e.g. the blank-nickname case is skipped before
        // either would be called - so they're lenient defaults, not per-test expectations.
        lenient().when(playerService.findPlayerByUsername(any())).thenReturn(Optional.empty());
        lenient().when(playerService.createPlayer(any(Player.class))).thenAnswer(inv -> {
            Player p = inv.getArgument(0);
            p.setId(nextId.getAndIncrement());
            if (p.getClubPlayerId() != null) byClubId.put(p.getClubPlayerId(), p);
            return p;
        });
    }

    private void register(Player p) {
        if (p.getClubPlayerId() != null) byClubId.put(p.getClubPlayerId(), p);
    }

    private Player existingPlayer(long id, String clubId, String username, boolean isAgent) {
        Player p = new Player();
        p.setId(id);
        p.setClubPlayerId(clubId);
        p.setUsername(username);
        p.setIsAgent(isAgent);
        register(p);
        return p;
    }

    private void setCell(Row row, int col, String value) {
        if (value == null) return; // leave the cell uncreated, matching a truly blank Excel cell
        row.createCell(col).setCellValue(value);
    }

    private Row dataRow(Sheet sheet, int idx, String superAgentId, String superAgentNick,
                         String directAgentId, String directAgentNick, String role,
                         String memberId, String memberNick) {
        Row row = sheet.createRow(idx);
        setCell(row, 1, superAgentId);
        setCell(row, 2, superAgentNick);
        setCell(row, 3, directAgentId);
        setCell(row, 4, directAgentNick);
        setCell(row, 6, role);
        setCell(row, 7, memberId);
        setCell(row, 8, memberNick);
        return row;
    }

    @Test
    void parseClubOverview_brandNewPlayer_isCreatedAndLinkedToTheirAgent() throws Exception {
        Player existingAgent = existingPlayer(500L, "A001", "ExistingAgent", true);

        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("Club Overview");
            dataRow(sheet, 3, "-", "-", "-", "-", "Agent", "A001", "ExistingAgent");
            // No direct agent (blank), falls back to the super-agent columns pointing at A001.
            dataRow(sheet, 4, "A001", "ExistingAgent", "-", "-", "Player", "P001", "NewPlayer");

            reportService.parseClubOverview(wb);
        }

        ArgumentCaptor<Player> createdCaptor = ArgumentCaptor.forClass(Player.class);
        verify(playerService).createPlayer(createdCaptor.capture());
        assertThat(createdCaptor.getValue().getClubPlayerId()).isEqualTo("P001");
        assertThat(createdCaptor.getValue().getUsername()).isEqualTo("NewPlayer");

        Player linked = byClubId.get("P001");
        assertThat(linked).isNotNull();
        assertThat(linked.getAgent()).isNotNull();
        assertThat(linked.getAgent().getId()).isEqualTo(existingAgent.getId());
    }

    @Test
    void parseClubOverview_playerRowWithBlankNickname_isSkippedWithoutCreatingOrThrowing() throws Exception {
        existingPlayer(500L, "A001", "ExistingAgent", true);

        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("Club Overview");
            dataRow(sheet, 3, "-", "-", "-", "-", "Agent", "A001", "ExistingAgent");
            // Real club ID, but no nickname cell at all (a genuinely blank Excel cell).
            dataRow(sheet, 4, "A001", "ExistingAgent", "-", "-", "Player", "P002", null);

            reportService.parseClubOverview(wb); // must not throw
        }

        // Player.username is NOT NULL/unique — creating with a blank nickname would violate
        // that constraint and roll back the whole upload, so this row must be skipped instead.
        verify(playerService, never()).createPlayer(any());
        assertThat(byClubId.get("P002")).isNull();
    }

    @Test
    void parseClubOverview_brandNewSubAgent_isCreatedByPass1AndLinkedToSuperAgentByPass3() throws Exception {
        Player topAgent = existingPlayer(500L, "T001", "TopAgent", true);

        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("Club Overview");
            dataRow(sheet, 3, "-", "-", "-", "-", "Super Agent", "T001", "TopAgent");
            // A brand-new sub-agent row: role "Agent", not yet in the players table, whose own
            // super-agent columns point at the existing top agent.
            dataRow(sheet, 4, "T001", "TopAgent", "-", "-", "Agent", "S001", "NewSubAgent");

            reportService.parseClubOverview(wb);
        }

        Player subAgent = byClubId.get("S001");
        assertThat(subAgent).isNotNull();
        assertThat(subAgent.getIsAgent()).isTrue();
        assertThat(subAgent.getAgent()).isNotNull();
        assertThat(subAgent.getAgent().getId()).isEqualTo(topAgent.getId());
    }
}
