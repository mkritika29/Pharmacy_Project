# Crescent Pharmacy Network – Payment Idempotency Service

A production-grade middleware service that prevents duplicate payment charges for Crescent Pharmacy Network's 240 locations across Egypt, Morocco, and Tunisia.

---

## The Problem

Crescent Pharmacy was losing **$28,000/month** in duplicate charges because:
- Poor mobile network conditions caused payment requests to be retried automatically
- POS systems had no deduplication logic — every button press fired a new charge
- Customers were being billed 2–3× for the same prescription pickup

---

## Solution: Idempotency Middleware

This service sits between the pharmacy POS systems and the Yuno payment processor. It guarantees that **no matter how many times the same payment request is submitted**, the customer is charged exactly once.

---

## Architecture

```
┌──────────────────────────────────────────────────────────────────────────┐
│                Crescent Pharmacy POS Systems (240 locations)              │
│           POST /api/v1/payments  +  X-Idempotency-Key: <uuid>            │
└──────────────────────────────────┬───────────────────────────────────────┘
                                   │
                                   ▼
┌──────────────────────────────────────────────────────────────────────────┐
│                   Payment Idempotency Service (Spring Boot)               │
│                                                                           │
│  ┌───────────────┐    ┌──────────────────────────┐    ┌───────────────┐  │
│  │ PaymentCont-  │───▶│  PaymentIdempotency      │───▶│  Mock Payment │  │
│  │ roller        │    │  Service (orchestrator)  │    │  Processor    │  │
│  └───────────────┘    └────────────┬─────────────┘    └───────────────┘  │
│                                    │                                      │
│                          ┌─────────┴──────────┐                          │
│                          │                    │                           │
│                   ┌──────▼──────┐    ┌────────▼────────┐                 │
│                   │    Redis    │    │   PostgreSQL    │                  │
│                   │  (Cache –   │    │  (Durable Store │                  │
│                   │  fast path) │    │   + ACID lock)  │                  │
│                   └─────────────┘    └─────────────────┘                 │
└──────────────────────────────────────────────────────────────────────────┘
```

### How Idempotency is Enforced

1. **Redis fast-path** — completed results are cached. Duplicate requests served from Redis in < 1 ms without touching the database.

2. **Database unique constraint** on `(idempotency_key, merchant_id)` — the atomic guard that prevents concurrent duplicates from creating two charges.

3. **Optimistic insertion** — the service tries to `INSERT` a `PENDING` record. If a `DataIntegrityViolationException` is thrown (duplicate key), it reads the existing record instead of crashing. No Redis locks needed.

4. **Polling for PENDING** — if a concurrent request sees a `PENDING` record (because the first request is still being processed), it polls with exponential back-off until the result is available.

### Concurrency Safety – Race Condition Handling

```
Thread A (first request):
  ├── Redis MISS
  ├── INSERT PENDING → SUCCESS ✓ (owns the processing)
  ├── Call payment processor (50–200 ms)
  └── UPDATE → COMPLETED → cache in Redis

Thread B (duplicate, arrives 5 ms later):
  ├── Redis MISS (still processing)
  ├── INSERT PENDING → CONSTRAINT VIOLATION ✗
  ├── Fetch existing PENDING record
  ├── Poll DB with back-off until COMPLETED
  └── Return Thread A's result (duplicate=true)

Thread C (duplicate, arrives 500 ms later):
  ├── Redis HIT → return cached result immediately
  └── (Done in < 1 ms)
```

This ensures **exactly one charge** regardless of how many concurrent requests arrive.

---

## Tech Stack

| Layer | Technology |
|---|---|
| Language | Java 17 |
| Framework | Spring Boot 3.2, Spring Data JPA |
| Cache | Redis 7 (via Lettuce) |
| Database | PostgreSQL 15 |
| Migrations | Flyway |
| Metrics | Micrometer + Prometheus |
| Containerisation | Docker + Docker Compose |
| Build | Maven 3.9 |
| Testing | JUnit 5, Mockito, H2 (in-memory) |

---

## Quick Start

### Prerequisites

- Docker Desktop (for `docker-compose` setup)  
  **OR** Java 17 + Maven 3.9 + PostgreSQL 15 + Redis 7 (for local setup)

### Option A – Docker Compose (Recommended)

```bash
# 1. Clone and enter the project
cd Pharmacy_Project

# 2. Start all services (PostgreSQL + Redis + Spring Boot app)
docker-compose up --build

# 3. Wait for "Started IdempotencyServiceApplication" in the logs
# Service is ready at http://localhost:8080
```

### Option B – Run Locally (without Docker for the app)

```bash
# 1. Start dependencies
docker-compose up -d postgres redis

# 2. Copy and edit environment
cp .env.example .env

# 3. Build and run
./mvnw spring-boot:run
```

### Option C – Build and run the JAR

```bash
./mvnw clean package -DskipTests
java -jar target/idempotency-service-1.0.0-SNAPSHOT.jar
```

---

## API Reference

