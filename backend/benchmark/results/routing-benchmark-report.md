# PaymentOS controlled routing benchmark

Generated: 2026-10-25T20:14:14.919317400Z

This is an in-process H2 simulator benchmark, not a production-network performance test. Paired strategies use identical seeded workloads and reset processor/database state for every seed.

| Scenario | Strategy | Payments (S/F/U) | Payment success | Attempts | Attempt success | Avg attempt ms | P95 attempt ms | Payments/s | Attempt processor distribution | End circuit states |
|---|---|---:|---:|---:|---:|---:|---:|---:|---|---|
| normal | ROUND_ROBIN | 300 (274/26/0) | 91.33% | 300 | 91.33% | 183.51 | 233 | 21.16 | {Benchmark A=102, Benchmark B=99, Benchmark C=99} | {CLOSED=9} |
| normal | HEALTH_AWARE | 300 (274/26/0) | 91.33% | 300 | 91.33% | 138.59 | 184 | 25.5 | {Benchmark A=278, Benchmark B=22} | {CLOSED=9} |
| processor-a-failure | ROUND_ROBIN | 300 (274/26/0) | 91.33% | 306 | 89.54% | 206.58 | 233 | 39.43 | {Benchmark A=6, Benchmark B=153, Benchmark C=147} | {CLOSED=6, OPEN=3} |
| processor-a-failure | HEALTH_AWARE | 300 (274/26/0) | 91.33% | 303 | 90.43% | 185.29 | 184 | 44.0 | {Benchmark A=3, Benchmark B=289, Benchmark C=11} | {CLOSED=9} |

## Interpretation

Payment-level status rates use one terminal payment outcome per payment. Attempt-level success rates use individual processor attempts, including retries. Latency percentiles use simulator-recorded attempt durations; throughput measures local service-call wall time. The separate recovery check verified expired OPEN → HALF_OPEN, one reserved probe, and a successful probe closing the circuit. Treat these measurements as reproducible simulator observations, not evidence of real network or production performance.
