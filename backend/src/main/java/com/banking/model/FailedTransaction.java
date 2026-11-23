package com.banking.model;

import java.time.LocalDateTime;

public record FailedTransaction(
        Long failureId,
        String transactionId,
        String errorCategory,
        String failureReason,
        LocalDateTime failedAt) {
}
