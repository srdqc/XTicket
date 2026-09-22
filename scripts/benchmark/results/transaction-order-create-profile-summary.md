# Phase 6C-3A Transaction Order-Create Internal Profiling

> LOCAL DIAGNOSTIC — NOT PRODUCTION CAPACITY

## Scope

- Branch: `feat/observability-benchmark`
- HEAD: `486dae92afbab49ebe7c100612e3d6cfe90498a6`
- Workload: `transaction-flow`, one seat per order, fixed session `910001`
- Runs: 10 VU × 2 and 25 VU × 2, 25 seconds each; no 50 VU run
- Production changes are instrumentation only. SQL, indexes, transactions, locks, stock, Hikari and Outbox publisher behavior were not changed.

## Actual call chain and SQL count

`JwtAuthInterceptor / RateLimitAspect → OrderController.createOrder → OrderBiz.createOrder → OrderService.createOrder → Redisson seat:{scheduleId} → TransactionTemplate → createOrderInScheduleLock → commit → OrderBiz response enrichment`.

The successful benchmark path receives an existing `lockToken` from the preceding lock request:

1. SELECT `activity_session` by primary key.
2. DELETE expired unlocked `seat_lock` rows.
3. SELECT `ticket_order` by unique lock token for idempotency.
4. SELECT active `seat_lock` rows by lock token and validate the returned N rows in memory.
5. SELECT trusted snapshot from `activity_session` joined to `activity` and `venue`.
6. Redis Lua pre-deduct.
7. UPDATE `activity_session` optimistic stock/version CAS.
8. INSERT `ticket_order`.
9. UPDATE all matching `seat_lock` rows to bind the order.
10. SELECT `activity_session`, then refresh the Redis session-detail hash.
11. INSERT `outbox_event`.
12. Commit.
13. Outside the transaction, SELECT session and SELECT activity for response enrichment.

Per successful API create: SELECT 7, INSERT 2, UPDATE 2, DELETE 1; total SQL statements 12. There is no `order_seat` write during create. SQL count is constant for the benchmark path as seat count grows; the lock query returns N rows and the bind is one UPDATE.

The legacy branch used only when no `lockToken` is supplied performs one active-lock SELECT and one seat-lock INSERT per requested seat. It is a conditional row-by-row/N+1 candidate, but its timer count was zero and it did not participate in this workload.

There is no `SELECT ... FOR UPDATE`, DB lock retry or optimistic-CAS retry in create. The only explicit wait is the shared session-level Redisson lock. Outbox publisher runs asynchronously on the same application DataSource/Hikari pool; publisher drain time is not part of request latency.

## Outer transaction stages

All latency values are milliseconds.

| VU/run | TPS | Lock P50/P95/P99 | Create P50/P95/P99 | Payment P50/P95/P99 | Full P50/P95/P99 | Success/failure | Business/system/network/timeout |
|---|---:|---:|---:|---:|---:|---:|---:|
| 10/1 | 28.976 | 133.0 / 316.45 / 646.18 | 146.0 / 335.90 / 520.73 | 27.0 / 50.45 / 67.07 | 294.5 / 609.75 / 1,026.91 | 732 / 0 | 0 / 0 / 0 / 0 |
| 10/2 | 29.392 | 130.0 / 355.85 / 541.59 | 143.0 / 350.95 / 491.85 | 27.0 / 48.00 / 81.00 | 297.0 / 655.95 / 1,043.00 | 742 / 0 | 0 / 0 / 0 / 0 |
| 25/1 | 23.460 | 413.0 / 1,237.95 / 3,511.55 | 425.0 / 1,087.10 / 1,710.09 | 31.0 / 54.85 / 68.82 | 844.5 / 1,777.55 / 2,507.70 | 604 / 20 | 20 / 0 / 0 / 0 |
| 25/2 | 27.260 | 372.0 / 1,028.30 / 1,870.34 | 392.0 / 1,125.90 / 1,628.42 | 28.0 / 49.10 / 65.02 | 759.0 / 1,906.10 / 2,908.64 | 699 / 0 | 0 / 0 / 0 / 0 |

The 20 business conflicts in 25/1 were bounded-lock acquisition failures, not system/network/timeout errors.

## Internal stage timers

Each cell is `count / mean / P50 / P95 / P99`, with latency in milliseconds. The application was restarted before every round, so snapshots contain only that round.

