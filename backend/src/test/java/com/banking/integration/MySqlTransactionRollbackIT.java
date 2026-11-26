package com.banking.integration;

import com.banking.dto.TransferRequest;
import com.banking.repository.AccountRepository;
import com.banking.service.TransactionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;

@SpringBootTest
@EnabledIfEnvironmentVariable(named = "DB_INTEGRATION_TESTS", matches = "true")
class MySqlTransactionRollbackIT {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TransactionService transactionService;

    @MockitoSpyBean
    private AccountRepository accountRepository;

    private final String uniqueId = UUID.randomUUID().toString().replace("-", "");
    private final String customerId = "ITC-" + uniqueId;
    private final String sourceAccountId = "IAS-" + uniqueId;
    private final String destinationAccountId = "IAD-" + uniqueId;
    private final String transactionId = "ITX-" + uniqueId;

    @BeforeEach
    void createIsolatedTestRowsAndFailBetweenBalanceUpdates() {
        jdbcTemplate.update(
                "INSERT INTO customers (customer_id, full_name, email) VALUES (?, ?, ?)",
                customerId,
                "Integration Test",
                uniqueId + "@example.test");
        insertAccount(sourceAccountId, "200.00");
        insertAccount(destinationAccountId, "50.00");

        AtomicInteger balanceUpdateCount = new AtomicInteger();
        doAnswer(invocation -> {
            if (balanceUpdateCount.incrementAndGet() == 2) {
                throw new IllegalStateException("Injected failure before credit update");
            }
            return invocation.callRealMethod();
        }).when(accountRepository).updateBalance(anyString(), any(BigDecimal.class));
    }

    @AfterEach
    void removeOnlyRowsCreatedByThisTest() {
        jdbcTemplate.update("DELETE FROM transaction_audit WHERE transaction_id = ?", transactionId);
        jdbcTemplate.update("DELETE FROM failed_transactions WHERE transaction_id = ?", transactionId);
        jdbcTemplate.update("DELETE FROM transactions WHERE transaction_id = ?", transactionId);
        jdbcTemplate.update("DELETE FROM accounts WHERE account_id IN (?, ?)",
                sourceAccountId, destinationAccountId);
        jdbcTemplate.update("DELETE FROM customers WHERE customer_id = ?", customerId);
    }

    @Test
    void mysqlRollsBackDebitAndPersistsFailureRecordsSeparately() {
        assertThrows(IllegalStateException.class, () -> transactionService.transfer(
                new TransferRequest(
                        transactionId,
                        sourceAccountId,
                        destinationAccountId,
                        new BigDecimal("75.00"),
                        "Rollback integration test")));

        assertEquals(new BigDecimal("200.00"), accountBalance(sourceAccountId));
        assertEquals(new BigDecimal("50.00"), accountBalance(destinationAccountId));
        assertEquals("FAILED", jdbcTemplate.queryForObject(
                "SELECT status FROM transactions WHERE transaction_id = ?",
                String.class,
                transactionId));
        assertEquals(1, countForTransaction("failed_transactions"));
        assertEquals(1, countForTransaction("transaction_audit"));
        assertEquals("FAILED", jdbcTemplate.queryForObject(
                "SELECT event_type FROM transaction_audit WHERE transaction_id = ?",
                String.class,
                transactionId));
    }

    private void insertAccount(String accountId, String balance) {
        jdbcTemplate.update(
                "INSERT INTO accounts (account_id, customer_id, account_type, balance, status) "
                        + "VALUES (?, ?, 'SAVINGS', ?, 'ACTIVE')",
                accountId,
                customerId,
                new BigDecimal(balance));
    }

    private BigDecimal accountBalance(String accountId) {
        return jdbcTemplate.queryForObject(
                "SELECT balance FROM accounts WHERE account_id = ?",
                BigDecimal.class,
                accountId);
    }

    private int countForTransaction(String table) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE transaction_id = ?",
                Integer.class,
                transactionId);
    }
}
