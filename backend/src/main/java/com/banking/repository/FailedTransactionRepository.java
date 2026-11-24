package com.banking.repository;

import com.banking.model.FailedTransaction;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class FailedTransactionRepository {

    private final JdbcTemplate jdbcTemplate;

    public FailedTransactionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void save(FailedTransaction failedTransaction) {
        jdbcTemplate.update(
                "INSERT INTO failed_transactions "
                        + "(transaction_id, error_category, failure_reason) VALUES (?, ?, ?)",
                failedTransaction.transactionId(),
                failedTransaction.errorCategory(),
                failedTransaction.failureReason());
    }
}
