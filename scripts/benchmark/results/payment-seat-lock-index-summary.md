# Phase 6C-7B Payment Seat-Lock Order/Status Index

## Scope and environment

- Branch: `feat/observability-benchmark`
- PRE_OPT_COMMIT: `38c38599dd972fb379b533bf2d304ad35616c2fa`
- **LOCAL BENCHMARK — NOT PRODUCTION CAPACITY**.
- The sole production optimization was one ordinary secondary index on `seat_lock(order_no,status)`. Payment SQL, transaction boundaries, isolation, retry, Hikari, Outbox and per-seat inserts were unchanged.
- Payment profiling was enabled only for benchmark containers. The repository default remains disabled.

## Schema and execution plan

Before the change, `seat_lock` had:

- `PRIMARY (id)`;
- unique `idx_seat_lock_unique (schedule_id,row_num,col_num)`;
- `idx_seat_lock_schedule (schedule_id,status)`;
- `idx_seat_lock_user (user_id,status)`;
- `idx_seat_lock_token (lock_token)`;
- `idx_seat_lock_expire (lock_until,status)`.

`order_no` is nullable `VARCHAR(64)` and `status` is nullable `INT DEFAULT 1`. No index began with `order_no`.

The added DDL is:

```sql
ALTER TABLE seat_lock
    ADD INDEX idx_seat_lock_order_status (order_no, status);
```

`order_no` leads because the existing Payment predicate uses equality on both columns and order number is highly selective for this workload, while status has low cardinality. This is a workload-specific conclusion.

| Plan | type | possible_keys | key | rows | Extra |
|---|---|---|---|---:|---|
| Before | index | NULL | PRIMARY | 33 | Using where |
| After | range | idx_seat_lock_order_status | idx_seat_lock_order_status | 1 | Using where; Using temporary |

MySQL selected the new index without `FORCE INDEX` and reduced the estimate to one row.

## Correctness and write-path regression

- 1-seat Payment regression: PASS.
- Same-order concurrent Payment, 8 ways: PASS; one debit, one payment record, one order seat, one ticket and one PAID event.
- 3-seat and 6-seat Payment-only runs: all requests succeeded; cleanup found no missing/orphan event.
- pay → refund: PASS; points and stock restored once, purchased seat locks removed.
- pay → check-in: PASS; USED ticket caused refund rejection, and an invalidated ticket caused check-in rejection.
- Missing seat-lock INSERT, expired in-place reclaim and bound-row protection: PASS.
- Different-seat lock regression: 8/16/25 all PASS; 25-way repeated 3/3 with no deadlock.
- Selection replacement: PASS; the same user retained exactly one active unbound selection.

The lock/reclaim validation helper was corrected only where it actually failed under Windows PowerShell 5.1: JSON-array materialization is now explicit and test coordinates are selected outside historical sold seats. The payment fixture received the same PowerShell 5.1 array-materialization correction. Neither correction changes the benchmark workload or production behavior.

## Short Payment-only diagnostics

All rows are different-user workloads with zero rate limit, system, network, timeout and parse errors and no new deadlock.

| Run | VU | Seats | Duration | TPS | Payment P50/P95/P99 ms | INSERT P95 ms | UPDATE mean/P95/max ms | Row waits count/time | Waiting trx peak | Hikari active/pending | CPU peak/median |
|---:|---:|---:|---:|---:|---|---:|---|---|---:|---|---|
| 7111 | 1 | 1 | 20s | 1.850 | 40 / 83.6 / 267.12 | 2.064 | 1.162 / 1.622 / 9.728 | 0 / 0ms | 0 | 2 / 0 | 2.170% / 1.002% |
| 7251 | 25 | 1 | 25s | 45.200 | 38 / 131.55 / 586.71 | 2.867 | 6.984 / 39.830 / 249.002 | 146 / 5,089ms | 2 | 7 / 0 | 15.840% / 6.401% |
| 7253 | 25 | 3 | 20s | 43.950 | 42 / 162.10 / 908.00 | 9.404 | 8.144 / 44.024 / 252.737 | 115 / 4,212ms | 1 | 13 / 12 | 20.868% / 11.440% |
| 7256 | 25 | 6 | 20s | 42.400 | 57 / 237.25 / 899.07 | 18.809 | 14.146 / 64.995 / 462.048 | 127 / 6,288ms | 3 | 20 / 5 | 18.876% / 8.749% |

Against Phase 6C-7A, UPDATE P95 changed from `9.929 → 1.622ms` at 1 VU/1 seat, `117.408 → 39.830ms` at 25 VU/1 seat, `369.033 → 44.024ms` at 25 VU/3 seats, and `419.299 → 64.995ms` at 25 VU/6 seats. The primary 25 VU one-seat diagnostic reduced row waits from `519 / 14,153ms` to `146 / 5,089ms`.

## Formal Payment-only 25 VU, one seat

