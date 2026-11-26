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
import com.banking.model.Transaction;
import com.banking.repository.AccountRepository;
import com.banking.repository.FailedTransactionRepository;
import com.banking.repository.TransactionAuditRepository;
import com.banking.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mock;

class TransactionProcessorTest {

    private AccountRepository accountRepository;
    private TransactionRepository transactionRepository;
    private TransactionAuditRepository transactionAuditRepository;
    private FailedTransactionRepository failedTransactionRepository;
    private TransactionProcessor processor;

    @BeforeEach
    void setUp() {
        accountRepository = mock(AccountRepository.class);
        transactionRepository = mock(TransactionRepository.class);
        transactionAuditRepository = mock(TransactionAuditRepository.class);
        failedTransactionRepository = mock(FailedTransactionRepository.class);
        processor = new TransactionProcessor(
                accountRepository,
                transactionRepository,
                transactionAuditRepository,
                failedTransactionRepository);

        when(transactionRepository.existsById(any())).thenReturn(false);
        when(transactionRepository.save(any(Transaction.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(transactionRepository.updateStatus(any(), any())).thenReturn(true);
    }

    @Test
    void depositAddsAmountAndReturnsUpdatedBalance() {
        when(accountRepository.findByIdForUpdate("ACC-1"))
                .thenReturn(Optional.of(account("ACC-1", "100.00", "ACTIVE")));
        when(accountRepository.updateBalance("ACC-1", new BigDecimal("125.50"))).thenReturn(true);
        when(transactionRepository.findById("TXN-1"))
                .thenReturn(Optional.of(transaction("TXN-1", TransactionType.DEPOSIT)));

        ProcessingResult result = processor.process(command(
                "TXN-1", TransactionType.DEPOSIT, "25.50", null, "ACC-1"));

        assertEquals(new BigDecimal("125.50"), result.response().destinationBalance());
        assertEquals(TransactionStatus.SUCCESS, result.response().status());
        assertNull(result.rejection());
    }

    @Test
    void withdrawalDeductsAmountFromLockedAccount() {
        when(accountRepository.findByIdForUpdate("ACC-1"))
                .thenReturn(Optional.of(account("ACC-1", "100.00", "ACTIVE")));
        when(accountRepository.updateBalance("ACC-1", new BigDecimal("60.00"))).thenReturn(true);
        when(transactionRepository.findById("TXN-2"))
                .thenReturn(Optional.of(transaction("TXN-2", TransactionType.WITHDRAWAL)));

        TransactionResponse response = processor.process(command(
                "TXN-2", TransactionType.WITHDRAWAL, "40.00", "ACC-1", null)).response();

        assertEquals(new BigDecimal("60.00"), response.sourceBalance());
        assertEquals(TransactionStatus.SUCCESS, response.status());
    }

    @Test
    void withdrawalWithInsufficientBalanceIsRejectedWithoutUpdatingBalance() {
        when(accountRepository.findByIdForUpdate("ACC-1"))
                .thenReturn(Optional.of(account("ACC-1", "10.00", "ACTIVE")));

        ProcessingResult result = processor.process(command(
                "TXN-3", TransactionType.WITHDRAWAL, "40.00", "ACC-1", null));

        assertNotNull(result.rejection());
        assertEquals(InsufficientBalanceException.class, result.rejection().getClass());
        verify(accountRepository, never()).updateBalance(any(), any());
        verify(failedTransactionRepository).save(any());
    }

    @Test
    void transferDebitsAndCreditsBothAccounts() {
        when(accountRepository.findByIdForUpdate("ACC-1"))
                .thenReturn(Optional.of(account("ACC-1", "200.00", "ACTIVE")));
        when(accountRepository.findByIdForUpdate("ACC-2"))
                .thenReturn(Optional.of(account("ACC-2", "50.00", "ACTIVE")));
        when(accountRepository.updateBalance("ACC-1", new BigDecimal("125.00"))).thenReturn(true);
        when(accountRepository.updateBalance("ACC-2", new BigDecimal("125.00"))).thenReturn(true);
        when(transactionRepository.findById("TXN-4"))
                .thenReturn(Optional.of(transaction("TXN-4", TransactionType.TRANSFER)));

        TransactionResponse response = processor.process(command(
                "TXN-4", TransactionType.TRANSFER, "75.00", "ACC-1", "ACC-2")).response();

        assertEquals(new BigDecimal("125.00"), response.sourceBalance());
        assertEquals(new BigDecimal("125.00"), response.destinationBalance());
        assertEquals(TransactionStatus.SUCCESS, response.status());
    }

    @Test
    void transferLocksAccountsInSortedIdOrder() {
        when(accountRepository.findByIdForUpdate("ACC-1"))
                .thenReturn(Optional.of(account("ACC-1", "50.00", "ACTIVE")));
        when(accountRepository.findByIdForUpdate("ACC-2"))
                .thenReturn(Optional.of(account("ACC-2", "200.00", "ACTIVE")));
        when(accountRepository.updateBalance("ACC-2", new BigDecimal("125.00"))).thenReturn(true);
        when(accountRepository.updateBalance("ACC-1", new BigDecimal("125.00"))).thenReturn(true);
        when(transactionRepository.findById("TXN-13"))
                .thenReturn(Optional.of(transaction("TXN-13", TransactionType.TRANSFER)));

        processor.process(command(
                "TXN-13", TransactionType.TRANSFER, "75.00", "ACC-2", "ACC-1"));

        org.mockito.InOrder lockOrder = inOrder(accountRepository);
        lockOrder.verify(accountRepository).findByIdForUpdate("ACC-1");
        lockOrder.verify(accountRepository).findByIdForUpdate("ACC-2");
    }

    @Test
    void transferToUnknownAccountFailsBeforeCreatingTransaction() {
        when(accountRepository.findByIdForUpdate("ACC-1"))
                .thenReturn(Optional.of(account("ACC-1", "200.00", "ACTIVE")));
        when(accountRepository.findByIdForUpdate("ACC-2")).thenReturn(Optional.empty());

        assertThrows(AccountNotFoundException.class, () -> processor.process(command(
                "TXN-5", TransactionType.TRANSFER, "10.00", "ACC-1", "ACC-2")));
        verify(transactionRepository, never()).save(any());
        verify(accountRepository, never()).updateBalance(any(), any());
    }

    @Test
    void transferToSameAccountIsRejected() {
        assertThrows(InvalidTransactionException.class, () -> processor.process(command(
                "TXN-6", TransactionType.TRANSFER, "10.00", "ACC-1", "ACC-1")));
        verify(accountRepository, never()).findByIdForUpdate(any());
        verify(transactionRepository, never()).save(any());
    }

    @Test
    void nonPositiveOrOverPrecisionAmountsAreRejectedBeforeDatabaseAccess() {
        assertThrows(InvalidTransactionException.class, () -> processor.process(command(
                "TXN-10", TransactionType.DEPOSIT, "0.00", null, "ACC-1")));
        assertThrows(InvalidTransactionException.class, () -> processor.process(command(
                "TXN-11", TransactionType.DEPOSIT, "-1.00", null, "ACC-1")));
        assertThrows(InvalidTransactionException.class, () -> processor.process(command(
                "TXN-12", TransactionType.DEPOSIT, "1.001", null, "ACC-1")));

        verify(accountRepository, never()).findByIdForUpdate(any());
        verify(transactionRepository, never()).save(any());
    }

    @Test
    void duplicateTransactionIdIsNotProcessed() {
        when(transactionRepository.existsById("TXN-7")).thenReturn(true);

        assertThrows(DuplicateTransactionException.class, () -> processor.process(command(
                "TXN-7", TransactionType.DEPOSIT, "10.00", null, "ACC-1")));
        verify(accountRepository, never()).findByIdForUpdate(any());
        verify(transactionRepository, never()).save(any());
    }

    @Test
    void inactiveAccountIsRecordedAsRejectedWithoutChangingBalance() {
        when(accountRepository.findByIdForUpdate("ACC-1"))
                .thenReturn(Optional.of(account("ACC-1", "100.00", "INACTIVE")));

        ProcessingResult result = processor.process(command(
                "TXN-8", TransactionType.DEPOSIT, "10.00", null, "ACC-1"));

        assertNotNull(result.rejection());
        assertEquals(InactiveAccountException.class, result.rejection().getClass());
        verify(accountRepository, never()).updateBalance(any(), any());
        verify(transactionRepository).updateStatus("TXN-8", TransactionStatus.REJECTED);
        verify(failedTransactionRepository).save(any());
    }

    @Test
    void transferFailureTriggersSpringTransactionRollback() {
        when(accountRepository.findByIdForUpdate("ACC-1"))
                .thenReturn(Optional.of(account("ACC-1", "200.00", "ACTIVE")));
        when(accountRepository.findByIdForUpdate("ACC-2"))
                .thenReturn(Optional.of(account("ACC-2", "50.00", "ACTIVE")));
        when(accountRepository.updateBalance("ACC-1", new BigDecimal("125.00"))).thenReturn(true);
        when(accountRepository.updateBalance("ACC-2", new BigDecimal("125.00"))).thenReturn(false);

        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        org.springframework.transaction.TransactionStatus transactionStatus =
                mock(org.springframework.transaction.TransactionStatus.class);
        when(transactionManager.getTransaction(any(TransactionDefinition.class)))
                .thenReturn(transactionStatus);
        TransactionInterceptor interceptor = new TransactionInterceptor(
                transactionManager,
                new AnnotationTransactionAttributeSource());
        ProxyFactory proxyFactory = new ProxyFactory(processor);
        proxyFactory.addAdvice(interceptor);
        TransactionProcessor transactionalProcessor =
                (TransactionProcessor) proxyFactory.getProxy();

        assertThrows(IllegalStateException.class, () -> transactionalProcessor.process(command(
                "TXN-9", TransactionType.TRANSFER, "75.00", "ACC-1", "ACC-2")));

        verify(transactionManager).rollback(transactionStatus);
        verify(transactionManager, never()).commit(transactionStatus);
        verify(transactionRepository, never()).updateStatus("TXN-9", TransactionStatus.SUCCESS);
    }

    private Account account(String accountId, String balance, String status) {
        return new Account(
                accountId,
                "CUST-1",
                "SAVINGS",
                new BigDecimal(balance),
                status,
                LocalDateTime.now());
    }

    private Transaction transaction(String transactionId, TransactionType type) {
        return new Transaction(
                transactionId,
                type,
                new BigDecimal("1.00"),
                null,
                null,
                TransactionStatus.SUCCESS,
                null,
                LocalDateTime.now());
    }

    private TransactionCommand command(
            String transactionId,
            TransactionType type,
            String amount,
            String sourceAccountId,
            String destinationAccountId) {
        return new TransactionCommand(
                transactionId,
                type,
                new BigDecimal(amount),
                sourceAccountId,
                destinationAccountId,
                "Test transaction");
    }
}
