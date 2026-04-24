package com.sevenmax.tracker.repository;

import com.sevenmax.tracker.entity.LeagueConfig;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LeagueConfigRepository extends JpaRepository<LeagueConfig, Long> {
}