### Authentication / Headers

| Header | Required | Description |
|---|---|---|
| `X-Idempotency-Key` | ✅ Yes | UUID identifying this payment intent |
| `X-Merchant-Id` | Optional | Pharmacy branch ID (default: `"default"`) |
| `Content-Type` | ✅ Yes | `application/json` |

---

### POST /api/v1/payments – Process a Payment

**First call** (new payment):
```http
POST /api/v1/payments
X-Idempotency-Key: 550e8400-e29b-41d4-a716-446655440000
X-Merchant-Id: PHARMACY_042
Content-Type: application/json

{
  "customerId": "CUST_0042",
  "amount": 45.00,
  "currency": "EGP",
  "paymentMethod": {
    "type": "CARD",
    "cardLastFour": "4242",
    "cardBrand": "VISA"
  },
  "description": "Prescription pickup – Amoxicillin 500mg"
}
```

**Response** `201 Created` (new charge):
```json
{
  "paymentId": "a3f8c2d1-...",
  "idempotencyKey": "550e8400-...",
  "merchantId": "PHARMACY_042",
  "customerId": "CUST_0042",
  "amount": 45.00,
  "currency": "EGP",
  "status": "COMPLETED",
  "paymentReference": "YNO-3F7A2C1B",
  "duplicate": false,
  "createdAt": "2026-06-08T10:00:00Z",
  "completedAt": "2026-06-08T10:00:00.143Z",
  "expiresAt": "2026-06-09T10:00:00Z"
}
```

**Duplicate call** (same `X-Idempotency-Key`):

Response `200 OK`:
```json
{
  "paymentId": "a3f8c2d1-...",
  "status": "COMPLETED",
  "paymentReference": "YNO-3F7A2C1B",
  "duplicate": true,
  "message": "Duplicate request – returning previously processed result. No new charge was made."
}
```

---

### GET /api/v1/payments/{idempotencyKey} – Query Payment Status

```http
GET /api/v1/payments/550e8400-e29b-41d4-a716-446655440000
X-Merchant-Id: PHARMACY_042
```

Response `200 OK` – same structure as above.

---

### GET /api/v1/payments/{idempotencyKey}/audit – Audit Trail

Shows every event recorded for this key: `NEW_PAYMENT`, `DUPLICATE_BLOCKED`, `PAYMENT_COMPLETED`.

```http
GET /api/v1/payments/550e8400-.../audit
X-Merchant-Id: PHARMACY_042
```

---

### GET /api/v1/stats – Deduplication Statistics

```http
GET /api/v1/stats
```

Response:
```json
{
  "reportGeneratedAt": "2026-06-08T10:05:00Z",
  "totalPaymentsProcessed": 87,
  "totalDuplicatesBlocked": 34,
  "duplicateRatePct": 28.1,
  "duplicatesBlockedLastHour": 12,
  "totalFailed": 11,
  "merchantStats": [...],
  "topRetriedKeys": [...]
}
```

---

### GET /api/v1/stats/audit – Recent Audit Log

```http
GET /api/v1/stats/audit?merchantId=PHARMACY_001&hours=2
```

---

### POST /api/v1/test/generate – Generate Test Data

Generates `count` payment requests (80% unique, 20% duplicates, 5 keys retried 3+ times).

```http
POST /api/v1/test/generate?count=100&merchantId=PHARMACY_001
```

---

### POST /api/v1/test/concurrent – Concurrent Load Test

Fires `concurrency` identical requests simultaneously and reports whether idempotency held.

```http
POST /api/v1/test/concurrent?concurrency=20&merchantId=PHARMACY_001
```

Response:
```json
{
  "sharedIdempotencyKey": "abc123...",
  "concurrentRequests": 20,
  "newChargesCreated": 1,
  "duplicatesBlocked": 19,
  "uniquePaymentIds": 1,
  "idempotencySuccessful": true,
  "verdict": "✓ PASS – only 1 charge created despite 20 concurrent requests"
}
```

---

## Running Tests

### Unit Tests (no external services required)

```bash
./mvnw test
```

Tests included:
- `PaymentIdempotencyServiceTest` – 5 unit tests covering happy path, Redis cache hit, DB existing claim, 5× same key, declined payment
- `ConcurrencyTest` – 4 DB-level concurrency tests using `CountDownLatch` and `ExecutorService` against an H2 in-memory database

### End-to-End Script (requires running service)

```bash
# Start the service first
docker-compose up -d

# Run the full test suite
bash scripts/test_idempotency.sh
```

### Concurrent Race-Condition Demonstration

```bash
bash scripts/concurrent_test.sh 20 PHARMACY_001
```

---

## Testing Idempotency – Step by Step

### 1. Prove a key is deduplicated

