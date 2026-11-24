package com.banking.service;

import com.banking.dto.DepositRequest;
import com.banking.dto.TransactionResponse;
import com.banking.dto.TransferRequest;
import com.banking.dto.WithdrawalRequest;
import com.banking.enums.TransactionType;
import com.banking.exception.AccountNotFoundException;
import com.banking.exception.DuplicateTransactionException;
import com.banking.exception.InvalidTransactionException;
import com.banking.exception.TransactionNotFoundException;
import com.banking.model.Transaction;
import com.banking.repository.TransactionRepository;
import org.springframework.stereotype.Service;

@Service
public class TransactionService {

    private final TransactionProcessor transactionProcessor;
    private final TransactionFailureRecorder failureRecorder;
    private final TransactionRepository transactionRepository;

    public TransactionService(
            TransactionProcessor transactionProcessor,
            TransactionFailureRecorder failureRecorder,
            TransactionRepository transactionRepository) {
        this.transactionProcessor = transactionProcessor;
        this.failureRecorder = failureRecorder;
        this.transactionRepository = transactionRepository;
    }

    public TransactionResponse deposit(DepositRequest request) {
        return process(new TransactionCommand(
                request.transactionId(),
                TransactionType.DEPOSIT,
                request.amount(),
                null,
                request.accountId(),
                request.description()));
    }

    public TransactionResponse withdraw(WithdrawalRequest request) {
        return process(new TransactionCommand(
                request.transactionId(),
                TransactionType.WITHDRAWAL,
                request.amount(),
                request.accountId(),
                null,
                request.description()));
    }

    public TransactionResponse transfer(TransferRequest request) {
        return process(new TransactionCommand(
                request.transactionId(),
                TransactionType.TRANSFER,
                request.amount(),
                request.sourceAccountId(),
                request.destinationAccountId(),
                request.description()));
    }

    public Transaction getTransaction(String transactionId) {
        return transactionRepository.findById(transactionId)
                .orElseThrow(() -> new TransactionNotFoundException(transactionId));
    }

    private TransactionResponse process(TransactionCommand command) {
        ProcessingResult result;
        try {
            result = transactionProcessor.process(command);
        } catch (DuplicateTransactionException exception) {
            throw exception;
        } catch (AccountNotFoundException | InvalidTransactionException exception) {
            failureRecorder.record(command, exception);
            throw exception;
        } catch (IllegalStateException exception) {
            failureRecorder.recordFailed(command, exception);
            throw exception;
        }

        if (result.rejection() != null) {
            throw result.rejection();
        }
        return result.response();
    }
}
