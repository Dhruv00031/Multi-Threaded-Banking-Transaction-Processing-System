CREATE INDEX idx_accounts_customer
    ON accounts (customer_id);

CREATE INDEX idx_transactions_created_at
    ON transactions (created_at);

CREATE INDEX idx_transactions_type_status_created
    ON transactions (transaction_type, status, created_at);

CREATE INDEX idx_transactions_source_created
    ON transactions (source_account_id, created_at);

CREATE INDEX idx_transactions_destination_created
    ON transactions (destination_account_id, created_at);

CREATE INDEX idx_transaction_audit_transaction_time
    ON transaction_audit (transaction_id, event_timestamp);

CREATE INDEX idx_failed_transactions_transaction
    ON failed_transactions (transaction_id);
