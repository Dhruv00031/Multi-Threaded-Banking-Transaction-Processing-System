package com.banking.model;

import java.time.LocalDateTime;

public record Customer(
        String customerId,
        String fullName,
        String email,
        String phone,
        LocalDateTime createdAt) {
}
