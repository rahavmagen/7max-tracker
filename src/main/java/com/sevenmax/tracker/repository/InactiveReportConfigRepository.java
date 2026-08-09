package com.sevenmax.tracker.repository;

import com.sevenmax.tracker.entity.InactiveReportConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;

public interface InactiveReportConfigRepository extends JpaRepository<InactiveReportConfig, Long> {

    /** Atomically claims the weekly-nudge send for `today`: only the first caller on a given day
     *  gets affected-rows=1 (a WHERE-guarded UPDATE is row-locked, so two near-simultaneous callers
     *  - e.g. old/new instances briefly overlapping during a rolling deploy - can't both win). */
    @Modifying
    @Query("UPDATE InactiveReportConfig c SET c.lastNudgeSentDate = :today " +
           "WHERE c.id = 1 AND (c.lastNudgeSentDate IS NULL OR c.lastNudgeSentDate <> :today)")
    int claimNudgeForDate(@Param("today") LocalDate today);
}
