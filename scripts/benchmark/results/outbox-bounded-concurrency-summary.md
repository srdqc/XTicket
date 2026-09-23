# Phase 6C-8 Outbox Bounded Publisher Concurrency

## Scope

- Branch: `feat/observability-benchmark`
- Benchmark base commit: `d2758ef`
- Environment: local Docker benchmark with client and server on the same host; not production-capacity evidence.
- Single production variable: bounded per-event Outbox publisher concurrency.
- Batch size remained 50, scheduler fixed delay remained 1000 ms, and full batches still drained consecutively.
- Order/Create/Payment, seat locks, indexes, Hikari sizing, transactions, RocketMQ broker and consumer behavior were not changed.

## Correctness model

The scheduler remains single-threaded and waits for every bounded worker task in a batch. Each worker independently performs:

1. conditional CAS claim to `PROCESSING`;
2. skip when claim returns zero;
3. synchronous RocketMQ send;
4. `markPublished` after a successful send;
5. the existing retryable `markFailed` path after a send failure.

The stable event ID, 30-second stale `PROCESSING` recovery, bounded exponential retry delay and consumer unique-key idempotency remain unchanged. Delivery remains at-least-once; this change does not claim exactly-once delivery.

## Quick fixed-event comparison

Each candidate drained 3,000 real PENDING Outbox events through the production publisher and consumer.

| Concurrency | Drain seconds | Events/s | Send errors | Retries | Hikari active/pending peak | CPU peak/median |
|---:|---:|---:|---:|---:|---:|---:|
| 1 | 30.472 | 98.451 | 0 | 0 | 2 / 0 | 6.979% / 3.543% |
| 2 | 17.728 | 169.224 | 0 | 0 | 4 / 0 | 9.633% / 5.583% |
| 4 | 11.260 | 266.430 | 0 | 0 | 8 / 0 | 12.787% / 1.874% |

Concurrency 2 improved fixed-workload throughput by 71.9% over concurrency 1 without Hikari pending, send failures, or retries. It is the smallest candidate satisfying the selection rule, so concurrency 4 was not selected solely for its higher isolated throughput.

The selected-concurrency short 25 VU transaction gate passed at 86.000 TPS with P50/P95/P99 of 279/361/543.93 ms. Its backlog decreased from 2,430 at measurement end to 250 at 10 seconds and zero at the subsequent checkpoints; system, network and parse errors, timeouts and deadlocks were zero.

The Quick runner completed all workloads successfully, but its first summary assembly encountered a PowerShell case-insensitive variable collision after the workload had finished. The runner was corrected and the compact Quick summary was regenerated solely from that run's structured artifacts; no successful business workload was repeated.

## Formal full transaction, concurrency 2

Each run used 30 seconds warmup followed by 60 seconds measurement.

| Run | TPS | P50/P95/P99 ms | Produced/s | Published/s during measurement | End backlog | 30s / 60s / 120s backlog | Time to zero | Hikari active/pending | CPU peak/median |
|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 7811 | 68.083 | 280 / 590 / 806.76 | 136.167 | 40.785 | 5,682 | 0 / 0 / 0 | 50.187s | 20 / 9 | 27.324% / 8.121% |
| 7812 | 93.233 | 224 / 470 / 561 | 186.467 | 56.363 | 7,441 | 0 / 0 / 0 | 67.379s | 20 / 8 | 19.990% / 17.214% |
| 7813 | 95.000 | 213 / 513 / 661.03 | 190.000 | 57.300 | 7,576 | 0 / 0 / 0 | 70.067s | 20 / 9 | 20.953% / 17.231% |
| Median | **93.233** | - / **513** / **661.03** | **186.467** | **56.363** | **7,441** | **0 / 0 / 0** | **67.379s** | **20 / 9** | **20.953% / 17.214%** |

All three runs met P95 below 800 ms and P99 below 1.2 seconds. Every run reached zero backlog before 120 seconds. System errors, network errors, parse errors, timeouts and deadlocks were zero in every run. Final missing, orphan and failed counts were also zero.

Hikari reached 20 active connections with 8-9 pending during the foreground transaction workload. This is comparable to the prior saturated-pool observation and did not produce a greater-than-10% foreground latency regression: median P95 was 513 ms versus the preceding 524 ms. CPU was not saturated. The evidence therefore does not classify the bounded publisher itself as `PUBLISHER_DB_CONTENTION`, and there is no evidence that the consumer became the limiting stage.

## Result

- Selected default concurrency: **2**.
- Outbox reliability gate: **PASS**.
- Final classification: `OUTBOX_BOUNDED_CONCURRENCY_CONFIRMED`.
- Next bottleneck: shared database/Hikari contention during full transactions remains the next profiling candidate; no pool-size change is justified by this phase alone.
- Quick machine summary: `scripts/benchmark/results/phase6c-8-quick-20260923-204424/summary.json`.
- Formal machine summary: `scripts/benchmark/results/phase6c-8-formal-20260923-205101/summary.json`.
