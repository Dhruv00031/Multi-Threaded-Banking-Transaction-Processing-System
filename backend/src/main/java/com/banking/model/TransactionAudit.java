package com.banking.model;

import java.time.LocalDateTime;

public record TransactionAudit(
        Long auditId,
        String transactionId,
        String eventType,
        LocalDateTime eventTimestamp,
        String threadName,
        Long durationMs,
        String details) {
}
