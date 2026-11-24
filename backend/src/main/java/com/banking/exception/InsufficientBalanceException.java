package com.banking.exception;

public class InsufficientBalanceException extends RuntimeException {

    public InsufficientBalanceException(String accountId) {
        super("Account '" + accountId + "' has insufficient funds for this transaction");
    }
}
