package com.banking.integration;

import com.banking.dto.DepositRequest;
import com.banking.dto.TransactionResponse;
import com.banking.enums.TransactionStatus;
import com.banking.exception.AccountNotFoundException;
import com.banking.exception.DuplicateTransactionException;
import com.banking.exception.InactiveAccountException;
import com.banking.exception.InsufficientBalanceException;
import com.banking.exception.InvalidTransactionException;
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
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(properties = {
        "banking.transaction.executor.worker-count=5",
        "banking.transaction.executor.queue-capacity=100",
        "banking.transaction.executor.shutdown-timeout-seconds=30"
})
@EnabledIfEnvironmentVariable(named = "DB_PERFORMANCE_TESTS", matches = "true")
class MySqlTransactionPerformanceIT {

    private static final int CLIENT_CONCURRENCY = 10;
    private static final BigDecimal DEPOSIT_AMOUNT = new BigDecimal("0.01");

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TransactionService transactionService;

    @Autowired
    private TransactionExecutor transactionExecutor;

    private final String testToken = UUID.randomUUID().toString().replace("-", "");
    private final String customerId = "P" + testToken;
    private final List<String> accountIds = new ArrayList<>();
    private final List<String> transactionIds = new ArrayList<>();
    private String warmupAccountId;

    @BeforeEach
    void createIsolatedPerformanceData() {
        jdbcTemplate.update(
                "INSERT INTO customers (customer_id, full_name, email) VALUES (?, ?, ?)",
                customerId,
                "Performance Integration Test",
                "performance-" + testToken + "@example.test");

        warmupAccountId = createAccount("0.00");
    }

    @AfterEach
    void removeOnlyPerformanceRowsAfterWorkersFinish() {
        assertTrue(awaitExecutorIdle(60),
                "Executor did not become idle; performance test rows retained");
        deleteByTransactionIds("transaction_audit");
        deleteByTransactionIds("failed_transactions");
        deleteByTransactionIds("transactions");
        deleteByIds("accounts", "account_id", accountIds);
        jdbcTemplate.update("DELETE FROM customers WHERE customer_id = ?", customerId);
    }

    @Test
    void benchmarks100500And1000TransactionsForIndependentAndSharedAccounts() throws Exception {
        warmUp();
        int[] workloadSizes = {100, 500, 1000};
        for (int count : workloadSizes) {
            runWorkload("independent-accounts", count, false);
            runWorkload("shared-account", count, true);
        }
    }

    private void warmUp() {
        for (int index = 0; index < 20; index++) {
            transactionService.deposit(new DepositRequest(
                    transactionId("WARM", index),
                    warmupAccountId,
                    DEPOSIT_AMOUNT,
                    "Performance warm-up"));
        }
    }

