# Banking Transaction Processing System

A Java 17 and Spring Boot banking backend using Spring JDBC, `JdbcTemplate`,
and MySQL. Deposit, withdrawal, and transfer requests are processed by a
bounded multithreaded executor. The frontend is outside this phase.

## Database setup

Use MySQL 8.0.16 or later. Create the database, then execute the scripts from
the repository root in order:

```sql
CREATE DATABASE banking_db;
```

1. `database/schema.sql`
2. `database/sample-data.sql`
3. `database/indexes.sql`

The sample data uses reserved `.test` email addresses and is for local
development only. Scripts are not run automatically by the application.

Configure these environment variables before starting the backend:

| Variable | Default |
| --- | --- |
| `DB_URL` | `jdbc:mysql://localhost:3306/banking_db` |
| `DB_USERNAME` | `root` |
| `DB_PASSWORD` | No default; required |
| `TRANSACTION_WORKER_COUNT` | `5` |
| `TRANSACTION_QUEUE_CAPACITY` | `100` |
| `TRANSACTION_SHUTDOWN_TIMEOUT_SECONDS` | `30` |

For example, set `DB_PASSWORD` in the terminal or IDE run configuration. Do not
put a real password in source control. `DB_URL` can point to a dedicated
database if preferred.

## Start the backend

From the repository root in PowerShell:

```powershell
Set-Location .\backend
mvn spring-boot:run
```

If Maven is not on `PATH`, use its absolute executable path:

```powershell
& 'D:\apache-maven-3.10.2-bin\apache-maven-3.10.2\bin\mvn.cmd' spring-boot:run
```

The API listens on `http://localhost:8080`. The same Maven commands can run
tests from the `backend` directory. Executor environment variables may be set
in the terminal or IDE run configuration; worker count, queue capacity, and
shutdown timeout must be positive integers.

## REST API

All responses use JSON. Replace path IDs with actual IDs. The sample database
includes `ACC-1001`, `ACC-1002`, `ACC-1003`, and customers `CUST-1001` through
`CUST-1003`.

| Method | URL | Request body | Success |
| --- | --- | --- | --- |
| `GET` | `/api/accounts/{accountId}` | None | `200` with account |
| `GET` | `/api/customers/{customerId}` | None | `200` with customer |
| `POST` | `/api/transactions/deposit` | Deposit JSON below | `201` with transaction result |
| `POST` | `/api/transactions/withdraw` | Withdrawal JSON below | `201` with transaction result |
| `POST` | `/api/transactions/transfer` | Transfer JSON below | `201` with transaction result |
| `GET` | `/api/transactions/{transactionId}` | None | `200` with stored transaction |
| `GET` | `/api/transactions/executor/stats` | None | `200` with thread pool statistics |

### Transaction requests

Deposit (`POST /api/transactions/deposit`):

```json
{
  "transactionId": "TXN-10001",
  "accountId": "ACC-1001",
  "amount": 125.50,
  "description": "Cash deposit"
}
```

Withdrawal (`POST /api/transactions/withdraw`):

```json
{
  "transactionId": "TXN-10002",
  "accountId": "ACC-1001",
  "amount": 25.00,
  "description": "ATM withdrawal"
}
```

Transfer (`POST /api/transactions/transfer`):

```json
{
  "transactionId": "TXN-10003",
  "sourceAccountId": "ACC-1001",
  "destinationAccountId": "ACC-1002",
  "amount": 100.00,
  "description": "Rent"
}
```

`description` is optional. Transaction IDs are limited to 64 characters and
may be used once. Account IDs are limited to 36 characters. Amounts must be
positive and fit `DECIMAL(19,2)`.

Example successful transfer response (`201 Created`; balances are illustrative):

```json
{
  "transactionId": "TXN-10003",
  "transactionType": "TRANSFER",
  "status": "SUCCESS",
  "amount": 100.00,
  "sourceAccountId": "ACC-1001",
  "destinationAccountId": "ACC-1002",
  "sourceBalance": 9900.00,
  "destinationBalance": 7600.00,
  "createdAt": "2026-01-01T12:00:00"
}
```

Deposits populate `destinationBalance`; withdrawals populate `sourceBalance`.
Fields that do not apply are `null`. Transaction lookup returns the stored
fields `transactionId`, `transactionType`, `amount`, `sourceAccountId`,
`destinationAccountId`, `status`, `description`, and `createdAt`.

Account lookup returns `accountId`, `customerId`, `accountType`, `balance`,
`status`, and `createdAt`. Customer lookup returns `customerId`, `fullName`,
`email`, `phone`, and `createdAt`.

### HTTP errors

Errors use this consistent shape:

```json
{
  "timestamp": "2026-01-01T12:00:00",
  "status": 404,
  "error": "Not Found",
  "message": "The requested resource was not found",
  "path": "/api/unknown"
}
```

| Status | Meaning |
| --- | --- |
| `400 Bad Request` | Missing/malformed body, invalid fields or amount, or same-account transfer |
| `404 Not Found` | Unknown endpoint, account, customer, or transaction |
| `409 Conflict` | Duplicate transaction ID, insufficient balance, or inactive account |
| `503 Service Unavailable` | Executor is shut down, full, or the waiting request was interrupted |
| `500 Internal Server Error` | Unexpected backend failure; response does not include internal exception details |

For a rejected transaction such as insufficient funds or an inactive account,
the transaction is stored as `REJECTED` with a failure record and audit event.
Validation failures and invalid same-account transfers are rejected before
processing and do not change balances.

