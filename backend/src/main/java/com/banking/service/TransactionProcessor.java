package com.banking.service;

import com.banking.dto.TransactionResponse;
import com.banking.enums.TransactionStatus;
import com.banking.enums.TransactionType;
import com.banking.exception.AccountNotFoundException;
import com.banking.exception.DuplicateTransactionException;
import com.banking.exception.InactiveAccountException;
import com.banking.exception.InsufficientBalanceException;
import com.banking.exception.InvalidTransactionException;
import com.banking.model.Account;
import com.banking.model.FailedTransaction;
import com.banking.model.Transaction;
import com.banking.model.TransactionAudit;
import com.banking.repository.AccountRepository;
import com.banking.repository.FailedTransactionRepository;
import com.banking.repository.TransactionAuditRepository;
import com.banking.repository.TransactionRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class TransactionProcessor {

    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final TransactionAuditRepository transactionAuditRepository;
    private final FailedTransactionRepository failedTransactionRepository;

    public TransactionProcessor(
            AccountRepository accountRepository,
            TransactionRepository transactionRepository,
            TransactionAuditRepository transactionAuditRepository,
            FailedTransactionRepository failedTransactionRepository) {
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.transactionAuditRepository = transactionAuditRepository;
        this.failedTransactionRepository = failedTransactionRepository;
    }

    @Transactional
    public ProcessingResult process(TransactionCommand command) {
        validateCommand(command);
        if (transactionRepository.existsById(command.transactionId())) {
            throw new DuplicateTransactionException(command.transactionId());
        }

        long startTime = System.nanoTime();
        Map<String, Account> lockedAccounts = lockAccounts(command);
        Account source = findCommandAccount(lockedAccounts, command.sourceAccountId());
        Account destination = findCommandAccount(lockedAccounts, command.destinationAccountId());

        Transaction pendingTransaction = new Transaction(
                command.transactionId(),
                command.transactionType(),
                command.amount(),
                command.sourceAccountId(),
                command.destinationAccountId(),
                TransactionStatus.PENDING,
                command.description(),
                LocalDateTime.now());
        try {
            transactionRepository.save(pendingTransaction);
        } catch (DuplicateKeyException exception) {
            // The primary key is the final arbiter when identical IDs arrive concurrently.
            throw new DuplicateTransactionException(command.transactionId());
        }

        if (source != null && !"ACTIVE".equals(source.status())) {
            return reject(command, new InactiveAccountException(source.accountId()), startTime);
        }
        if (destination != null && !"ACTIVE".equals(destination.status())) {
            return reject(command, new InactiveAccountException(destination.accountId()), startTime);
        }

        BigDecimal sourceBalance = source == null ? null : source.balance();
        BigDecimal destinationBalance = destination == null ? null : destination.balance();

        if ((command.transactionType() == TransactionType.WITHDRAWAL
                || command.transactionType() == TransactionType.TRANSFER)
                && sourceBalance.compareTo(command.amount()) < 0) {
            return reject(command, new InsufficientBalanceException(source.accountId()), startTime);
        }

        if (command.transactionType() == TransactionType.DEPOSIT) {
            destinationBalance = destinationBalance.add(command.amount());
            updateBalance(destination.accountId(), destinationBalance);
        } else if (command.transactionType() == TransactionType.WITHDRAWAL) {
            sourceBalance = sourceBalance.subtract(command.amount());
            updateBalance(source.accountId(), sourceBalance);
        } else {
            sourceBalance = sourceBalance.subtract(command.amount());
            destinationBalance = destinationBalance.add(command.amount());
            updateBalance(source.accountId(), sourceBalance);
            updateBalance(destination.accountId(), destinationBalance);
        }

        updateTransactionStatus(command.transactionId(), TransactionStatus.SUCCESS);
        saveAudit(command, "SUCCESS", "Transaction completed", startTime);

        return new ProcessingResult(
                new TransactionResponse(
                        command.transactionId(),
                        command.transactionType(),
                        TransactionStatus.SUCCESS,
                        command.amount(),
                        command.sourceAccountId(),
                        command.destinationAccountId(),
                        sourceBalance,
                        destinationBalance,
                        transactionRepository.findById(command.transactionId())
                                .orElseThrow(() -> new IllegalStateException(
                                        "Completed transaction could not be retrieved"))
                                .createdAt()),
                null);
    }

    private void validateCommand(TransactionCommand command) {
        if (command == null
                || command.transactionId() == null
                || command.transactionId().isBlank()
                || command.transactionId().length() > 64
                || command.transactionType() == null
                || command.amount() == null
                || command.amount().signum() <= 0
                || command.amount().scale() > 2
                || command.amount().precision() > 19
                || command.amount().precision() - command.amount().scale() > 17
                || command.description() != null && command.description().length() > 255) {
            throw new InvalidTransactionException("Transaction ID and a positive amount are required");
        }
        if (command.transactionType() == TransactionType.DEPOSIT
                && (!validAccountId(command.destinationAccountId())
                || command.sourceAccountId() != null)) {
            throw new InvalidTransactionException("A valid deposit account is required");
        }
        if (command.transactionType() == TransactionType.WITHDRAWAL
                && (!validAccountId(command.sourceAccountId())
                || command.destinationAccountId() != null)) {
            throw new InvalidTransactionException("A valid withdrawal account is required");
        }
        if (command.transactionType() == TransactionType.TRANSFER) {
            if (!validAccountId(command.sourceAccountId())
                    || !validAccountId(command.destinationAccountId())) {
                throw new InvalidTransactionException("Source and destination accounts are required");
            }
            if (command.sourceAccountId().equals(command.destinationAccountId())) {
                throw new InvalidTransactionException("Source and destination accounts must be different");
            }
        }
    }

    private boolean validAccountId(String accountId) {
        return accountId != null
                && !accountId.isBlank()
                && accountId.length() <= 36;
    }

    private Map<String, Account> lockAccounts(TransactionCommand command) {
        List<String> accountIds = new ArrayList<>();
        if (command.sourceAccountId() != null) {
            accountIds.add(command.sourceAccountId());
        }
        if (command.destinationAccountId() != null) {
            accountIds.add(command.destinationAccountId());
        }
        accountIds.sort(Comparator.naturalOrder());

        Map<String, Account> accounts = new HashMap<>();
        for (String accountId : accountIds) {
            Account account = accountRepository.findByIdForUpdate(accountId)
                    .orElseThrow(() -> new AccountNotFoundException(accountId));
            accounts.put(accountId, account);
        }
        return accounts;
    }

    private Account findCommandAccount(Map<String, Account> accounts, String accountId) {
        return accountId == null ? null : accounts.get(accountId);
    }

    private void updateBalance(String accountId, BigDecimal balance) {
        if (!accountRepository.updateBalance(accountId, balance)) {
            throw new IllegalStateException("Account balance could not be updated");
        }
    }

    private ProcessingResult reject(
            TransactionCommand command,
            RuntimeException rejection,
            long startTime) {
        updateTransactionStatus(command.transactionId(), TransactionStatus.REJECTED);
        failedTransactionRepository.save(new FailedTransaction(
                null,
                command.transactionId(),
                rejection.getClass().getSimpleName(),
                rejection.getMessage(),
                LocalDateTime.now()));
        saveAudit(command, "REJECTED", rejection.getMessage(), startTime);
        return new ProcessingResult(null, rejection);
    }

    private void saveAudit(
            TransactionCommand command,
            String eventType,
            String details,
            long startTime) {
        transactionAuditRepository.save(new TransactionAudit(
                null,
                command.transactionId(),
                eventType,
                LocalDateTime.now(),
                Thread.currentThread().getName(),
                (System.nanoTime() - startTime) / 1_000_000,
                details));
    }

    private void updateTransactionStatus(String transactionId, TransactionStatus status) {
        if (!transactionRepository.updateStatus(transactionId, status)) {
            throw new IllegalStateException("Transaction status could not be updated");
        }
    }
}
