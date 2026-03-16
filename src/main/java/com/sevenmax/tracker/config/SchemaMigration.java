package com.sevenmax.tracker.config;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class SchemaMigration {

    private final JdbcTemplate jdbcTemplate;

    @PostConstruct
    public void migrate() {
        try {
            jdbcTemplate.execute(
                "ALTER TABLE transactions DROP CONSTRAINT IF EXISTS transactions_type_check"
            );
            jdbcTemplate.execute(
                "ALTER TABLE transactions ADD CONSTRAINT transactions_type_check " +
                "CHECK (type IN ('DEPOSIT', 'WITHDRAWAL', 'CREDIT', 'REPAYMENT'))"
            );
            log.info("SchemaMigration: transactions type constraint updated");
        } catch (Exception e) {
            log.warn("SchemaMigration: could not update transactions constraint: {}", e.getMessage());
        }
    }
}