```bash
KEY=$(uuidgen)

# Send 5 identical requests
for i in 1 2 3 4 5; do
  curl -s -X POST http://localhost:8080/api/v1/payments \
    -H "Content-Type: application/json" \
    -H "X-Idempotency-Key: $KEY" \
    -H "X-Merchant-Id: PHARMACY_001" \
    -d '{"customerId":"CUST_001","amount":45.00,"currency":"EGP","paymentMethod":{"type":"CARD","cardLastFour":"4242","cardBrand":"VISA"}}' | python3 -m json.tool
  echo "--- Request $i complete ---"
done
```

Expected: request 1 returns `"duplicate": false`, requests 2–5 return `"duplicate": true` with the same `paymentId`.

### 2. See the audit trail

```bash
curl http://localhost:8080/api/v1/payments/$KEY/audit \
  -H "X-Merchant-Id: PHARMACY_001" | python3 -m json.tool
```

Expected: 5 events — 1× `NEW_PAYMENT`, 1× `PAYMENT_COMPLETED`, 3× `DUPLICATE_BLOCKED`.

### 3. Run the concurrent test (10 threads)

```bash
curl -X POST "http://localhost:8080/api/v1/test/concurrent?concurrency=10" | python3 -m json.tool
```

### 4. Generate 100 test payments and check stats

```bash
curl -X POST "http://localhost:8080/api/v1/test/generate?count=100" | python3 -m json.tool
curl http://localhost:8080/api/v1/stats | python3 -m json.tool
```

---

## Configuration Reference

| Property | Default | Description |
|---|---|---|
| `app.idempotency.key-expiration-hours` | `24` | Key TTL before it can be reused |
| `app.idempotency.pending-timeout-seconds` | `300` | Max time to wait for a PENDING payment |
| `app.idempotency.max-wait-attempts` | `15` | Poll count when waiting for PENDING |
| `app.idempotency.wait-interval-ms` | `200` | Initial poll interval (doubles each attempt) |
| `app.mock-processor.success-rate` | `0.80` | Fraction of payments approved |
| `app.mock-processor.decline-rate` | `0.15` | Fraction declined |
| `app.mock-processor.error-rate` | `0.05` | Fraction with gateway error |
| `app.mock-processor.processing-delay-min-ms` | `50` | Min simulated latency |
| `app.mock-processor.processing-delay-max-ms` | `200` | Max simulated latency |

---

## Observability

### Metrics (Prometheus)

```
GET /actuator/prometheus
```

Key metrics:
- `payments_new_total` – total new payment intents
- `payments_duplicates_blocked_total` – duplicate requests intercepted
- `payments_completed_total` – successful charges
- `payments_failed_total` – declined / errored charges
- `payment_api_process_seconds` – end-to-end processing latency histogram

### Health Check

```
GET /actuator/health
```

### Audit Log

```
GET /api/v1/stats/audit?merchantId=PHARMACY_001&hours=1
```

---

## Multi-Tenancy

Every idempotency key is scoped to a `(idempotency_key, merchant_id)` pair. Each of the 240 pharmacy locations uses its own `X-Merchant-Id` header, so key namespaces are fully isolated. `PHARMACY_001` using key `abc` never conflicts with `PHARMACY_002` using the same key.

---

## Graceful Degradation

If Redis becomes unavailable:
- All Redis operations are wrapped in try/catch and logged as warnings
- The service falls back to PostgreSQL for every lookup
- No payments are blocked or lost; only the Redis caching fast-path is bypassed

---

## Key Design Decisions

| Decision | Rationale |
|---|---|
| DB unique constraint as the lock | Atomic, no extra infrastructure; PostgreSQL MVCC handles concurrent inserts cleanly |
| Redis for completed-result cache | Sub-millisecond response for high-frequency retries; graceful fallback when Redis is down |
| `REQUIRES_NEW` transactions for claim and audit | Ensures records are committed immediately and independently of the caller's transaction |
| Polling (not pub/sub) for PENDING wait | Simpler, sufficient for a prototype; production would use Redis pub/sub or LISTEN/NOTIFY |
| Mock processor with configurable outcomes | Demonstrates all real-world paths (COMPLETED, DECLINED, ERROR) without external dependencies |

---

## Project Structure

```
src/main/java/com/crescent/pharmacy/idempotency/
├── IdempotencyServiceApplication.java
├── config/
│   ├── AppProperties.java          # Typed configuration
│   └── RedisConfig.java
├── controller/
│   ├── PaymentController.java      # POST /payments, GET /payments/{key}
│   ├── StatsController.java        # GET /stats, GET /stats/audit
│   └── TestDataController.java     # POST /test/generate, POST /test/concurrent
├── dto/                            # Request / response DTOs
├── entity/                         # JPA entities + enums
├── exception/                      # Custom exceptions + global handler
├── repository/                     # Spring Data JPA repositories
└── service/
    ├── PaymentIdempotencyService.java      # Core orchestrator
    ├── IdempotencyKeyPersistenceService.java # DB operations (REQUIRES_NEW)
    ├── RedisIdempotencyCache.java           # Redis cache wrapper
    ├── MockPaymentProcessor.java            # Simulated Yuno gateway
    └── AuditService.java                   # Event logging + Micrometer metrics
```