Each round used a 30-second warmup, reset, and a 60-second measurement. The backend was restarted after warmup so the read-only Micrometer stage snapshot contains measurement samples only; this means JVM warm state was not carried across that restart, while MySQL/Redis data and caches remained local and warm.

| Run | TPS | Payment P50/P95/P99 ms | UPDATE P95 ms | INSERT P95 ms | Row waits count/time | Waiting trx peak | Hikari active/pending | CPU peak/median | Errors |
|---:|---:|---|---:|---:|---|---:|---|---|---|
| 731 | 45.867 | 34 / 93 / 343.56 | 23.052 | 1.360 | 294 / 11,103ms | 8 | 6 / 0 | 16.726% / 3.869% | 0 |
| 732 | 46.833 | 22 / 72 / 320.01 | 10.469 | 1.360 | 285 / 5,970ms | 1 | 3 / 0 | 18.204% / 4.783% | 0 |
| 733 | 47.150 | 21 / 67 / 194.68 | 10.994 | 1.229 | 310 / 5,579ms | 4 | 8 / 0 | 16.573% / 4.171% | 0 |
| median | 46.833 | 22 / 72 / 320.01 | 10.994 | 1.360 | 294 / 5,970ms | 4 | 6 / 0 | 16.726% / 4.171% | 0 |

Compared with the 7A one-seat before values, the formal median UPDATE P95 fell 90.6% (`117.408 → 10.994ms`), Payment P95 fell 56.4% (`165 → 72ms`), row-wait count fell 43.4% (`519 → 294`) and row-wait time fell 57.8% (`14,153 → 5,970ms`). Isolated Payment therefore confirms that the index materially narrows and accelerates the target UPDATE. Per-seat INSERT remained secondary at a 1.360ms median P95.

## Full transaction

The 25-second short gate passed the requested system/timeout criteria: `85.440 TPS`, transaction `P50/P95/P99 = 279/360/487.90ms`, Payment-stage P95 `104ms`, system/network/timeout/parse errors zero, and no new deadlock. Ten business conflicts were reported separately.

| Formal run | TPS | Transaction P50/P95/P99 ms | UPDATE P95 ms | Row waits count/time | Hikari active/pending | CPU peak/median | Business conflict | System/network/timeout/parse | Deadlock |
|---:|---:|---|---:|---|---|---|---:|---|---|
| 741 | 68.417 | 323 / 616 / 897.84 | 46.121 | 5,640 / 543,867ms | 20 / 6 | 40.053% / 27.270% | 10 | 0 / 0 / 0 / 0 | 0 |
| 742 | 77.567 | 299 / 469 / 603.29 | 39.830 | 6,373 / 542,027ms | 20 / 7 | 44.748% / 26.760% | 12 | 0 / 0 / 0 / 0 | **1** |
| 743 | NOT RUN | STOP after run 742 reliability/correctness gate | — | — | — | — | — | — | — |

Both completed measurements met P95 `<800ms` and P99 `<1.2s`. A three-run formal median is intentionally not reported because the third run was forbidden after the run-742 gate failure.

The run-742 deadlock is direct evidence of a new lock-order interaction involving the added index:

- Payment's purchased-lock UPDATE held an X gap lock in `idx_seat_lock_order_status` and waited for an insert intention on `idx_seat_lock_user`.
- Concurrent Order Create `bindLocksToOrder` held X on `idx_seat_lock_user` and waited for an insert intention on `idx_seat_lock_order_status`.
- InnoDB rolled back the Order Create transaction.

No retry was added, as required. This deadlock did not occur in the isolated Payment-only formal runs.

## Outbox reliability gate

| Full run | Measurement-end open | 30s open | Time to zero | Final pending/processing/failed | Missing/orphan |
|---:|---:|---:|---:|---|---|
| 741 | 6,170 | 0 | 63.412s | 0 / 0 / 0 | 0 / 0 |
| 742 | 7,112 | 395 | 137.227s | 0 / 0 / 0 | 0 / 0 |

Run 742 eventually drained without deleting unconsumed events, but it did not reach zero within 120 seconds from measurement end. Therefore the required `120s backlog=0` gate failed. Missing, orphan and failed counts remained zero.

## Classification

- Isolated target-query result: the index is effective and Payment-only A/B is positive.
- Full-transaction acceptance: failed because formal run 742 introduced a measured index-related deadlock and exceeded the 120-second Outbox drain gate.
- Hikari is not promoted as the next primary variable: only peak pending was collected, and the same full runs had heavy InnoDB waits; sustained pool starvation was not established.
- `NEXT_BOTTLENECK=FULL_TRANSACTION_SEAT_LOCK_INDEX_LOCK_ORDER`.
- Final required classification: `SEAT_LOCK_ORDER_STATUS_INDEX_INSUFFICIENT`.
- The index adds normal secondary-index storage and write amplification to seat-lock insert/reclaim/bind operations. Functional write-path regression passed, but the full-transaction deadlock makes the current index design unacceptable without a separate follow-up design.

