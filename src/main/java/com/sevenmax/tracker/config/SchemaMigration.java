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
                "CREATE TABLE IF NOT EXISTS import_summary (" +
                "id BIGINT PRIMARY KEY, " +
                "will_expense NUMERIC(12,2), " +
                "general_expenses NUMERIC(12,2), " +
                "bank_deposits NUMERIC(12,2), " +
                "last_updated TIMESTAMP)"
            );
            log.info("SchemaMigration: import_summary table ensured");
        } catch (Exception e) {
            log.warn("SchemaMigration: import_summary table: {}", e.getMessage());
        }
        try {
            jdbcTemplate.execute(
                "CREATE TABLE IF NOT EXISTS admin_expenses (" +
                "id BIGSERIAL PRIMARY KEY, " +
                "admin_username VARCHAR(255), " +
                "amount NUMERIC(12,2), " +
                "notes TEXT, " +
                "expense_date DATE, " +
                "created_by VARCHAR(255), " +
                "created_at TIMESTAMP, " +
                "source_ref VARCHAR(255))"
            );
            log.info("SchemaMigration: admin_expenses table ensured");
        } catch (Exception e) {
            log.warn("SchemaMigration: admin_expenses table: {}", e.getMessage());
        }
        try {
            jdbcTemplate.execute(
                "ALTER TABLE transactions DROP CONSTRAINT IF EXISTS transactions_type_check"
            );
            jdbcTemplate.execute(
                "ALTER TABLE transactions ADD CONSTRAINT transactions_type_check " +
                "CHECK (type IN ('DEPOSIT', 'WITHDRAWAL', 'CREDIT', 'PAYMENT', 'WHEEL_EXPENSE', 'CHIP_PROMO', 'PROMOTION'))"
            );
            log.info("SchemaMigration: transactions type constraint updated");
        } catch (Exception e) {
            log.warn("SchemaMigration: could not update transactions constraint: {}", e.getMessage());
        }
        try {
            int updated = jdbcTemplate.update("UPDATE transactions SET type = 'PAYMENT' WHERE type = 'REPAYMENT'");
            if (updated > 0) log.info("SchemaMigration: renamed {} REPAYMENT → PAYMENT rows", updated);
        } catch (Exception e) {
            log.warn("SchemaMigration: could not rename REPAYMENT rows: {}", e.getMessage());
        }
        try {
            jdbcTemplate.execute("ALTER TABLE admin_expenses ADD COLUMN IF NOT EXISTS settled BOOLEAN DEFAULT FALSE");
            jdbcTemplate.execute("ALTER TABLE admin_expenses ADD COLUMN IF NOT EXISTS settled_at DATE");
            jdbcTemplate.execute("ALTER TABLE admin_expenses ADD COLUMN IF NOT EXISTS settled_by VARCHAR(255)");
            log.info("SchemaMigration: admin_expenses settle columns ensured");
        } catch (Exception e) {
            log.warn("SchemaMigration: admin_expenses settle columns: {}", e.getMessage());
        }
    }
}
