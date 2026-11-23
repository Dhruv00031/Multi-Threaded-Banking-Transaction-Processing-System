package com.banking.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record Account(
        String accountId,
        String customerId,
        String accountType,
        BigDecimal balance,
        String status,
        LocalDateTime createdAt) {
}
