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
            jdbcTemplate.execute("ALTER TABLE club_expenses ADD COLUMN IF NOT EXISTS settled_by VARCHAR(255)");
            log.info("SchemaMigration: admin_expenses settle columns ensured");
        } catch (Exception e) {
            log.warn("SchemaMigration: admin_expenses settle columns: {}", e.getMessage());
        }
        try {
            jdbcTemplate.execute("ALTER TABLE player_transfers ADD COLUMN IF NOT EXISTS from_admin_username VARCHAR(255)");
            jdbcTemplate.execute("ALTER TABLE player_transfers ADD COLUMN IF NOT EXISTS to_admin_username VARCHAR(255)");
            log.info("SchemaMigration: player_transfers admin attribution columns ensured");
        } catch (Exception e) {
            log.warn("SchemaMigration: player_transfers admin attribution columns: {}", e.getMessage());
        }
        try {
            jdbcTemplate.execute("ALTER TABLE admin_expenses ADD COLUMN IF NOT EXISTS paid_from_admin_username VARCHAR(255)");
            jdbcTemplate.execute("ALTER TABLE admin_expenses ADD COLUMN IF NOT EXISTS paid_from_bank_account_id BIGINT");
            log.info("SchemaMigration: admin_expenses payment source columns ensured");
        } catch (Exception e) {
            log.warn("SchemaMigration: admin_expenses payment source columns: {}", e.getMessage());
        }
        try {
            jdbcTemplate.execute("ALTER TABLE club_expenses ADD COLUMN IF NOT EXISTS paid_from_admin_username VARCHAR(255)");
            jdbcTemplate.execute("ALTER TABLE club_expenses ADD COLUMN IF NOT EXISTS paid_from_bank_account_id BIGINT");
            log.info("SchemaMigration: club_expenses payment source columns ensured");
        } catch (Exception e) {
            log.warn("SchemaMigration: club_expenses payment source columns: {}", e.getMessage());
        }
        try {
            jdbcTemplate.execute(
                "CREATE TABLE IF NOT EXISTS ticket_assets (" +
                "id BIGSERIAL PRIMARY KEY, " +
                "cost_per_ticket NUMERIC(12,2) NOT NULL, " +
                "face_value_per_ticket NUMERIC(12,2) NOT NULL, " +
                "quantity_total INTEGER NOT NULL, " +
                "quantity_remaining INTEGER NOT NULL, " +
                "buyer_admin_username VARCHAR(255) NOT NULL, " +
                "purchase_date DATE NOT NULL, " +
                "notes VARCHAR(500), " +
                "created_at TIMESTAMP DEFAULT NOW())"
            );
            log.info("SchemaMigration: ticket_assets table ensured");
        } catch (Exception e) {
            log.warn("SchemaMigration: ticket_assets table: {}", e.getMessage());
        }
        try {
            jdbcTemplate.execute(
                "ALTER TABLE transactions DROP CONSTRAINT IF EXISTS transactions_type_check"
            );
            jdbcTemplate.execute(
                "ALTER TABLE transactions ADD CONSTRAINT transactions_type_check " +
                "CHECK (type IN ('DEPOSIT', 'WITHDRAWAL', 'CREDIT', 'PAYMENT', 'WHEEL_EXPENSE', 'CHIP_PROMO', 'PROMOTION', 'EXPENSE_REPAYMENT', 'TICKET_GRANT'))"
            );
            log.info("SchemaMigration: transactions type constraint updated for TICKET_GRANT");
        } catch (Exception e) {
            log.warn("SchemaMigration: could not update transactions constraint for TICKET_GRANT: {}", e.getMessage());
        }
        try {
            jdbcTemplate.execute(
                "CREATE TABLE IF NOT EXISTS admin_wallet_starting_balances (" +
                "admin_username VARCHAR(255) PRIMARY KEY, " +
                "cash_amount NUMERIC(12,2) DEFAULT 0, " +
                "bit_amount NUMERIC(12,2) DEFAULT 0, " +
                "paybox_amount NUMERIC(12,2) DEFAULT 0, " +
                "other_amount NUMERIC(12,2) DEFAULT 0, " +
                "notes VARCHAR(500), " +
                "set_at TIMESTAMP DEFAULT NOW())"
            );
            // Migrate old schema if needed (drop old amount column, add new columns)
            jdbcTemplate.execute("ALTER TABLE admin_wallet_starting_balances DROP COLUMN IF EXISTS amount");
            jdbcTemplate.execute("ALTER TABLE admin_wallet_starting_balances ADD COLUMN IF NOT EXISTS cash_amount NUMERIC(12,2) DEFAULT 0");
            jdbcTemplate.execute("ALTER TABLE admin_wallet_starting_balances ADD COLUMN IF NOT EXISTS bit_amount NUMERIC(12,2) DEFAULT 0");
            jdbcTemplate.execute("ALTER TABLE admin_wallet_starting_balances ADD COLUMN IF NOT EXISTS paybox_amount NUMERIC(12,2) DEFAULT 0");
            jdbcTemplate.execute("ALTER TABLE admin_wallet_starting_balances ADD COLUMN IF NOT EXISTS kashcash_amount NUMERIC(12,2) DEFAULT 0");
            jdbcTemplate.execute("ALTER TABLE admin_wallet_starting_balances ADD COLUMN IF NOT EXISTS other_amount NUMERIC(12,2) DEFAULT 0");
            jdbcTemplate.execute("ALTER TABLE admin_wallet_starting_balances ADD COLUMN IF NOT EXISTS starting_amount NUMERIC(12,2) DEFAULT 0");
            // Migrate: sum old sub-balance columns into starting_amount (only where starting_amount is still 0)
            jdbcTemplate.execute(
                "UPDATE admin_wallet_starting_balances SET starting_amount = " +
                "COALESCE(cash_amount,0) + COALESCE(bit_amount,0) + COALESCE(paybox_amount,0) + " +
                "COALESCE(kashcash_amount,0) + COALESCE(other_amount,0) " +
                "WHERE starting_amount = 0 AND (" +
                "COALESCE(cash_amount,0) + COALESCE(bit_amount,0) + COALESCE(paybox_amount,0) + " +
                "COALESCE(kashcash_amount,0) + COALESCE(other_amount,0)) != 0"
            );
            log.info("SchemaMigration: admin_wallet_starting_balances table ensured");
        } catch (Exception e) {
            log.warn("SchemaMigration: admin_wallet_starting_balances table: {}", e.getMessage());
        }
        try {
            jdbcTemplate.execute(
                "CREATE TABLE IF NOT EXISTS bank_transactions (" +
                "id BIGSERIAL PRIMARY KEY, " +
                "amount NUMERIC(12,2) NOT NULL, " +
                "transaction_date DATE, " +
                "notes VARCHAR(500), " +
                "created_by VARCHAR(255), " +
                "created_at TIMESTAMP DEFAULT NOW())"
            );
            // Migrate existing bank_deposits from import_summary as opening balance (runs once)
            jdbcTemplate.execute(
                "INSERT INTO bank_transactions (amount, notes, created_by, created_at) " +
                "SELECT bank_deposits, 'Opening balance (migrated)', 'system', NOW() " +
                "FROM import_summary WHERE id = 1 AND bank_deposits IS NOT NULL AND bank_deposits != 0 " +
                "AND NOT EXISTS (SELECT 1 FROM bank_transactions)"
            );
            log.info("SchemaMigration: bank_transactions table ensured");
        } catch (Exception e) {
            log.warn("SchemaMigration: bank_transactions table: {}", e.getMessage());
        }
    }
}
