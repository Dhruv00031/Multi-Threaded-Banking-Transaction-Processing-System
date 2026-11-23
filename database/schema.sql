CREATE TABLE IF NOT EXISTS customers (
    customer_id VARCHAR(36) NOT NULL,
    full_name VARCHAR(120) NOT NULL,
    email VARCHAR(254) NOT NULL,
    phone VARCHAR(30),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (customer_id),
    UNIQUE KEY uk_customers_email (email)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS accounts (
    account_id VARCHAR(36) NOT NULL,
    customer_id VARCHAR(36) NOT NULL,
    account_type VARCHAR(30) NOT NULL DEFAULT 'SAVINGS',
    balance DECIMAL(19, 2) NOT NULL DEFAULT 0.00,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (account_id),
    CONSTRAINT chk_accounts_balance_nonnegative CHECK (balance >= 0),
    CONSTRAINT chk_accounts_status CHECK (status IN ('ACTIVE', 'INACTIVE', 'FROZEN')),
    CONSTRAINT fk_accounts_customer
        FOREIGN KEY (customer_id) REFERENCES customers (customer_id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS transactions (
    transaction_id VARCHAR(64) NOT NULL,
    transaction_type VARCHAR(20) NOT NULL,
    amount DECIMAL(19, 2) NOT NULL,
    source_account_id VARCHAR(36),
    destination_account_id VARCHAR(36),
    status VARCHAR(20) NOT NULL,
    description VARCHAR(255),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (transaction_id),
    CONSTRAINT chk_transactions_amount_positive CHECK (amount > 0),
    CONSTRAINT chk_transactions_type
        CHECK (transaction_type IN ('DEPOSIT', 'WITHDRAWAL', 'TRANSFER')),
    CONSTRAINT chk_transactions_status
        CHECK (status IN ('PENDING', 'SUCCESS', 'FAILED', 'REJECTED')),
    CONSTRAINT chk_transactions_different_accounts
        CHECK (
            source_account_id IS NULL
            OR destination_account_id IS NULL
            OR source_account_id <> destination_account_id
        ),
    CONSTRAINT fk_transactions_source_account
        FOREIGN KEY (source_account_id) REFERENCES accounts (account_id),
    CONSTRAINT fk_transactions_destination_account
        FOREIGN KEY (destination_account_id) REFERENCES accounts (account_id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS transaction_audit (
    audit_id BIGINT NOT NULL AUTO_INCREMENT,
    transaction_id VARCHAR(64) NOT NULL,
    event_type VARCHAR(40) NOT NULL,
    event_timestamp TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    thread_name VARCHAR(100),
    duration_ms BIGINT,
    details VARCHAR(500),
    PRIMARY KEY (audit_id),
    CONSTRAINT fk_audit_transaction
        FOREIGN KEY (transaction_id) REFERENCES transactions (transaction_id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS failed_transactions (
    failure_id BIGINT NOT NULL AUTO_INCREMENT,
    transaction_id VARCHAR(64) NOT NULL,
    error_category VARCHAR(100) NOT NULL,
    failure_reason VARCHAR(1000) NOT NULL,
    failed_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (failure_id),
    CONSTRAINT fk_failed_transaction
        FOREIGN KEY (transaction_id) REFERENCES transactions (transaction_id)
) ENGINE=InnoDB;