    private void runWorkload(String name, int count, boolean sharedAccount) throws Exception {
        List<String> workloadTransactionIds = createTransactionIds(name + "-" + count, count);
        String workloadSharedAccountId = sharedAccount ? createAccount("10000.00") : null;
        List<String> workloadIndependentAccounts = new ArrayList<>();
        if (!sharedAccount) {
            for (int index = 0; index < count; index++) {
                workloadIndependentAccounts.add(createAccount("0.00"));
            }
        }
        TransactionExecutorStats before = transactionExecutor.getStats();
        LongAdder successful = new LongAdder();
        LongAdder businessRejected = new LongAdder();
        LongAdder executorRejected = new LongAdder();
        LongAdder unexpectedFailures = new LongAdder();
        List<Long> requestLatenciesNanos = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger nextIndex = new AtomicInteger();
        CountDownLatch clientsReady = new CountDownLatch(CLIENT_CONCURRENCY);
        CountDownLatch startClients = new CountDownLatch(1);
        ExecutorService clients = Executors.newFixedThreadPool(CLIENT_CONCURRENCY);
        List<Future<Void>> clientTasks = new ArrayList<>(CLIENT_CONCURRENCY);
        long startTime = 0;

        try {
            for (int client = 0; client < CLIENT_CONCURRENCY; client++) {
                clientTasks.add(clients.submit(() -> {
                    clientsReady.countDown();
                    if (!startClients.await(30, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Timed out waiting for benchmark start");
                    }

                    while (true) {
                        int index = nextIndex.getAndIncrement();
                        if (index >= count) {
                            return null;
                        }
                        String transactionId = workloadTransactionIds.get(index);
                        String accountId = sharedAccount
                                ? workloadSharedAccountId
                                : workloadIndependentAccounts.get(index);
                        long requestStart = System.nanoTime();
                        try {
                            TransactionResponse response = transactionService.deposit(
                                    new DepositRequest(
                                            transactionId,
                                            accountId,
                                            DEPOSIT_AMOUNT,
                                            "Performance workload: " + name));
                            if (response.status() == TransactionStatus.SUCCESS) {
                                successful.increment();
                            } else {
                                businessRejected.increment();
                            }
                        } catch (AccountNotFoundException
                                 | DuplicateTransactionException
                                 | InactiveAccountException
                                 | InsufficientBalanceException
                                 | InvalidTransactionException exception) {
                            businessRejected.increment();
                        } catch (TransactionProcessingUnavailableException exception) {
                            executorRejected.increment();
                        } catch (RuntimeException exception) {
                            unexpectedFailures.increment();
                        } finally {
                            requestLatenciesNanos.add(System.nanoTime() - requestStart);
                        }
                    }
                }));
            }

            assertTrue(clientsReady.await(30, TimeUnit.SECONDS),
                    "Benchmark clients did not reach the start gate");
            startTime = System.nanoTime();
            startClients.countDown();

            long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(3);
            for (Future<Void> clientTask : clientTasks) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    throw new java.util.concurrent.TimeoutException(
                            "Benchmark workload exceeded three minutes");
                }
                try {
                    clientTask.get(remaining, TimeUnit.NANOSECONDS);
                } catch (ExecutionException exception) {
                    throw new AssertionError("Benchmark client task failed", exception.getCause());
                }
            }
        } finally {
            startClients.countDown();
            clients.shutdown();
            if (!clients.awaitTermination(3, TimeUnit.MINUTES)) {
                clients.shutdownNow();
                assertTrue(clients.awaitTermination(30, TimeUnit.SECONDS),
                        "Benchmark clients did not stop; test rows will not be cleaned up");
            }
        }

        long elapsedNanos = System.nanoTime() - startTime;
        TransactionExecutorStats after = transactionExecutor.getStats();
        LatencySummary latency = summarize(requestLatenciesNanos);
        long auditedTransactions = countForIds("transaction_audit", workloadTransactionIds);
        Double averageProcessorDurationMs = jdbcTemplate.queryForObject(
                "SELECT AVG(duration_ms) FROM transaction_audit WHERE transaction_id IN ("
                        + placeholders(workloadTransactionIds.size()) + ")",
                Double.class,
                workloadTransactionIds.toArray());

        assertEquals(count, successful.sum() + businessRejected.sum()
                + executorRejected.sum() + unexpectedFailures.sum());
        assertEquals(count, successful.sum());
        assertEquals(0, businessRejected.sum());
        assertEquals(0, executorRejected.sum());
        assertEquals(0, unexpectedFailures.sum());
        assertEquals(count, transactionCountInStatus(workloadTransactionIds, "SUCCESS"));
        assertEquals(count, auditedTransactions);
        assertNotNull(averageProcessorDurationMs);

        BigDecimal expectedBalance = sharedAccount
                ? new BigDecimal("10000.00").add(DEPOSIT_AMOUNT.multiply(BigDecimal.valueOf(count)))
                : DEPOSIT_AMOUNT.multiply(BigDecimal.valueOf(count));
        BigDecimal actualBalance = sharedAccount
                ? accountBalance(workloadSharedAccountId)
                : sumBalances(workloadIndependentAccounts);
        assertEquals(expectedBalance, actualBalance);

