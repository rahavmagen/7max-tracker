package com.sevenmax.tracker.service;

import com.sevenmax.tracker.entity.Player;
import com.sevenmax.tracker.repository.PlayerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentServiceTest {

    @Mock PlayerRepository playerRepository;

    AgentService agentService;

    @BeforeEach
    void setUp() {
        agentService = new AgentService(
            playerRepository, null, null, null, null, null, null, null
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
}
