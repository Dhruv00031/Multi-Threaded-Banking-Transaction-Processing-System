package com.banking.exception;

public class InactiveAccountException extends RuntimeException {

    public InactiveAccountException(String accountId) {
        super("Account '" + accountId + "' is not active");
    }
}
