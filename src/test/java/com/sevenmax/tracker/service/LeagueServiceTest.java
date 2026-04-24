package com.sevenmax.tracker.service;

import com.sevenmax.tracker.entity.*;
import com.sevenmax.tracker.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LeagueServiceTest {

    @Mock LeagueConfigRepository leagueConfigRepository;
    @Mock LeagueSessionConfigRepository leagueSessionConfigRepository;
    @Mock GameResultRepository gameResultRepository;

    LeagueService leagueService;

    @BeforeEach
    void setUp() {
        leagueService = new LeagueService(leagueConfigRepository, leagueSessionConfigRepository, gameResultRepository);
    }

    private Player player(long id, String username) {
        Player p = new Player();
        p.setId(id);
        p.setUsername(username);
        return p;
    }

    private GameSession session(long id) {
        GameSession s = new GameSession();
        s.setId(id);
        return s;
    }

    private LeagueSessionConfig config(GameSession session, int handsMultiplier, int profitMultiplier) {
        LeagueSessionConfig c = new LeagueSessionConfig();
        c.setSession(session);
        c.setIncluded(true);
        c.setHandsMultiplier(handsMultiplier);
        c.setProfitMultiplier(profitMultiplier);
        return c;
    }

    private GameResult result(GameSession session, Player player, int hands, int profit) {
        GameResult r = new GameResult();
        r.setSession(session);
        r.setPlayer(player);
        r.setHandsPlayed(hands);
        r.setResultAmount(BigDecimal.valueOf(profit));
        return r;
    }

    @Test
    void qualifiedPlayerIsRanked() {
        LeagueConfig cfg = new LeagueConfig();
        cfg.setMinHands(100);
        when(leagueConfigRepository.findById(1L)).thenReturn(Optional.of(cfg));

        GameSession s1 = session(1L);
        LeagueSessionConfig sc1 = config(s1, 2, 4);
        when(leagueSessionConfigRepository.findByIncludedTrue()).thenReturn(List.of(sc1));

        Player alice = player(1L, "Alice");
        when(gameResultRepository.findBySessionId(1L))
            .thenReturn(List.of(result(s1, alice, 150, 200)));

        List<Map<String, Object>> standings = leagueService.computeStandings();

        assertThat(standings).hasSize(1);
        Map<String, Object> row = standings.get(0);
        assertThat(row.get("username")).isEqualTo("Alice");
        assertThat(row.get("totalHands")).isEqualTo(150);
        assertThat(row.get("handsPoints")).isEqualTo(300L);   // 150 * 2
        assertThat(row.get("profitILS")).isEqualTo(BigDecimal.valueOf(200));
        assertThat(row.get("profitPoints")).isEqualTo(800L);  // 200 * 4
        assertThat(row.get("totalPoints")).isEqualTo(1100L);
        assertThat(row.get("qualified")).isEqualTo(true);
        assertThat(row.get("rank")).isEqualTo(1);
    }

    @Test
    void playerBelowMinHandsIsUnranked() {
        LeagueConfig cfg = new LeagueConfig();
        cfg.setMinHands(100);
        when(leagueConfigRepository.findById(1L)).thenReturn(Optional.of(cfg));

        GameSession s1 = session(1L);
        when(leagueSessionConfigRepository.findByIncludedTrue()).thenReturn(List.of(config(s1, 1, 1)));

        Player bob = player(2L, "Bob");
        when(gameResultRepository.findBySessionId(1L))
            .thenReturn(List.of(result(s1, bob, 62, 100)));

        List<Map<String, Object>> standings = leagueService.computeStandings();

        assertThat(standings).hasSize(1);
        Map<String, Object> row = standings.get(0);
        assertThat(row.get("qualified")).isEqualTo(false);
        assertThat(row.get("rank")).isNull();
    }

    @Test
    void negativeResultSubtractsPoints() {
        LeagueConfig cfg = new LeagueConfig();
        cfg.setMinHands(50);
        when(leagueConfigRepository.findById(1L)).thenReturn(Optional.of(cfg));

        GameSession s1 = session(1L);
        when(leagueSessionConfigRepository.findByIncludedTrue()).thenReturn(List.of(config(s1, 1, 2)));

        Player carol = player(3L, "Carol");
        when(gameResultRepository.findBySessionId(1L))
            .thenReturn(List.of(result(s1, carol, 100, -50)));

        List<Map<String, Object>> standings = leagueService.computeStandings();

        Map<String, Object> row = standings.get(0);
        assertThat(row.get("profitPoints")).isEqualTo(-100L); // -50 * 2
        assertThat(row.get("totalPoints")).isEqualTo(0L);     // 100 - 100
    }

    @Test
    void standingsOrderedByTotalPointsDesc() {
        LeagueConfig cfg = new LeagueConfig();
        cfg.setMinHands(0);
        when(leagueConfigRepository.findById(1L)).thenReturn(Optional.of(cfg));

        GameSession s1 = session(1L);
        when(leagueSessionConfigRepository.findByIncludedTrue()).thenReturn(List.of(config(s1, 1, 1)));

        Player alice = player(1L, "Alice");
        Player bob   = player(2L, "Bob");
        when(gameResultRepository.findBySessionId(1L))
            .thenReturn(List.of(result(s1, alice, 50, 100), result(s1, bob, 200, 50)));

        List<Map<String, Object>> standings = leagueService.computeStandings();

        // bob: 200+50=250, alice: 50+100=150 → bob is rank 1
        assertThat(standings.get(0).get("username")).isEqualTo("Bob");
        assertThat(standings.get(0).get("rank")).isEqualTo(1);
        assertThat(standings.get(1).get("username")).isEqualTo("Alice");
        assertThat(standings.get(1).get("rank")).isEqualTo(2);
    }
}
