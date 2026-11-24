package com.banking.repository;

import com.banking.model.Account;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.Optional;

@Repository
public class AccountRepository {

    private static final RowMapper<Account> ACCOUNT_ROW_MAPPER = (resultSet, rowNumber) ->
            new Account(
                    resultSet.getString("account_id"),
                    resultSet.getString("customer_id"),
                    resultSet.getString("account_type"),
                    resultSet.getBigDecimal("balance"),
                    resultSet.getString("status"),
                    resultSet.getTimestamp("created_at").toLocalDateTime());

    private final JdbcTemplate jdbcTemplate;

    public AccountRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<Account> findById(String accountId) {
        return jdbcTemplate.query(
                        "SELECT account_id, customer_id, account_type, balance, status, created_at "
                                + "FROM accounts WHERE account_id = ?",
                        ACCOUNT_ROW_MAPPER,
                        accountId)
                .stream()
                .findFirst();
    }

    /**
     * The lock is held until the surrounding Spring transaction commits or rolls back.
     */
    public Optional<Account> findByIdForUpdate(String accountId) {
        return jdbcTemplate.query(
                        "SELECT account_id, customer_id, account_type, balance, status, created_at "
                                + "FROM accounts WHERE account_id = ? FOR UPDATE",
                        ACCOUNT_ROW_MAPPER,
                        accountId)
                .stream()
                .findFirst();
    }

    public boolean updateBalance(String accountId, BigDecimal balance) {
        return jdbcTemplate.update(
                "UPDATE accounts SET balance = ? WHERE account_id = ? AND ? >= 0",
                balance,
                accountId,
                balance) == 1;
    }
}
