package com.sevenmax.tracker.repository;

import com.sevenmax.tracker.entity.AgentSettlement;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface AgentSettlementRepository extends JpaRepository<AgentSettlement, Long> {
    List<AgentSettlement> findByAgentIdOrderByCreatedAtDesc(Long agentId);
}
