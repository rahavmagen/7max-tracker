package com.sevenmax.tracker.repository;

import com.sevenmax.tracker.entity.ImportSummary;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ImportSummaryRepository extends JpaRepository<ImportSummary, Long> {
}
