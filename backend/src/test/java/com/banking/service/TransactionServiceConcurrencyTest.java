package com.banking.service;

import com.banking.dto.DepositRequest;
import com.banking.dto.TransactionResponse;
import com.banking.enums.TransactionStatus;
import com.banking.enums.TransactionType;
import com.banking.exception.InsufficientBalanceException;
import com.banking.exception.TransactionProcessingUnavailableException;
import com.banking.repository.TransactionRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TransactionServiceConcurrencyTest {

    private TransactionExecutor executor;
    private TransactionProcessor transactionProcessor;
    private TransactionService transactionService;

    @BeforeEach
    void setUp() {
        executor = new TransactionExecutor(1, 2, 2);
        transactionProcessor = mock(TransactionProcessor.class);
        transactionService = new TransactionService(
                executor,
                transactionProcessor,
                mock(TransactionFailureRecorder.class),
                mock(TransactionRepository.class));
    }

    @AfterEach
    void shutDownExecutor() {
        executor.shutdown();
    }

    @Test
    void invokesProcessorOnWorkerAndReturnsItsResultSynchronously() {
        AtomicReference<String> processingThreadName = new AtomicReference<>();
        TransactionResponse response = new TransactionResponse(
                "TXN-1",
                TransactionType.DEPOSIT,
                TransactionStatus.SUCCESS,
                new BigDecimal("10.00"),
                null,
                "ACC-1",
                null,
                new BigDecimal("110.00"),
                LocalDateTime.now());
        when(transactionProcessor.process(any())).thenAnswer(invocation -> {
            processingThreadName.set(Thread.currentThread().getName());
            return new ProcessingResult(response, null);
        });

        TransactionResponse actual = transactionService.deposit(new DepositRequest(
                "TXN-1", "ACC-1", new BigDecimal("10.00"), null));

        assertSame(response, actual);
        assertTrue(processingThreadName.get().startsWith("banking-worker-"));
    }

    @Test
    void unwrapsFutureAndPreservesBusinessException() {
        InsufficientBalanceException failure = new InsufficientBalanceException("ACC-1");
        when(transactionProcessor.process(any())).thenThrow(failure);

        InsufficientBalanceException actual = org.junit.jupiter.api.Assertions.assertThrows(
                InsufficientBalanceException.class,
                () -> transactionService.deposit(new DepositRequest(
                        "TXN-2", "ACC-1", new BigDecimal("10.00"), null)));

        assertSame(failure, actual);
    }

    @Test
    void interruptedWaitRestoresInterruptAndReportsUnknownOutcome() throws Exception {
        CountDownLatch processorStarted = new CountDownLatch(1);
        CountDownLatch releaseProcessor = new CountDownLatch(1);
        CountDownLatch callerFinished = new CountDownLatch(1);
        AtomicReference<Throwable> callerFailure = new AtomicReference<>();
        AtomicReference<Boolean> interruptRestored = new AtomicReference<>(false);
        when(transactionProcessor.process(any())).thenAnswer(invocation -> {
            processorStarted.countDown();
            releaseProcessor.await();
            return new ProcessingResult(null, null);
        });

        Thread caller = new Thread(() -> {
            Thread.currentThread().interrupt();
            try {
                transactionService.deposit(new DepositRequest(
                        "TXN-INTERRUPTED", "ACC-1", new BigDecimal("10.00"), null));
            } catch (TransactionProcessingUnavailableException failure) {
                callerFailure.set(failure);
                interruptRestored.set(Thread.currentThread().isInterrupted());
            } finally {
                Thread.interrupted();
                callerFinished.countDown();
            }
        });
        caller.start();

        try {
            assertTrue(callerFinished.await(2, TimeUnit.SECONDS));
            assertTrue(processorStarted.await(2, TimeUnit.SECONDS));
            assertTrue(callerFailure.get() instanceof TransactionProcessingUnavailableException);
            assertTrue(callerFailure.get().getMessage().contains("outcome may be unknown"));
            assertTrue(callerFailure.get().getMessage().contains("TXN-INTERRUPTED"));
            assertTrue(interruptRestored.get());
        } finally {
            releaseProcessor.countDown();
            caller.join(TimeUnit.SECONDS.toMillis(2));
        }
    }

    @Test
    void saturatedExecutorIsTranslatedToServiceUnavailable() throws Exception {
        CountDownLatch firstTaskStarted = new CountDownLatch(1);
        CountDownLatch releaseFirstTask = new CountDownLatch(1);
        Future<Void> running = executor.submit(() -> {
            firstTaskStarted.countDown();
            releaseFirstTask.await();
            return null;
        });
        assertTrue(firstTaskStarted.await(2, TimeUnit.SECONDS));
        Future<Void> queued = executor.submit(() -> null);
        Future<Void> secondQueued = executor.submit(() -> null);

        try {
            assertThrows(
                    TransactionProcessingUnavailableException.class,
                    () -> transactionService.deposit(new DepositRequest(
                            "TXN-REJECTED", "ACC-1", new BigDecimal("10.00"), null)));
            assertTrue(executor.getStats().rejectedTaskCount() == 1);
        } finally {
            releaseFirstTask.countDown();
            running.get(2, TimeUnit.SECONDS);
            queued.get(2, TimeUnit.SECONDS);
            secondQueued.get(2, TimeUnit.SECONDS);
        }
    }
}
