package com.sevenmax.tracker.repository;

import com.sevenmax.tracker.entity.LeagueSessionConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface LeagueSessionConfigRepository extends JpaRepository<LeagueSessionConfig, Long> {
    List<LeagueSessionConfig> findByIncludedTrue();
    Optional<LeagueSessionConfig> findBySessionId(Long sessionId);
}
