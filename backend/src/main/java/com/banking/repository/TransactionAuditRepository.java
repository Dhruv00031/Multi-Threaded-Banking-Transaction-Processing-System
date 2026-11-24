package com.banking.repository;

import com.banking.model.TransactionAudit;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class TransactionAuditRepository {

    private final JdbcTemplate jdbcTemplate;

    public TransactionAuditRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void save(TransactionAudit audit) {
        jdbcTemplate.update(
                "INSERT INTO transaction_audit "
                        + "(transaction_id, event_type, thread_name, duration_ms, details) "
                        + "VALUES (?, ?, ?, ?, ?)",
                audit.transactionId(),
                audit.eventType(),
                audit.threadName(),
                audit.durationMs(),
                audit.details());
    }
}
