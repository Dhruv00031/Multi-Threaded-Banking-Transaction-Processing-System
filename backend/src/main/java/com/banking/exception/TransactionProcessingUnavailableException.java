package com.banking.exception;

public class TransactionProcessingUnavailableException extends RuntimeException {

    private TransactionProcessingUnavailableException(String message) {
        super(message);
    }

    public static TransactionProcessingUnavailableException executorUnavailable() {
        return new TransactionProcessingUnavailableException(
                "Transaction processing is temporarily unavailable; please try again later");
    }

    public static TransactionProcessingUnavailableException outcomeUnknown(String transactionId) {
        return new TransactionProcessingUnavailableException(
                "The wait for transaction processing was interrupted and the outcome may be "
                        + "unknown. Check transaction ID '" + transactionId
                        + "' before retrying");
    }
}
