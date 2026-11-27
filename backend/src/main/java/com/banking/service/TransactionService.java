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
import com.banking.exception.TransactionProcessingUnavailableException;
import com.banking.model.Transaction;
import com.banking.repository.TransactionRepository;
import org.springframework.stereotype.Service;

import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;

@Service
public class TransactionService {

    private final TransactionExecutor transactionExecutor;
    private final TransactionProcessor transactionProcessor;
    private final TransactionFailureRecorder failureRecorder;
    private final TransactionRepository transactionRepository;

    public TransactionService(
            TransactionExecutor transactionExecutor,
            TransactionProcessor transactionProcessor,
            TransactionFailureRecorder failureRecorder,
            TransactionRepository transactionRepository) {
        this.transactionExecutor = transactionExecutor;
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

    public TransactionExecutorStats getExecutorStats() {
        return transactionExecutor.getStats();
    }

    private TransactionResponse process(TransactionCommand command) {
        Future<ProcessingResult> future;
        try {
            future = transactionExecutor.submit(() -> processOnWorker(command));
        } catch (RejectedExecutionException exception) {
            throw TransactionProcessingUnavailableException.executorUnavailable();
        }

        try {
            ProcessingResult result = future.get();
            if (result.rejection() != null) {
                throw result.rejection();
            }
            return result.response();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw TransactionProcessingUnavailableException.outcomeUnknown(command.transactionId());
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("Transaction processing failed", cause);
        } catch (CancellationException exception) {
            throw TransactionProcessingUnavailableException.executorUnavailable();
        }
    }

    private ProcessingResult processOnWorker(TransactionCommand command) {
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
        return result;
    }
}
