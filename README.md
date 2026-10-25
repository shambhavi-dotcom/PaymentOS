# PaymentOS

PaymentOS is an educational payment orchestration simulator with a Spring Boot API, PostgreSQL persistence, and a React operations dashboard. It is not connected to Visa, other card networks, or live payment processors and is not production-ready.

## Architecture and technology

- **Backend:** Java 21, Spring Boot 3.3, Spring MVC, Bean Validation, Spring Data JPA/Hibernate, Flyway, PostgreSQL.
- **Frontend:** React 18, TypeScript, Vite, Tailwind CSS, Recharts, Lucide React.
- **Tests:** Maven/JUnit/Spring integration tests; H2 in PostgreSQL compatibility mode for fast tests; environment-gated integration tests against a real PostgreSQL database.
- **Persistence:** Payments, processor attempts, payment events, idempotency fingerprints, processor health observations, and circuit-breaker state are stored in the relational database.

The frontend calls the `/api` backend. Vite proxies that path to `http://localhost:8080` during local development. Production hosting must configure a same-origin reverse proxy or equivalent for `/api`.

## Payment processing flow

1. `POST /api/payments` validates the merchant, amount, currency, customer reference, and `Idempotency-Key`.
2. The backend creates the payment and creation event in one transaction.
3. The selected routing policy chooses an enabled processor whose circuit is routable. The processor rows are locked during selection to coordinate breaker state and HALF_OPEN probe reservations.
4. An attempt is recorded with a unique processor request ID and the simulator's outcome and measured duration.
5. A confirmed pre-execution failure may be retried against a different eligible processor. A decline is final. A timeout with uncertain outcome becomes `UNKNOWN` and is never automatically retried.
6. The payment's terminal status and corresponding event are persisted with its attempts.

If no processor is eligible, the payment is still persisted as `FAILED` with a status event and no attempt. The caller receives a normal payment response rather than an infrastructure exception that would roll the record back.

## Idempotency

Idempotency is scoped to `(merchant_id, idempotency_key)` and enforced by a database uniqueness constraint. The request fingerprint binds the key to the normalized merchant, currency, amount, and customer reference. Replays with the same payload return the existing payment without another processor dispatch; reuse with a different payload returns HTTP 409. Creation occurs in a separate transactional service so a uniqueness conflict rolls back before the duplicate is read and returned.

## Routing algorithms

- **Round Robin:** cycles through the currently eligible processors in processor-ID order.
- **Health Aware:** ranks CLOSED processors ahead of HALF_OPEN candidates, then prefers higher recent success rate and lower recent latency.
- **Latency Aware:** ranks eligible processors by lower recent latency, then higher recent success rate.

Routing names are stored with each payment and surfaced in historical aggregates. The strategy does not override disabled state or an OPEN breaker.

## Circuit-breaker behavior

- A processor opens after three consecutive pre-execution failures/timeouts, or when its adjusted recent success rate falls below the configured floor.
- OPEN processors are excluded from ordinary routing for a 30-second cooldown.
- On the next routing selection after cooldown, an OPEN breaker moves to HALF_OPEN and can reserve one probe. The persisted reservation prevents another probe from being selected before the current probe outcome is recorded.
- A responsive processor result closes the breaker and clears the failure count. A failed or uncertain probe reopens it and starts a new cooldown.
- Recovery is demand-driven: the cooldown expiration is observed on a subsequent route selection; there is no background health-check scheduler.

## Reconciliation semantics

`POST /api/payments/{id}/reconcile` only accepts payments in `UNKNOWN` with a latest `TIMEOUT_UNKNOWN` attempt. The server asks the simulator to resolve the authoritative outcome using that attempt's original processor request ID; the client cannot choose the final status. Reconciliation updates the existing attempt and payment and records a payment event. It does not create another charge attempt.

## Controlled routing benchmark

Run from PowerShell:

```powershell
.\backend\benchmark\run-benchmark.ps1 -MavenCommand "C:\path\to\mvn.cmd"
```

