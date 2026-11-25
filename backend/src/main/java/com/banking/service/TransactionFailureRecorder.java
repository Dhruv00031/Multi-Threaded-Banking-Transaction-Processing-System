package com.banking.service;

import com.banking.enums.TransactionStatus;
import com.banking.model.FailedTransaction;
import com.banking.model.Transaction;
import com.banking.model.TransactionAudit;
import com.banking.repository.AccountRepository;
import com.banking.repository.FailedTransactionRepository;
import com.banking.repository.TransactionAuditRepository;
import com.banking.repository.TransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
public class TransactionFailureRecorder {

    private static final Logger LOGGER = LoggerFactory.getLogger(TransactionFailureRecorder.class);

    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final FailedTransactionRepository failedTransactionRepository;
    private final TransactionAuditRepository transactionAuditRepository;

    public TransactionFailureRecorder(
            AccountRepository accountRepository,
            TransactionRepository transactionRepository,
            FailedTransactionRepository failedTransactionRepository,
            TransactionAuditRepository transactionAuditRepository) {
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.failedTransactionRepository = failedTransactionRepository;
        this.transactionAuditRepository = transactionAuditRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(TransactionCommand command, RuntimeException failure) {
        record(command, failure, TransactionStatus.REJECTED);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailed(TransactionCommand command, RuntimeException failure) {
        record(command, failure, TransactionStatus.FAILED);
    }

    private void record(
            TransactionCommand command,
            RuntimeException failure,
            TransactionStatus status) {
        if (command == null) {
            logSkipped(null, "transaction command is null");
            return;
        }
        if (command.transactionId() == null
                || command.transactionId().isBlank()
                || command.transactionId().length() > 64) {
            logSkipped(command, "transaction ID is missing or exceeds 64 characters");
            return;
        }
        if (command.amount() == null
                || command.amount().signum() <= 0
                || command.amount().scale() > 2
                || command.amount().precision() > 19
                || command.amount().precision() - command.amount().scale() > 17) {
            logSkipped(command, "amount is missing, non-positive, or outside the supported precision");
            return;
        }
        if (command.description() != null && command.description().length() > 255) {
            logSkipped(command, "description exceeds 255 characters");
            return;
        }
        if (command.transactionType() == null) {
            logSkipped(command, "transaction type is missing");
            return;
        }
        if (transactionRepository.existsById(command.transactionId())) {
            logSkipped(command, "transaction ID already exists");
            return;
        }
        if (hasSameAccount(command)) {
            logSkipped(command, "source and destination accounts are identical");
            return;
        }

        if (command.sourceAccountId() != null
                && accountRepository.findById(command.sourceAccountId()).isEmpty()) {
            logSkipped(command, "source account does not exist; transaction foreign key would fail");
            return;
        }
        if (command.destinationAccountId() != null
                && accountRepository.findById(command.destinationAccountId()).isEmpty()) {
            logSkipped(command, "destination account does not exist; transaction foreign key would fail");
            return;
        }

        long startTime = System.nanoTime();
        try {
            transactionRepository.save(new Transaction(
                    command.transactionId(),
                    command.transactionType(),
                    command.amount(),
                    command.sourceAccountId(),
                    command.destinationAccountId(),
                    status,
                    command.description(),
                    LocalDateTime.now()));
            failedTransactionRepository.save(new FailedTransaction(
                    null,
                    command.transactionId(),
                    failure.getClass().getSimpleName(),
                    failure.getMessage(),
                    LocalDateTime.now()));
            transactionAuditRepository.save(new TransactionAudit(
                    null,
                    command.transactionId(),
                    status.name(),
                    LocalDateTime.now(),
                    Thread.currentThread().getName(),
                    elapsedMilliseconds(startTime),
                    failure.getMessage()));
        } catch (RuntimeException exception) {
            LOGGER.error("Failure and audit records could not be persisted for transaction '{}'",
                    command.transactionId(),
                    exception);
            throw exception;
        }
    }

    private void logSkipped(TransactionCommand command, String reason) {
        String transactionId = command == null ? null : command.transactionId();
        LOGGER.warn("Failure and audit records were not written for transaction '{}': {}",
                transactionId,
                reason);
    }

    private boolean hasSameAccount(TransactionCommand command) {
        return command.sourceAccountId() != null
                && command.sourceAccountId().equals(command.destinationAccountId());
    }

    private long elapsedMilliseconds(long startTime) {
        return (System.nanoTime() - startTime) / 1_000_000;
    }
}
