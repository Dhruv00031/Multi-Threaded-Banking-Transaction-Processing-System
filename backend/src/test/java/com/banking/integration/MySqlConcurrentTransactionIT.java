package com.banking.integration;

import com.banking.dto.DepositRequest;
import com.banking.dto.TransactionResponse;
import com.banking.dto.TransferRequest;
import com.banking.dto.WithdrawalRequest;
import com.banking.exception.DuplicateTransactionException;
import com.banking.exception.InsufficientBalanceException;
import com.banking.service.TransactionExecutor;
import com.banking.service.TransactionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(properties = {
        "banking.transaction.executor.worker-count=5",
        "banking.transaction.executor.queue-capacity=100",
        "banking.transaction.executor.shutdown-timeout-seconds=30"
})
@EnabledIfEnvironmentVariable(named = "DB_INTEGRATION_TESTS", matches = "true")
class MySqlConcurrentTransactionIT {

    private static final long CLIENT_TIMEOUT_SECONDS = 90;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TransactionService transactionService;

    @Autowired
    private TransactionExecutor transactionExecutor;

    private final String testToken = UUID.randomUUID().toString().replace("-", "");
    private final String customerId = "C" + testToken;
    private final List<String> accountIds = new ArrayList<>();
    private final List<String> transactionIds = new ArrayList<>();

    @BeforeEach
    void createIsolatedCustomer() {
        jdbcTemplate.update(
                "INSERT INTO customers (customer_id, full_name, email) VALUES (?, ?, ?)",
                customerId,
                "Concurrent Integration Test",
                "concurrent-" + testToken + "@example.test");
    }

    @AfterEach
    void removeOnlyTestRowsAfterWorkersFinish() {
        assertTrue(awaitExecutorIdle(30), "Banking executor did not become idle; test rows retained");
        deleteByTransactionIds("transaction_audit");
        deleteByTransactionIds("failed_transactions");
        deleteByTransactionIds("transactions");
        deleteByIds("accounts", "account_id", accountIds);
        jdbcTemplate.update("DELETE FROM customers WHERE customer_id = ?", customerId);
    }

    @Test
    void concurrentDepositsDoNotLoseUpdatesAndAreAuditedByWorkers() throws Exception {
        String accountId = createAccount("10000.00");
        List<String> ids = createTransactionIds("DEP", 50);

        List<Callable<Attempt>> requests = new ArrayList<>();
        for (String id : ids) {
            requests.add(() -> attempt(() -> transactionService.deposit(
                    new DepositRequest(id, accountId, new BigDecimal("100.00"), "Concurrent deposit"))));
        }

        List<Attempt> results = runTogether(requests);

        assertEquals(50, successfulCount(results));
        assertEquals(0, failedCount(results));
        assertEquals(new BigDecimal("15000.00"), accountBalance(accountId));
        assertEquals(50L, transactionCount(ids, "SUCCESS"));
        assertEquals(0L, failedRecordCount(ids));
        assertEquals(50L, auditCount(ids));
        assertEquals(50L, auditCountWithWorkerName(ids));
        assertTrue(distinctAuditWorkers(ids) >= 2,
                "Concurrent requests should be processed by more than one banking worker");
    }

