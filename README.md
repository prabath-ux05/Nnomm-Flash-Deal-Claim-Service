# Nnomm Flash-Deal Claim Service

A production-grade, high-concurrency flash deal claim and payment reconciliation service built with **Kotlin**, **Spring Boot 3**, **PostgreSQL 16**, and **Flyway**.

The service solves flash-sale engineering challenges:
- **Zero Overselling**: Guaranteed strict stock safety under heavy concurrent access using database row locks (`SELECT ... FOR UPDATE`).
- **Idempotency with Crash Recovery**: Transparent request replay for duplicate keys, rejection of concurrent requests in flight, and lease-based deadline recovery for interrupted executions.
- **HMAC-SHA256 Webhook Verification**: Cryptographically authenticated payment webhooks with deduplication and state verification.
- **Distributed Expiry & Inventory Recovery**: Periodic scheduled batch expiry of overdue reservations using `FOR UPDATE SKIP LOCKED` across multi-instance deployments without duplicate processing or deadlocks.
- **Customer Isolation & JWT Security**: Stateless HMAC-SHA256 JWT tokens ensuring customers can only access their own claims.
- **Integer Paise Arithmetic**: All financial amounts are stored and calculated strictly in integer paise (1 INR = 100 paise), avoiding floating-point rounding errors.

---

## 1. Architecture & Technology Stack

| Layer | Component | Version / Details |
|---|---|---|
| Language | Kotlin | 1.9.25 |
| Framework | Spring Boot | 3.3.4 (Web, Data JPA, Security, Actuator, Validation) |
| Database | PostgreSQL | 16.x (ACID transactions, Row-level pessimistic locking) |
| Migrations | Flyway | Versioned DDL migrations in `classpath:db/migration` |
| Security | Spring Security + Nimbus/HMAC | HMAC-SHA256 signed JWT Bearer tokens |
| Build Tool | Gradle Wrapper | 8.x (Kotlin DSL) |
| Testing | JUnit 5 + Zonky Embedded Postgres | 39 integration tests against real PostgreSQL 16 |
| Containerization | Docker & Docker Compose | Multi-stage Dockerfile + Two-instance cluster configuration |

---

## 2. API Reference

All error responses follow the standard format:
```json
{
  "code": "ERROR_CODE",
  "message": "Human readable explanation",
  "timestamp": "2026-10-19T04:30:00Z"
}
```

### Health Check
- **`GET /health`**
  - **Auth**: None
  - **Response `200 OK`**:
    ```json
    { "status": "UP" }
    ```

---

### Deal Endpoints

#### 1. List Active Deals
- **`GET /v1/deals`**
  - **Auth**: None
  - **Response `200 OK`**:
    ```json
    [
      {
        "id": "11111111-1111-1111-1111-111111111111",
        "title": "Wireless Noise-Canceling Headphones",
        "description": "Premium wireless headphones with active noise cancellation",
        "pricePaise": 299900,
        "stockTotal": 50,
        "stockAvailable": 45,
        "startsAt": "2026-10-19T00:00:00Z",
        "endsAt": "2026-10-20T00:00:00Z",
        "createdAt": "2026-10-19T00:00:00Z",
        "updatedAt": "2026-10-19T00:00:00Z"
      }
    ]
    ```

#### 2. Get Deal by ID
- **`GET /v1/deals/{dealId}`**
  - **Auth**: None
  - **Response `200 OK`**: Same JSON structure as deal item above.
  - **Errors**: `404 Not Found` (`DEAL_NOT_FOUND`).

---

### Claim Endpoints

#### 3. Claim Flash Deal
- **`POST /v1/deals/{dealId}/claims`**
  - **Headers**:
    - `Authorization: Bearer <jwt_token>` (Required)
    - `Idempotency-Key: <unique_string>` (Required, 1-255 characters matching `^[a-zA-Z0-9_.-]+$`)
  - **Body**:
    ```json
    {
      "quantity": 2
    }
    ```
    *(Note: `quantity` must be an integer between 1 and 3 inclusive)*
  - **Response `201 Created`**:
    ```json
    {
      "id": "c1111111-1111-1111-1111-111111111111",
      "dealId": "11111111-1111-1111-1111-111111111111",
      "customerId": "cust_12345",
      "quantity": 2,
      "amountPaise": 599800,
      "status": "RESERVED",
      "expiresAt": "2026-10-19T04:40:00Z",
      "createdAt": "2026-10-19T04:30:00Z",
      "updatedAt": "2026-10-19T04:30:00Z"
    }
    ```
  - **Errors**:
    - `400 Bad Request`: `INVALID_QUANTITY`, `INVALID_IDEMPOTENCY_KEY`, `DEAL_NOT_LIVE`, `MALFORMED_JSON`
    - `401 Unauthorized`: `UNAUTHORIZED` (missing or invalid JWT)
    - `404 Not Found`: `DEAL_NOT_FOUND`
    - `409 Conflict`: `SOLD_OUT`, `ACTIVE_CLAIM_EXISTS`, `IDEMPOTENCY_KEY_REUSED`, `REQUEST_IN_PROGRESS`