        long executorRejectedDelta = after.rejectedTaskCount() - before.rejectedTaskCount();
        assertEquals(executorRejected.sum(), executorRejectedDelta);
        double elapsedSeconds = elapsedNanos / 1_000_000_000.0;
        double throughput = count / elapsedSeconds;

        System.out.printf(
                Locale.ROOT,
                "PERF workload=%s submitted=%d clients=%d workers=%d queueCapacity=%d "
                        + "successful=%d businessRejected=%d executorRejected=%d "
                        + "unexpectedFailures=%d elapsedSeconds=%.3f throughputTxPerSecond=%.2f "
                        + "avgEndToEndMs=%.3f minMs=%.3f maxMs=%.3f p50Ms=%.3f p95Ms=%.3f "
                        + "p99Ms=%.3f avgAuditedProcessorMs=%.3f%n",
                name,
                count,
                CLIENT_CONCURRENCY,
                after.configuredWorkerCount(),
                after.queueCapacity(),
                successful.sum(),
                businessRejected.sum(),
                executorRejectedDelta,
                unexpectedFailures.sum(),
                elapsedSeconds,
                throughput,
                nanosToMillis(latency.averageNanos()),
                nanosToMillis(latency.minimumNanos()),
                nanosToMillis(latency.maximumNanos()),
                nanosToMillis(latency.p50Nanos()),
                nanosToMillis(latency.p95Nanos()),
                nanosToMillis(latency.p99Nanos()),
                averageProcessorDurationMs);
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

    private List<String> createTransactionIds(String name, int count) {
        List<String> ids = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            ids.add(transactionId(name, index));
        }
        return ids;
    }

    private String transactionId(String category, int index) {
        String id = testToken + "-" + category + "-" + index;
        transactionIds.add(id);
        return id;
    }

    private BigDecimal accountBalance(String accountId) {
        return jdbcTemplate.queryForObject(
                "SELECT balance FROM accounts WHERE account_id = ?",
                BigDecimal.class,
                accountId);
    }

    private BigDecimal sumBalances(List<String> ids) {
        return jdbcTemplate.queryForObject(
                "SELECT SUM(balance) FROM accounts WHERE account_id IN ("
                        + placeholders(ids.size()) + ")",
                BigDecimal.class,
                ids.toArray());
    }

    private long countForIds(String table, List<String> ids) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE transaction_id IN ("
                        + placeholders(ids.size()) + ")",
                Long.class,
                ids.toArray());
    }

    private long transactionCountInStatus(List<String> ids, String status) {
        Object[] parameters = new Object[ids.size() + 1];
        System.arraycopy(ids.toArray(), 0, parameters, 0, ids.size());
        parameters[ids.size()] = status;
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM transactions WHERE transaction_id IN ("
                        + placeholders(ids.size()) + ") AND status = ?",
                Long.class,
                parameters);
    }

    private LatencySummary summarize(List<Long> samples) {
        List<Long> sorted = new ArrayList<>(samples);
        Collections.sort(sorted);
        assertTrue(!sorted.isEmpty(), "Benchmark should produce latency samples");
        double average = sorted.stream().mapToLong(Long::longValue).average().orElseThrow();
        return new LatencySummary(
                average,
                sorted.get(0),
                sorted.get(sorted.size() - 1),
                percentile(sorted, 0.50),
                percentile(sorted, 0.95),
                percentile(sorted, 0.99));
    }

    private long percentile(List<Long> sorted, double percentile) {
        int index = (int) Math.ceil(percentile * sorted.size()) - 1;
        return sorted.get(Math.max(0, index));
    }

    private double nanosToMillis(double nanos) {
        return nanos / 1_000_000.0;
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

    private record LatencySummary(
            double averageNanos,
            long minimumNanos,
            long maximumNanos,
            long p50Nanos,
            long p95Nanos,
            long p99Nanos) {
    }
}