    @Test
    void concurrentWithdrawalsRejectInsufficientFundsWithoutGoingNegative() throws Exception {
        String accountId = createAccount("1000.00");
        List<String> ids = createTransactionIds("WDR", 20);

        List<Callable<Attempt>> requests = new ArrayList<>();
        for (String id : ids) {
            requests.add(() -> attempt(() -> transactionService.withdraw(
                    new WithdrawalRequest(id, accountId, new BigDecimal("100.00"),
                            "Concurrent withdrawal"))));
        }

        List<Attempt> results = runTogether(requests);

        assertEquals(10, successfulCount(results));
        assertEquals(10, failureCount(results, InsufficientBalanceException.class));
        assertEquals(0, unexpectedFailureCount(results, InsufficientBalanceException.class));
        assertEquals(new BigDecimal("0.00"), accountBalance(accountId));
        assertEquals(10L, transactionCount(ids, "SUCCESS"));
        assertEquals(10L, transactionCount(ids, "REJECTED"));
        assertEquals(10L, failedRecordCount(ids));
        assertEquals(20L, auditCount(ids));
        UncategorizedSQLException constraintFailure = assertThrows(
                UncategorizedSQLException.class,
                () -> jdbcTemplate.update(
                        "UPDATE accounts SET balance = -1.00 WHERE account_id = ?",
                        accountId));
        SQLException mysqlException = constraintFailure.getSQLException();
        assertNotNull(mysqlException);
        assertEquals(3819, mysqlException.getErrorCode());
        assertEquals("HY000", mysqlException.getSQLState());
        assertTrue(mysqlException.getMessage().contains("chk_accounts_balance_nonnegative"));
        assertEquals(new BigDecimal("0.00"), accountBalance(accountId));
        assertEquals(0L, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM accounts WHERE account_id = ? AND balance < 0",
                Long.class,
                accountId));
    }

    @Test
    void opposingConcurrentTransfersPreserveBalancesAndUseWorkerAudits() throws Exception {
        String accountA = createAccount("10000.00");
        String accountB = createAccount("10000.00");
        List<String> ids = createTransactionIds("TRF", 50);

        List<Callable<Attempt>> requests = new ArrayList<>();
        for (int index = 0; index < 25; index++) {
            String idAtoB = ids.get(index);
            requests.add(() -> attempt(() -> transactionService.transfer(new TransferRequest(
                    idAtoB, accountA, accountB, new BigDecimal("100.00"), "A to B"))));

            String idBtoA = ids.get(index + 25);
            requests.add(() -> attempt(() -> transactionService.transfer(new TransferRequest(
                    idBtoA, accountB, accountA, new BigDecimal("100.00"), "B to A"))));
        }

        List<Attempt> results = runTogether(requests);

        assertEquals(50, successfulCount(results));
        assertEquals(0, failedCount(results));
        assertEquals(new BigDecimal("10000.00"), accountBalance(accountA));
        assertEquals(new BigDecimal("10000.00"), accountBalance(accountB));
        assertEquals(new BigDecimal("20000.00"),
                accountBalance(accountA).add(accountBalance(accountB)));
        assertEquals(50L, transactionCount(ids, "SUCCESS"));
        assertEquals(50L, auditCount(ids));
        assertEquals(50L, auditCountWithWorkerName(ids));
    }

    @Test
    void concurrentDuplicateIdsOnlyApplyOneDepositAndDoNotCreateFailureRecords() throws Exception {
        String accountId = createAccount("1000.00");
        String duplicatedId = testToken + "-DUP-1";
        transactionIds.add(duplicatedId);
        List<Callable<Attempt>> requests = new ArrayList<>();
        for (int index = 0; index < 12; index++) {
            requests.add(() -> attempt(() -> transactionService.deposit(
                    new DepositRequest(duplicatedId, accountId, new BigDecimal("25.00"),
                            "Concurrent duplicate request"))));
        }

        List<Attempt> results = runTogether(requests);

        assertEquals(1, successfulCount(results));
        assertEquals(11, failureCount(results, DuplicateTransactionException.class));
        assertEquals(new BigDecimal("1025.00"), accountBalance(accountId));
        assertEquals(1L, transactionCount(List.of(duplicatedId)));
        assertEquals(1L, transactionCount(List.of(duplicatedId), "SUCCESS"));
        assertEquals(0L, failedRecordCount(List.of(duplicatedId)));
        assertEquals(1L, auditCount(List.of(duplicatedId)));
    }

    private String createAccount(String initialBalance) {
        String accountId = "A" + UUID.randomUUID().toString().replace("-", "");
        accountIds.add(accountId);
        jdbcTemplate.update(
                "INSERT INTO accounts (account_id, customer_id, account_type, balance, status) "
                        + "VALUES (?, ?, 'SAVINGS', ?, 'ACTIVE')",
                accountId,
                customerId,
                new BigDecimal(initialBalance));
        return accountId;
    }

    private List<String> createTransactionIds(String prefix, int count) {
        List<String> ids = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            String id = testToken + "-" + prefix + "-" + index;
            ids.add(id);
            transactionIds.add(id);
        }
        return ids;
    }

    private List<Attempt> runTogether(List<Callable<Attempt>> requests) throws Exception {
        ExecutorService clients = Executors.newFixedThreadPool(requests.size());
        CountDownLatch ready = new CountDownLatch(requests.size());
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Attempt>> futures = new ArrayList<>(requests.size());
        try {
            for (Callable<Attempt> request : requests) {
                futures.add(clients.submit(() -> {
                    ready.countDown();
                    if (!start.await(CLIENT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Timed out waiting to start concurrent requests");
                    }
                    return request.call();
                }));
            }

            boolean allReady = ready.await(30, TimeUnit.SECONDS);
            start.countDown();
            assertTrue(allReady, "Not all client tasks reached the start gate");

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(CLIENT_TIMEOUT_SECONDS);
            List<Attempt> results = new ArrayList<>(futures.size());
            for (Future<Attempt> future : futures) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    throw new TimeoutException("Concurrent transaction requests exceeded the timeout");
                }
                try {
                    results.add(future.get(remaining, TimeUnit.NANOSECONDS));
                } catch (ExecutionException exception) {
                    throw new AssertionError("A concurrent request task failed unexpectedly",
                            exception.getCause());
                }
            }
            return results;
        } finally {
            start.countDown();
            clients.shutdown();
            if (!clients.awaitTermination(CLIENT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                clients.shutdownNow();
                assertTrue(clients.awaitTermination(30, TimeUnit.SECONDS),
                        "Client test tasks did not stop; test rows will not be cleaned up");
            }
        }
    }

    private Attempt attempt(TransactionCall transactionCall) {
        try {
            TransactionResponse response = transactionCall.call();
            return new Attempt(response, null);
        } catch (RuntimeException exception) {
            return new Attempt(null, exception);
        }
    }

    private long successfulCount(List<Attempt> attempts) {
        return attempts.stream().filter(attempt -> attempt.failure() == null).count();
    }

    private long failedCount(List<Attempt> attempts) {
        return attempts.stream().filter(attempt -> attempt.failure() != null).count();
    }

    private long failureCount(List<Attempt> attempts, Class<? extends RuntimeException> failureType) {
        return attempts.stream()
                .filter(attempt -> failureType.isInstance(attempt.failure()))
                .count();
    }

    private long unexpectedFailureCount(
            List<Attempt> attempts,
            Class<? extends RuntimeException> expectedFailureType) {
        return attempts.stream()
                .filter(attempt -> attempt.failure() != null
                        && !expectedFailureType.isInstance(attempt.failure()))
                .count();
    }

    private BigDecimal accountBalance(String accountId) {
        return jdbcTemplate.queryForObject(
                "SELECT balance FROM accounts WHERE account_id = ?",
                BigDecimal.class,
                accountId);
    }

    private long transactionCount(List<String> ids, String status) {
        Object[] parameters = append(ids.toArray(), status);
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM transactions WHERE transaction_id IN ("
                        + placeholders(ids.size()) + ") AND status = ?",
                Long.class,
                parameters);
    }

    private long transactionCount(List<String> ids) {
        return countForIds("transactions", ids);
    }

    private long failedRecordCount(List<String> ids) {
        return countForIds("failed_transactions", ids);
    }

    private long auditCount(List<String> ids) {
        return countForIds("transaction_audit", ids);
    }

    private long auditCountWithWorkerName(List<String> ids) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM transaction_audit WHERE transaction_id IN ("
                        + placeholders(ids.size())
                        + ") AND thread_name LIKE 'banking-worker-%'",
                Long.class,
                ids.toArray());
    }

    private long distinctAuditWorkers(List<String> ids) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(DISTINCT thread_name) FROM transaction_audit "
                        + "WHERE transaction_id IN (" + placeholders(ids.size()) + ")",
                Long.class,
                ids.toArray());
    }

    private long countForIds(String table, List<String> ids) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE transaction_id IN ("
                        + placeholders(ids.size()) + ")",
                Long.class,
                ids.toArray());
    }

    private boolean awaitExecutorIdle(long timeoutSeconds) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        while (System.nanoTime() < deadline) {
            var stats = transactionExecutor.getStats();
            if (stats.activeWorkerCount() == 0
                    && stats.queueSize() == 0
                    && stats.submittedTaskCount() == stats.completedTaskCount()) {
                return true;
            }
            Thread.yield();
        }
        return false;
    }

    private void deleteByTransactionIds(String table) {
        deleteByIds(table, "transaction_id", transactionIds);
    }

    private void deleteByIds(String table, String column, List<String> ids) {
        for (int start = 0; start < ids.size(); start += 500) {
            List<String> chunk = ids.subList(start, Math.min(start + 500, ids.size()));
            jdbcTemplate.update(
                    "DELETE FROM " + table + " WHERE " + column
                            + " IN (" + placeholders(chunk.size()) + ")",
                    chunk.toArray());
        }
    }

    private String placeholders(int count) {
        return String.join(",", Collections.nCopies(count, "?"));
    }

    private Object[] append(Object[] values, Object extra) {
        Object[] result = new Object[values.length + 1];
        System.arraycopy(values, 0, result, 0, values.length);
        result[values.length] = extra;
        return result;
    }

    private record Attempt(TransactionResponse response, RuntimeException failure) {
    }

    @FunctionalInterface
    private interface TransactionCall {
        TransactionResponse call();
    }
}