The benchmark runs only when explicitly enabled by that script. It compares Round Robin and Health Aware on three fixed seeds and 100 identical synthetic payments per seed (300 per strategy per scenario). It resets payments, attempts, events, processor configuration, and the Round Robin cursor before each seed/strategy run; alternates strategy execution order by seed to reduce warm-up bias; and uses the same generated workload and processor settings for both strategies. Scenarios cover normal configuration and Processor A configured for 100% pre-execution failure while B and C remain healthy.

Results are generated at:

- `backend/benchmark/results/routing-benchmark.json` — machine-readable payment/attempt outcomes, rates, latency percentiles, throughput, processor selection distribution, and circuit states.
- `backend/benchmark/results/routing-benchmark-report.md` — concise readable summary.
- `frontend/public/routing-benchmark.json` — copied benchmark snapshot displayed separately from the dashboard's historical observations.

### Measured results in the checked-in benchmark snapshot

| Scenario | Strategy | Payments (succeeded / failed / unknown) | Payment success | Processor attempts | Attempt success | Average / P95 attempt latency | Measured payments/s |
|---|---|---:|---:|---:|---:|---:|---:|
| Normal | Round Robin | 300 (274 / 26 / 0) | 91.33% | 300 | 91.33% | 183.51 / 233 ms | 21.16 |
| Normal | Health Aware | 300 (274 / 26 / 0) | 91.33% | 300 | 91.33% | 138.59 / 184 ms | 25.50 |
| Processor A failure | Round Robin | 300 (274 / 26 / 0) | 91.33% | 306 | 89.54% | 206.58 / 233 ms | 39.43 |
| Processor A failure | Health Aware | 300 (274 / 26 / 0) | 91.33% | 303 | 90.43% | 185.29 / 184 ms | 44.00 |

The fixed-snapshot simulator results show the same terminal payment success rate for both strategies. Health Aware recorded fewer attempts in the failure scenario; it deprioritizes a processor after observed failures, while Round Robin continues selecting an available processor until its breaker opens. The normal scenario's latency differs because routing policies choose different processors with different configured latencies. Throughput varied substantially between benchmark executions and is noisy in-process H2/service-call throughput, not network throughput; these figures do **not** establish production performance or a general strategy winner. The JSON and report are regenerated by the benchmark script and supersede these figures after each run.

## Setup and validation

### Start PostgreSQL and the API

From the repository root:

```powershell
docker compose up --build postgres backend
```

The API listens at `http://localhost:8080`. Flyway applies the database migrations on startup.

### Start the dashboard

```powershell
cd frontend
npm ci
npm run dev
```

Open `http://localhost:5173`.

### Run backend tests

Use Java 21 and Maven:

```powershell
cd backend
mvn test
```

The PostgreSQL integration tests run only when their connection variables are configured. Against a disposable test database:

```powershell
$env:PAYMENTOS_POSTGRES_TEST_URL = "jdbc:postgresql://localhost:5432/paymentos"
$env:PAYMENTOS_POSTGRES_TEST_USERNAME = "paymentos"
$env:PAYMENTOS_POSTGRES_TEST_PASSWORD = "paymentos"
mvn test
```

Do not point destructive integration-test setup at a production database.

### Build and lint the frontend

```powershell
cd frontend
npm ci
npm run build
npm run lint
```

## Known limitations

- Processor integrations, failure schedules, timeouts, and reconciliation outcomes are simulated; there are no real payment-network or acquirer calls.
- Simulator outcomes are deterministic from request fingerprints and configured probabilities rather than real stochastic processor behavior.
- Benchmark latency values are simulator-recorded durations. Throughput is a local in-process measurement using H2 and must not be presented as a production SLA or external-network benchmark.
- Circuit recovery begins on demand at the next routing selection after cooldown; there is no background probe scheduler.
- The controlled benchmark is a modest fixed workload. It is useful for reproducibility and debugging, not a statistically rigorous capacity or reliability qualification.
- Production deployment needs managed secrets, authentication/authorization, operational monitoring, load testing, production database review, and an API reverse proxy; these are outside this simulator's current scope.
