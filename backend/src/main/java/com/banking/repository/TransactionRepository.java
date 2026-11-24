package com.banking.repository;

import com.banking.enums.TransactionStatus;
import com.banking.enums.TransactionType;
import com.banking.model.Transaction;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public class TransactionRepository {

    private static final RowMapper<Transaction> TRANSACTION_ROW_MAPPER = (resultSet, rowNumber) ->
            new Transaction(
                    resultSet.getString("transaction_id"),
                    TransactionType.valueOf(resultSet.getString("transaction_type")),
                    resultSet.getBigDecimal("amount"),
                    resultSet.getString("source_account_id"),
                    resultSet.getString("destination_account_id"),
                    TransactionStatus.valueOf(resultSet.getString("status")),
                    resultSet.getString("description"),
                    resultSet.getTimestamp("created_at").toLocalDateTime());

    private final JdbcTemplate jdbcTemplate;

    public TransactionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public boolean existsById(String transactionId) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM transactions WHERE transaction_id = ?",
                Long.class,
                transactionId);
        return count != null && count > 0;
    }

    public Transaction save(Transaction transaction) {
        jdbcTemplate.update(
                "INSERT INTO transactions "
                        + "(transaction_id, transaction_type, amount, source_account_id, "
                        + "destination_account_id, status, description) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                transaction.transactionId(),
                transaction.transactionType().name(),
                transaction.amount(),
                transaction.sourceAccountId(),
                transaction.destinationAccountId(),
                transaction.status().name(),
                transaction.description());
        return findById(transaction.transactionId()).orElseThrow(
                () -> new IllegalStateException("Saved transaction could not be retrieved"));
    }

    public boolean updateStatus(String transactionId, TransactionStatus status) {
        return jdbcTemplate.update(
                "UPDATE transactions SET status = ? WHERE transaction_id = ?",
                status.name(),
                transactionId) == 1;
    }

    public Optional<Transaction> findById(String transactionId) {
        return jdbcTemplate.query(
                        "SELECT transaction_id, transaction_type, amount, source_account_id, "
                                + "destination_account_id, status, description, created_at "
                                + "FROM transactions WHERE transaction_id = ?",
                        TRANSACTION_ROW_MAPPER,
                        transactionId)
                .stream()
                .findFirst();
    }
}