If a caller is interrupted while waiting for an accepted transaction, the
worker is not cancelled. The transaction may still complete, so the `503`
response identifies the outcome as unknown and advises checking the transaction
ID before retrying. Do not retry with a new transaction ID until the original
ID's stored status is checked.

### Multithreaded processing

The synchronous REST endpoints submit one `Callable` per request to a
Spring-managed `ThreadPoolExecutor` and wait for its `Future`. The HTTP request
thread does not open a database transaction or hold account locks while it
waits. The callable invokes the injected Spring `TransactionProcessor` bean on
a worker, so its `@Transactional` boundary starts on that worker. JDBC
connections and transaction contexts are acquired there and are never passed
between threads.

```text
HTTP request
    |
    v
TransactionController -> TransactionService -> bounded executor queue
                                                |
                         +----------------------+----------------------+
                         v                      v                      v
                  banking-worker-1       banking-worker-2       banking-worker-N
                         |                      |                      |
                         +----------------------+----------------------+
                                                |
                                                v
                                  Spring TransactionProcessor
                           @Transactional + account row locks
                                                |
                           TransactionFailureRecorder (REQUIRES_NEW)
                                                |
                                                v
                                      MySQL through JdbcTemplate
                                                |
                         Future result/exception -> synchronous HTTP response
```

By default, the pool has five fixed workers and an `ArrayBlockingQueue` with
capacity 100. Override these using `TRANSACTION_WORKER_COUNT`,
`TRANSACTION_QUEUE_CAPACITY`, and `TRANSACTION_SHUTDOWN_TIMEOUT_SECONDS`.
Workers are named `banking-worker-1`, `banking-worker-2`, and so on. When all
workers and queue slots are occupied, the explicit `AbortPolicy` rejects
additional work; the API responds with `503`, and no transaction is retried or
silently discarded. Database row locking with `SELECT ... FOR UPDATE`, sorted
account lock ordering, duplicate transaction IDs, and atomic transfers remain
the responsibility of the existing transactional processor.

The `GET /api/transactions/executor/stats` endpoint exposes executor activity
only; it does not return account or customer data. Example response:

```json
{
  "configuredWorkerCount": 5,
  "currentPoolSize": 5,
  "activeWorkerCount": 2,
  "queueSize": 3,
  "queueCapacity": 100,
  "submittedTaskCount": 48,
  "completedTaskCount": 45,
  "rejectedTaskCount": 0
}
```

Submitted/completed/rejected values count executor tasks, not successful
banking transactions. Completed tasks include tasks that ended with an
exception; accepted queued tasks cancelled during a forced shutdown remain
submitted but are not counted as completed. The bounded in-memory queue is not
durable and is lost on an application crash. On normal shutdown Spring stops
new submissions and allows
accepted tasks to finish for the configured interval; if that interval expires
or shutdown is interrupted, queued tasks that did not start are cancelled and
the condition is logged. Running worker tasks are interrupted on forced
shutdown; their database operation outcome depends on where processing was
interrupted.

## Testing

Run the unit, MVC, and executor tests from `backend`:

```powershell
mvn test
```

If Maven is not on `PATH`, replace `mvn` with the absolute `mvn.cmd` path shown
above. Tests cover transaction calculations, invalid amounts, lock and rejection
outcomes, Spring transaction rollback invocation, named worker execution,
bounded queue rejection, executor metrics, exception propagation, graceful
shutdown, parallel independent tasks, MVC validation, and API error handling.
The multithreaded unit tests use latches for deterministic task coordination.

### Optional real-MySQL rollback integration test

`MySqlTransactionRollbackIT` uses the configured MySQL database and should be
run against a local or dedicated test database with the schema already
installed. It inserts UUID-based customer/account rows, deliberately throws
after the debit update and before the credit update, checks both original
balances and the separate failed/audit records, then removes only the rows it
created.

In PowerShell, from `backend`, configure the database environment variables
without putting credentials in source files, opt in, and run the test:

```powershell
$env:DB_URL = 'jdbc:mysql://localhost:3306/banking_db'
$env:DB_USERNAME = 'root'
$securePassword = Read-Host 'MySQL password' -AsSecureString
$env:DB_PASSWORD = (New-Object System.Net.NetworkCredential('', $securePassword)).Password
$env:DB_INTEGRATION_TESTS = 'true'
mvn -Dtest=MySqlTransactionRollbackIT test
```

Use the absolute Maven executable instead of `mvn` if it is not on `PATH`.
Without `DB_INTEGRATION_TESTS=true`, the integration test is skipped. Do not
point it at a database containing conflicting generated `IT*` IDs; its
cleanup is restricted to the unique IDs created in that test invocation.

## Current scope and limitations

- The application uses Spring JDBC and MySQL; it does not use JPA/Hibernate.
- Transaction updates use Spring `@Transactional`, InnoDB row locks via
  `SELECT ... FOR UPDATE`, and deterministic account-ID lock ordering.
- The integration test verifies rollback against real MySQL; mocked tests
  alone do not prove database rollback.
- Executor tasks and their queue are in-memory; crashes can lose accepted
  queued work, so this is not a durable job-processing system.
- Full-scale concurrent MySQL tests, stress testing, and performance
  benchmarking are deferred to Phase 5.
- Frontend functionality, authentication/authorization, and production
  deployment configuration are not implemented in this phase.