#### 4. Get Claim by ID
- **`GET /v1/claims/{claimId}`**
  - **Headers**: `Authorization: Bearer <jwt_token>` (Required)
  - **Response `200 OK`**: Single `ClaimResponse` object.
  - **Errors**:
    - `401 Unauthorized`: Token missing or invalid.
    - `403 Forbidden`: Token customer ID does not match claim owner (`FORBIDDEN`).
    - `404 Not Found`: Claim ID does not exist (`CLAIM_NOT_FOUND`).

#### 5. List Customer Claims
- **`GET /v1/claims`**
  - **Headers**: `Authorization: Bearer <jwt_token>` (Required)
  - **Response `200 OK`**: Array of `ClaimResponse` objects belonging to the authenticated customer.

---

### Payment Webhook Endpoint

#### 6. Process Payment Webhook
- **`POST /v1/payments/webhook`**
  - **Headers**:
    - `X-Signature: <hmac_sha256_hex>` (Required: HMAC-SHA256 of raw request body using `WEBHOOK_SECRET`)
  - **Body**:
    ```json
    {
      "event_id": "evt_99887766",
      "claim_id": "c1111111-1111-1111-1111-111111111111",
      "amount_paise": 599800,
      "status": "SUCCESS"
    }
    ```
    *(Supports both `snake_case` and `camelCase` keys)*
  - **Response `200 OK`**:
    ```json
    {
      "eventId": "evt_99887766",
      "claimId": "c1111111-1111-1111-1111-111111111111",
      "status": "PROCESSED",
      "message": "Payment processed successfully"
    }
    ```
  - **Behavior**:
    - **Valid `SUCCESS` + Matching Amount**: Transitions claim `RESERVED` -> `PAID`.
    - **Amount Mismatch**: Transitions claim to `NEEDS_REVIEW` (flags for manual finance intervention; stock remains reserved).
    - **Duplicate Event ID**: Idempotently returns `200 OK` (`ALREADY_PROCESSED`) without double-processing.
    - **Invalid Signature**: Returns `401 Unauthorized` (`INVALID_SIGNATURE`).
    - **Expired Claim**: Returns `400 Bad Request` (`INVALID_STATE_TRANSITION`).

---

## 3. Configuration & Environment Variables

| Variable | Default Value | Description |
|---|---|---|
| `PORT` | `8080` | HTTP service port |
| `DATABASE_URL` | `jdbc:postgresql://localhost:5432/flashdeals` | PostgreSQL JDBC connection URL |
| `DATABASE_USER` | `postgres` | Database username |
| `DATABASE_PASSWORD` | `postgres` | Database password |
| `JWT_SECRET` | `c2VjdXJlX2p3dF...` | Secret key for JWT signature verification |
| `WEBHOOK_SECRET` | `whsec_908f2a...` | HMAC-SHA256 secret for payment webhooks |
| `CLAIM_TTL_SECONDS` | `600` | Claim reservation duration (default: 10 minutes) |
| `EXPIRY_POLL_INTERVAL_MS` | `1000` | Expiry processor polling frequency (ms) |
| `EXPIRY_BATCH_SIZE` | `100` | Maximum claims processed per expiry batch |
| `EXPIRY_SCHEDULER_ENABLED`| `true` | Enable/disable background expiry scheduler |

---

## 4. Running Locally

### Prerequisites
- JDK 17 or higher
- PostgreSQL 16 (or Docker to run PostgreSQL)

### Step 1: Start PostgreSQL
```bash
docker run -d --name flashdeal-db \
  -e POSTGRES_DB=flashdeals \
  -e POSTGRES_USER=postgres \
  -e POSTGRES_PASSWORD=postgres \
  -p 5432:5432 \
  postgres:16-alpine
```

