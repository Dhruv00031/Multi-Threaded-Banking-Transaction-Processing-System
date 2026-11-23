package com.banking.dto;

import com.banking.enums.TransactionStatus;
import com.banking.enums.TransactionType;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record TransactionResponse(
        String transactionId,
        TransactionType transactionType,
        TransactionStatus status,
        BigDecimal amount,
        String sourceAccountId,
        String destinationAccountId,
        BigDecimal sourceBalance,
        BigDecimal destinationBalance,
        LocalDateTime createdAt) {
}
