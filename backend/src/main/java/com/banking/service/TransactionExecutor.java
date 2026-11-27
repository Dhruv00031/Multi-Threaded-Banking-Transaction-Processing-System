package com.banking.service;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class TransactionExecutor {

    private static final Logger LOGGER = LoggerFactory.getLogger(TransactionExecutor.class);

    private final int workerCount;
    private final int queueCapacity;
    private final long shutdownTimeoutSeconds;
    private final AtomicLong submittedTaskCount = new AtomicLong();
    private final AtomicLong completedTaskCount = new AtomicLong();
    private final AtomicLong rejectedTaskCount = new AtomicLong();
    private final ThreadPoolExecutor executor;
    private final ExecutorService taskExecutor;

    public TransactionExecutor(
            @Value("${banking.transaction.executor.worker-count:5}") int workerCount,
            @Value("${banking.transaction.executor.queue-capacity:100}") int queueCapacity,
            @Value("${banking.transaction.executor.shutdown-timeout-seconds:30}")
            long shutdownTimeoutSeconds) {
        if (workerCount < 1 || queueCapacity < 1 || shutdownTimeoutSeconds < 1) {
            throw new IllegalArgumentException(
                    "Worker count, queue capacity, and shutdown timeout must be positive");
        }

        this.workerCount = workerCount;
        this.queueCapacity = queueCapacity;
        this.shutdownTimeoutSeconds = shutdownTimeoutSeconds;

        AtomicInteger threadNumber = new AtomicInteger();
        ThreadFactory threadFactory = task ->
                new Thread(task, "banking-worker-" + threadNumber.incrementAndGet());
        BlockingQueue<Runnable> taskQueue = new ArrayBlockingQueue<>(queueCapacity);
        this.executor = new ThreadPoolExecutor(
                workerCount,
                workerCount,
                0L,
                TimeUnit.MILLISECONDS,
                taskQueue,
                threadFactory,
                new ThreadPoolExecutor.AbortPolicy()) {
            @Override
            protected void afterExecute(Runnable task, Throwable failure) {
                super.afterExecute(task, failure);
                completedTaskCount.incrementAndGet();
            }
        };
        this.taskExecutor = this.executor;
    }

    public <T> Future<T> submit(Callable<T> task) {
        FutureTask<T> future = new FutureTask<>(Objects.requireNonNull(task, "task"));
        try {
            taskExecutor.execute(future);
            submittedTaskCount.incrementAndGet();
            return future;
        } catch (RejectedExecutionException exception) {
            rejectedTaskCount.incrementAndGet();
            throw exception;
        }
    }

    public TransactionExecutorStats getStats() {
        return new TransactionExecutorStats(
                workerCount,
                executor.getPoolSize(),
                executor.getActiveCount(),
                executor.getQueue().size(),
                queueCapacity,
                submittedTaskCount.get(),
                completedTaskCount.get(),
                rejectedTaskCount.get());
    }

    boolean isShutdown() {
        return executor.isShutdown();
    }

    @PreDestroy
    public void shutdown() {
        LOGGER.info("Shutting down transaction executor");
        taskExecutor.shutdown();
        try {
            if (!taskExecutor.awaitTermination(shutdownTimeoutSeconds, TimeUnit.SECONDS)) {
                int activeWorkers = executor.getActiveCount();
                List<Runnable> notStartedTasks = taskExecutor.shutdownNow();
                cancelNotStartedTasks(notStartedTasks);
                LOGGER.error(
                        "Transaction executor exceeded its {} second shutdown period; "
                                + "{} active worker(s) were interrupted and {} accepted queued "
                                + "task(s) did not start and were cancelled",
                        shutdownTimeoutSeconds,
                        activeWorkers,
                        notStartedTasks.size());
            } else {
                LOGGER.info("Transaction executor shut down cleanly");
            }
        } catch (InterruptedException exception) {
            int activeWorkers = executor.getActiveCount();
            List<Runnable> notStartedTasks = taskExecutor.shutdownNow();
            cancelNotStartedTasks(notStartedTasks);
            LOGGER.warn(
                    "Transaction executor shutdown was interrupted; {} active worker(s) were "
                            + "interrupted and {} accepted queued task(s) did not start and "
                            + "were cancelled",
                    activeWorkers,
                    notStartedTasks.size());
            Thread.currentThread().interrupt();
        }
    }

    private void cancelNotStartedTasks(List<Runnable> tasks) {
        for (Runnable task : tasks) {
            if (task instanceof Future<?> future) {
                future.cancel(false);
            }
        }
    }
}