### Step 2: Build & Run Service
```bash
# On Linux / macOS:
./gradlew bootRun

# On Windows PowerShell:
.\gradlew bootRun
```

Flyway will automatically execute migrations and seed sample deals upon startup.

---

## 5. Running with Docker Compose (Two-Instance Cluster)

The included `docker-compose.yml` launches a distributed two-instance setup:
- **`postgres`**: PostgreSQL 16 on port `5432` with persistent volume `postgres_data` and healthcheck.
- **`app-1`**: First service instance on port `8080`.
- **`app-2`**: Second service instance on port `8081`.

Both instances point to the exact same database and run active expiry schedulers concurrently, demonstrating multi-instance race safety.

```bash
# Start the full stack in background
docker compose up --build -d

# Check container health and status
docker compose ps

# View logs from both instances
docker compose logs -f
```

### Verify Endpoints on Both Instances
```bash
# Instance 1:
curl -i http://localhost:8080/health
curl -i http://localhost:8080/v1/deals

# Instance 2:
curl -i http://localhost:8081/health
curl -i http://localhost:8081/v1/deals
```

---

## 6. Verification & Automated Test Suite

The test suite runs against a real embedded PostgreSQL 16 engine (via Zonky Embedded Postgres) with Flyway migrations applied:

```bash
# Run all unit, integration, and concurrency tests
./gradlew test --info
```

### Test Suite Summary: 41 Tests / 0 Failures
1. **`AdversarialAndEdgeCaseIntegrationTest` (5 tests)**:
   - Concurrent stock claim exhaustion race under thread contention.
   - Idempotency key reuse across different deals rejected with `409 IDEMPOTENCY_KEY_REUSED`.
   - Concurrent duplicate idempotency requests return `409 REQUEST_IN_PROGRESS`.
   - Webhook timestamp / event deduplication.
   - Expired claim cannot be transitioned to PAID via late payment.
2. **`ClaimExpiryIntegrationTest` (7 tests)**:
   - Overdue RESERVED claim transitions to EXPIRED and restores inventory atomically.
   - Non-expired claims remain untouched.
   - PAID claims are never expired.
   - Multiple expired claims aggregate and restore stock accurately.
   - Multi-instance race condition (`FOR UPDATE SKIP LOCKED` prevents double-expiration).
   - Concurrent payment vs. expiry race produces exactly one consistent outcome.
   - Restart recovery: Overdue claims created prior to restart are swept immediately.
3. **`ClaimIntegrationTest` (8 tests)**:
   - Successful reservation decreases stock and calculates integer paise.
   - Idempotent replay on identical request.
   - Quantity bounds validation (1 to 3).
   - One active claim per customer per deal enforcement.
   - Deal availability time window (`starts_at` / `ends_at`) validation.
   - Sold-out rejection when quantity exceeds `stock_available`.
   - Invalid and blank idempotency key syntax rejection.
4. **`DealAndClaimApiIntegrationTest` (9 tests)**:
   - Deal lookup returns inventory, pricing, timing, and metadata.
   - Unknown deal returns 404.
   - Authenticated claim lookup returns claim state and amount.
   - Cross-customer claim access blocked with 403 Forbidden.
   - Unauthenticated claim access blocked with 401 Unauthorized.
   - Unknown claim returns 404.
   - PAID claim lookup.
   - EXPIRED claim lookup.
   - Authenticated customer claim listing (`GET /v1/claims`).
5. **`PaymentIntegrationTest` (10 tests)**:
   - Valid HMAC signature and amount transitions claim to PAID.
   - Invalid or missing signature returns 401 Unauthorized.
   - Unknown claim returns 404 Not Found.
   - Amount mismatch transitions claim to NEEDS_REVIEW for manual reconciliation.
   - Duplicate webhook delivery safely acknowledged without duplicate processing.
   - Concurrent duplicate webhook deliveries across instances handled safely.
   - Webhook for already PAID claim rejects invalid transition.
   - Webhook for EXPIRED claim rejects invalid transition.
   - Webhook with status REFUNDED transitions PAID claim to REFUND_PENDING.
   - Webhook with status FAILED records event and does not falsely mark claim as PAID.
6. **`FlashDealApplicationTests` (1 test)**: Context boot test.
7. **`HealthControllerTest` (1 test)**: `/health` returns `200 UP`.
