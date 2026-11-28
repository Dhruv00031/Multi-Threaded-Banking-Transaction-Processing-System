package com.banking.integration;

import com.banking.dto.DepositRequest;
import com.banking.exception.TransactionProcessingUnavailableException;
import com.banking.service.TransactionExecutor;
import com.banking.service.TransactionExecutorStats;
import com.banking.service.TransactionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(properties = {
        "banking.transaction.executor.worker-count=2",
        "banking.transaction.executor.queue-capacity=2",
        "banking.transaction.executor.shutdown-timeout-seconds=30"
})
@EnabledIfEnvironmentVariable(named = "DB_INTEGRATION_TESTS", matches = "true")
class MySqlExecutorSaturationIT {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TransactionExecutor transactionExecutor;

    @Autowired
    private TransactionService transactionService;

    private final String testToken = UUID.randomUUID().toString().replace("-", "");
    private final String customerId = "S" + testToken;
    private final String accountId = "A" + testToken;
    private final String transactionId = testToken + "-SAT-1";

    @BeforeEach
    void createIsolatedAccount() {
        jdbcTemplate.update(
                "INSERT INTO customers (customer_id, full_name, email) VALUES (?, ?, ?)",
                customerId,
                "Executor Saturation Test",
                "saturation-" + testToken + "@example.test");
        jdbcTemplate.update(
                "INSERT INTO accounts (account_id, customer_id, account_type, balance, status) "
                        + "VALUES (?, ?, 'SAVINGS', 100.00, 'ACTIVE')",
                accountId,
                customerId);
    }

    @AfterEach
    void removeOnlyTestRowsAfterExecutorIsIdle() {
        assertTrue(awaitExecutorIdle(30), "Executor did not become idle; test rows retained");
        jdbcTemplate.update("DELETE FROM transaction_audit WHERE transaction_id = ?", transactionId);
        jdbcTemplate.update("DELETE FROM failed_transactions WHERE transaction_id = ?", transactionId);
        jdbcTemplate.update("DELETE FROM transactions WHERE transaction_id = ?", transactionId);
        jdbcTemplate.update("DELETE FROM accounts WHERE account_id = ?", accountId);
        jdbcTemplate.update("DELETE FROM customers WHERE customer_id = ?", customerId);
    }

    @Test
    void saturatedExecutorRejectsBankingRequestWithoutChangingBalance() throws Exception {
        CountDownLatch workersStarted = new CountDownLatch(2);
        CountDownLatch releaseWorkers = new CountDownLatch(1);
        List<Future<Void>> acceptedTasks = new ArrayList<>();
        long rejectedBefore = transactionExecutor.getStats().rejectedTaskCount();

        try {
            for (int index = 0; index < 2; index++) {
                acceptedTasks.add(transactionExecutor.submit(() -> {
                    workersStarted.countDown();
                    if (!releaseWorkers.await(20, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Timed out waiting for saturation test release");
                    }
                    return null;
                }));
            }
            assertTrue(workersStarted.await(5, TimeUnit.SECONDS));

            acceptedTasks.add(transactionExecutor.submit(() -> null));
            acceptedTasks.add(transactionExecutor.submit(() -> null));

            TransactionExecutorStats saturated = transactionExecutor.getStats();
            assertEquals(2, saturated.configuredWorkerCount());
            assertEquals(2, saturated.activeWorkerCount());
            assertEquals(2, saturated.queueSize());
            assertEquals(2, saturated.queueCapacity());

            assertThrows(
                    TransactionProcessingUnavailableException.class,
                    () -> transactionService.deposit(new DepositRequest(
                            transactionId, accountId, new BigDecimal("25.00"), "Must be rejected")));

            assertEquals(rejectedBefore + 1, transactionExecutor.getStats().rejectedTaskCount());
            assertEquals(new BigDecimal("100.00"), jdbcTemplate.queryForObject(
                    "SELECT balance FROM accounts WHERE account_id = ?",
                    BigDecimal.class,
                    accountId));
            assertEquals(0L, jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM transactions WHERE transaction_id = ?",
                    Long.class,
                    transactionId));
        } finally {
            releaseWorkers.countDown();
            for (Future<Void> task : acceptedTasks) {
                task.get(10, TimeUnit.SECONDS);
            }
        }
    }

    private boolean awaitExecutorIdle(long timeoutSeconds) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        while (System.nanoTime() < deadline) {
            TransactionExecutorStats stats = transactionExecutor.getStats();
            if (stats.activeWorkerCount() == 0
                    && stats.queueSize() == 0
                    && stats.submittedTaskCount() == stats.completedTaskCount()) {
                return true;
            }
            Thread.yield();
        }
        return false;
    }
}