| Stage | 10 VU run1 | 10 VU run2 | 25 VU run1 | 25 VU run2 |
|---|---:|---:|---:|---:|
| request_validation | 732/0.003/0.002/0.004/0.011 | 742/0.003/0.002/0.004/0.006 | 608/0.004/0.003/0.008/0.016 | 699/0.003/0.002/0.005/0.006 |
| redisson_wait | 732/138.591/113.238/260.039/469.754 | 742/137.529/109.044/251.650/352.313 | 608/454.229/369.091/872.407/1,409.278 | 699/434.292/335.536/771.744/1,207.951 |
| load_session | 732/0.785/0.475/0.999/1.229 | 742/0.716/0.475/0.967/1.294 | 604/0.788/0.541/1.098/1.491 | 699/0.746/0.475/0.934/1.491 |
| cleanup_expired_locks | 732/6.394/6.275/13.091/16.237 | 742/6.326/6.013/12.567/14.664 | 604/7.042/6.799/15.712/19.907 | 699/7.032/7.062/14.664/15.712 |
| create_missing_lock | 0/0/0/0/0 | 0/0/0/0/0 | 0/0/0/0/0 | 0/0/0/0/0 |
| idempotency_check | 732/0.601/0.401/0.844/1.237 | 742/0.599/0.418/0.877/1.171 | 604/0.673/0.516/1.040/1.499 | 699/0.606/0.434/0.811/1.106 |
| verify_locks | 732/0.807/0.573/1.098/1.425 | 742/0.817/0.573/1.032/1.425 | 604/0.901/0.672/1.163/1.819 | 699/0.843/0.573/0.967/1.360 |
| load_snapshot | 732/0.738/0.492/0.967/1.294 | 742/0.767/0.508/0.967/1.294 | 604/0.809/0.606/1.229/1.753 | 699/0.740/0.492/0.934/1.360 |
| redis_stock | 732/0.645/0.459/0.934/1.229 | 742/0.660/0.442/0.868/1.032 | 604/0.711/0.541/1.032/1.425 | 699/0.641/0.442/0.836/1.163 |
| db_stock_update | 732/0.894/0.606/1.163/1.688 | 742/0.883/0.606/1.229/1.819 | 604/0.954/0.705/1.556/1.819 | 699/0.853/0.573/1.098/1.360 |
| order_insert | 732/1.246/0.836/1.819/2.474 | 742/1.206/0.819/1.475/2.195 | 604/1.312/0.934/1.884/5.489 | 699/1.159/0.836/1.425/1.622 |
| bind_locks | 732/0.863/0.639/1.229/1.556 | 742/0.826/0.573/1.032/1.425 | 604/0.901/0.672/1.294/2.015 | 699/0.817/0.606/1.032/1.491 |
| refresh_cache | 732/1.351/0.950/1.802/2.458 | 742/1.383/0.918/1.802/2.589 | 604/1.539/1.147/2.195/2.851 | 699/1.354/0.918/1.606/2.064 |
| outbox_insert | 732/1.052/0.705/1.425/2.605 | 742/1.060/0.705/1.425/1.950 | 604/1.168/0.803/1.819/2.474 | 699/1.041/0.672/1.360/1.950 |
| response_mapping | 732/0.015/0.007/0.016/0.035 | 742/0.014/0.006/0.015/0.019 | 604/0.019/0.014/0.039/0.065 | 699/0.015/0.006/0.015/0.019 |
| tx_completion | 732/3.407/2.949/4.915/7.537 | 742/3.349/2.818/4.391/5.439 | 604/8.669/3.473/5.964/13.042 | 699/4.043/3.342/5.177/5.964 |
| response_enrichment | 732/1.642/1.147/2.064/3.244 | 742/1.598/1.147/1.933/2.458 | 604/1.842/1.343/2.458/4.030 | 699/1.577/1.081/1.933/2.589 |

`tx_completion` is the observed `TransactionTemplate.execute` duration outside its callback; it includes framework transaction begin/commit completion overhead without changing transaction management. It is not a JDBC-driver-only commit trace.

## Resource and Outbox evidence

| VU/run | CPU peak/median % | Heap peak | GC count/time | Hikari active/idle/pending peak | Sampler pending peak | Measurement-end open | Time-to-zero |
|---|---:|---:|---:|---:|---:|---:|---:|
| 10/1 | 16.093 / 10.475 | 90,291,624 | 65 / 0.276 s | 9 / 10 / 2 | 372 | 683 | 19.593 s |
| 10/2 | 15.537 / 10.309 | 89,152,928 | 65 / 0.235 s | 7 / 12 / 0 | 441 | 697 | 19.886 s |
| 25/1 | 14.169 / 10.994 | 92,522,064 | 49 / 0.250 s | 20 / 13 / 2 | 261 | 554 | 17.327 s |
| 25/2 | 13.853 / 9.530 | 93,301,696 | 60 / 0.365 s | 17 / 11 / 0 | 419 | 666 | 20.007 s |

Hikari pending briefly reached 2 in one run at each VU and was zero in the paired run. Pool contention therefore cannot be declared absolutely absent, but it is not supported as the primary cause: create P95 and Redisson wait remained high when pending was zero, while DB/Outbox/commit stages stayed in low single-digit milliseconds. Publisher backlog did not move monotonically with create P95 and safely drained below 30 seconds in all runs; no synchronous publisher-contention relationship was observed.

## Static EXPLAIN evidence

| SQL | type | key | rows | Extra |
|---|---|---|---:|---|
| session PK SELECT | const | PRIMARY | 1 | — |
| expired-lock DELETE | ALL | none | 28 | Using where |
| lock-token order SELECT | const by unique `uq_ticket_order_lock_token` | unique BTREE | ≤1 | no matching row in sampled empty table |
| active locks by token | ref | `idx_seat_lock_token` | 1 | Using where |
| snapshot joins | const ×3 | PRIMARY | 1 each | — |
| session stock CAS UPDATE | range | PRIMARY | 1 | Using where |
| bind locks UPDATE | range | `idx_seat_lock_unique` | 1 estimated | Using where |
| enrichment activity SELECT | const | PRIMARY | 1 | — |

The expired-lock cleanup scan is observable but small in this fixture and its P95 is 12.567–15.712 ms, far below the shared-lock wait. No index or SQL was changed.

## Classification

`MIXED`

- PRIMARY: session-level Redisson lock wait shared by seat lock and order create.
- SECONDARY: expired-lock cleanup inside the serialized create critical section.

Strongest evidence: Redisson wait P95 increased from 251.650–260.039 ms at 10 VU to 771.744–872.407 ms at 25 VU, while every individual DB insert/update/query stage remained below 2.5 ms P95, transaction completion below 6 ms P95, and Outbox insert below 1.9 ms P95.

The single next optimization variable is `SESSION_LEVEL_REDISSION_LOCK_SCOPE`: redesign only the create critical-section/key granularity while preserving lock-token uniqueness, stock correctness and DB CAS guarantees. This phase does not implement that change.
