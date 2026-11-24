package com.banking.service;

import com.banking.enums.TransactionType;

import java.math.BigDecimal;

record TransactionCommand(
        String transactionId,
        TransactionType transactionType,
        BigDecimal amount,
        String sourceAccountId,
        String destinationAccountId,
        String description) {
}
