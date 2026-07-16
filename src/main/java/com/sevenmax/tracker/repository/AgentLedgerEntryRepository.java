package com.sevenmax.tracker.repository;

import com.sevenmax.tracker.entity.AgentLedgerEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface AgentLedgerEntryRepository extends JpaRepository<AgentLedgerEntry, Long> {
    List<AgentLedgerEntry> findByAgentIdOrderByEffectiveDateDescIdDesc(Long agentId);

    List<AgentLedgerEntry> findByAgentIdAndType(Long agentId, AgentLedgerEntry.Type type);
}
