package com.banking.exception;

public class DuplicateTransactionException extends RuntimeException {

    public DuplicateTransactionException(String transactionId) {
        super("Transaction ID '" + transactionId + "' has already been used");
    }
}
