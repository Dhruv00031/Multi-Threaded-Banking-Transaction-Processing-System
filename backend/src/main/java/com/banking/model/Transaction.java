package com.banking.model;

import com.banking.enums.TransactionStatus;
import com.banking.enums.TransactionType;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record Transaction(
        String transactionId,
        TransactionType transactionType,
        BigDecimal amount,
        String sourceAccountId,
        String destinationAccountId,
        TransactionStatus status,
        String description,
        LocalDateTime createdAt) {
}
