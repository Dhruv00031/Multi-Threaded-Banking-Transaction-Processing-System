INSERT INTO customers (customer_id, full_name, email, phone)
VALUES
    ('CUST-1001', 'Aarav Sharma', 'aarav.sharma@example.test', '9000001001'),
    ('CUST-1002', 'Diya Patel', 'diya.patel@example.test', '9000001002'),
    ('CUST-1003', 'Kabir Singh', 'kabir.singh@example.test', '9000001003');

INSERT INTO accounts (account_id, customer_id, account_type, balance, status)
VALUES
    ('ACC-1001', 'CUST-1001', 'SAVINGS', 10000.00, 'ACTIVE'),
    ('ACC-1002', 'CUST-1002', 'SAVINGS', 7500.00, 'ACTIVE'),
    ('ACC-1003', 'CUST-1003', 'CURRENT', 25000.00, 'ACTIVE');
