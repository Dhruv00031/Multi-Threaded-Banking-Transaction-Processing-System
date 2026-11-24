package com.banking.service;

import com.banking.dto.TransactionResponse;

record ProcessingResult(TransactionResponse response, RuntimeException rejection) {
}
