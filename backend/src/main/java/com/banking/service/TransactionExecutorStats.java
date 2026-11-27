package com.banking.service;

public record TransactionExecutorStats(
        int configuredWorkerCount,
        int currentPoolSize,
        int activeWorkerCount,
        int queueSize,
        int queueCapacity,
        long submittedTaskCount,
        long completedTaskCount,
        long rejectedTaskCount) {
}
