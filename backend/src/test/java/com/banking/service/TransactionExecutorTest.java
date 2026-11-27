package com.banking.service;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TransactionExecutorTest {

    @Test
    void executesTasksOnNamedWorkersAndUpdatesCompletionMetrics() throws Exception {
        TransactionExecutor executor = new TransactionExecutor(1, 2, 2);
        try {
            Future<String> workerName = executor.submit(
                    () -> Thread.currentThread().getName());

            assertTrue(workerName.get(2, TimeUnit.SECONDS).startsWith("banking-worker-"));
            awaitCondition(() -> executor.getStats().completedTaskCount() == 1);
            assertEquals(1, executor.getStats().submittedTaskCount());
            assertEquals(1, executor.getStats().completedTaskCount());
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void boundedQueueRejectsExcessTasksAndTracksCounters() throws Exception {
        TransactionExecutor executor = new TransactionExecutor(1, 1, 2);
        CountDownLatch firstTaskStarted = new CountDownLatch(1);
        CountDownLatch releaseTasks = new CountDownLatch(1);
        try {
            Future<Void> running = executor.submit(() -> {
                firstTaskStarted.countDown();
                releaseTasks.await();
                return null;
            });
            assertTrue(firstTaskStarted.await(2, TimeUnit.SECONDS));

            Future<Void> queued = executor.submit(() -> null);
            assertThrows(RejectedExecutionException.class, () -> executor.submit(() -> null));

            awaitCondition(() -> executor.getStats().submittedTaskCount() == 2);
            TransactionExecutorStats stats = executor.getStats();
            assertEquals(1, stats.configuredWorkerCount());
            assertEquals(1, stats.activeWorkerCount());
            assertEquals(1, stats.queueSize());
            assertEquals(1, stats.queueCapacity());
            assertEquals(2, stats.submittedTaskCount());
            assertEquals(1, stats.rejectedTaskCount());

            releaseTasks.countDown();
            running.get(2, TimeUnit.SECONDS);
            queued.get(2, TimeUnit.SECONDS);
            awaitCondition(() -> executor.getStats().completedTaskCount() == 2);
        } finally {
            releaseTasks.countDown();
            executor.shutdown();
        }
    }

    @Test
    void futurePreservesWorkerFailureAsItsCause() throws Exception {
        TransactionExecutor executor = new TransactionExecutor(1, 1, 2);
        IllegalArgumentException failure = new IllegalArgumentException("invalid task");
        try {
            Future<Void> future = executor.submit(() -> {
                throw failure;
            });

            ExecutionException actual = assertThrows(
                    ExecutionException.class,
                    () -> future.get(2, TimeUnit.SECONDS));
            assertEquals(failure, actual.getCause());
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void independentTasksRunAtTheSameTimeOnSeparateWorkers() throws Exception {
        TransactionExecutor executor = new TransactionExecutor(2, 2, 2);
        CountDownLatch bothTasksStarted = new CountDownLatch(2);
        CountDownLatch releaseTasks = new CountDownLatch(1);
        try {
            Future<Void> first = executor.submit(() -> {
                bothTasksStarted.countDown();
                releaseTasks.await();
                return null;
            });
            Future<Void> second = executor.submit(() -> {
                bothTasksStarted.countDown();
                releaseTasks.await();
                return null;
            });

            assertTrue(bothTasksStarted.await(2, TimeUnit.SECONDS));
            releaseTasks.countDown();
            first.get(2, TimeUnit.SECONDS);
            second.get(2, TimeUnit.SECONDS);
        } finally {
            releaseTasks.countDown();
            executor.shutdown();
        }
    }

    @Test
    void gracefulShutdownCompletesAcceptedRunningAndQueuedTasks() throws Exception {
        TransactionExecutor executor = new TransactionExecutor(1, 1, 2);
        CountDownLatch taskStarted = new CountDownLatch(1);
        CountDownLatch releaseTask = new CountDownLatch(1);
        AtomicBoolean shutdownReturned = new AtomicBoolean();
        Thread shutdownThread = null;
        try {
            Future<Void> running = executor.submit(() -> {
                taskStarted.countDown();
                releaseTask.await();
                return null;
            });
            assertTrue(taskStarted.await(2, TimeUnit.SECONDS));
            Future<Void> queued = executor.submit(() -> null);

            shutdownThread = new Thread(() -> {
                executor.shutdown();
                shutdownReturned.set(true);
            });
            shutdownThread.start();
            awaitCondition(executor::isShutdown);
            releaseTask.countDown();

            running.get(2, TimeUnit.SECONDS);
            queued.get(2, TimeUnit.SECONDS);
            shutdownThread.join(TimeUnit.SECONDS.toMillis(2));
            assertTrue(shutdownReturned.get());
            assertFalse(shutdownThread.isAlive());
            assertEquals(2, executor.getStats().completedTaskCount());
        } finally {
            releaseTask.countDown();
            if (shutdownThread != null && shutdownThread.isAlive()) {
                shutdownThread.interrupt();
            }
            executor.shutdown();
        }
    }

    private void awaitCondition(BooleanSupplier condition) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.yield();
        }
        assertTrue(condition.getAsBoolean(), "Condition was not met before the timeout");
    }
}
