# Phase 6C-3C / 3C-1 Order Create Seat Lock Summary

## Scope and classification

- Branch: `feat/observability-benchmark`
- HEAD and benchmark source revision: `1fb643a7078b8bde35ed6ed996a0e27f569fa827`
- Environment: local Docker benchmark; client and server share one host. This is not a production-capacity result.
- Phase 6C-3C changed Order Create from a session-level Redisson lock to deterministic seat/seat-set locks.
- The first 8-way same-session test exposed `HOT_SESSION_STOCK_VERSION_CAS`: 3 CAS attempts plus bounded backoff still produced only 3/8 successes (`retry=12`, `exhausted=5`). Retrying a stale version was safe, but caused unnecessary failures.
- Phase 6C-3C-1 replaced only the stock decrement condition. Seat locks, Redis stock, transaction boundaries, Hikari, Outbox, RocketMQ, and database indexes were not changed.

## Stock decrement change

Old version CAS:

```sql
UPDATE activity_session
SET available_seats = available_seats - :seatCount,
    version = version + 1,
    update_time = CURRENT_TIMESTAMP
WHERE id = :scheduleId
  AND version = :version
  AND available_seats >= :seatCount
  AND deleted = 0;
```

New atomic conditional decrement:

```sql
UPDATE activity_session
SET available_seats = available_seats - :seatCount,
    version = version + 1,
    update_time = CURRENT_TIMESTAMP
WHERE id = :scheduleId
  AND available_seats >= :seatCount
  AND deleted = 0;
```

`version` and its increment remain for other session-change semantics, but the Order Create decrement no longer compares a stale version. MySQL `EXPLAIN` selected `PRIMARY`, estimated `rows=1`, with `Using where`. InnoDB serializes updates to the same primary-key row and re-evaluates the stock predicate, so successful concurrent decrements neither lose updates nor oversell.

The old maximum-attempt loop, fresh-transaction retry, deterministic backoff, retry-exhausted path, and CAS-specific metrics were removed. Each create performs at most one Redis debit and compensates it at most once when the database decrement or a later transactional step fails. Database stock decrement, order insert, seat-lock bind, and Outbox insert remain in one eight-second `TransactionTemplate`.

## Correctness evidence

| Check | Result |
|---|---|
| Stock sufficient: 10 - 3 | PASS, affected=1, final=7 |
| Stock exact: 3 - 3 | PASS, affected=1, final=0 |
| Stock insufficient: 2 - 3 | PASS, affected=0, final=2 |
| Missing session | PASS, affected=0 |
| Atomic decrement, 8 workers | PASS, 8/8, 100 -> 92, version=8 |
| Atomic decrement, 25 workers | PASS, 25/25, 100 -> 75, version=25 |
| Same lockToken, 8 concurrent creates | PASS, one order and one CREATED event |
| Same seat, 8 users | PASS, one order |
| Same session/different seats, 8 | PASS, 8/8 |
| Same session/different seats, 16 | PASS, 16/16 |
| Same session/different seats, 25 | PASS, 25/25 |
| Different sessions, 8 | PASS, 8/8 |
| Reverse-order two-seat contention | PASS, no distributed deadlock, one winner |
| Multi-seat create | PASS, 3 seats |
| Ten-seat lock-key model | PASS, ten deterministic keys; HTTP create correctly rejects above the product maximum of six seats |
| Expired-seat relock | PASS |
| Concurrent selection replacement | PASS |
| Partial lock acquisition failure | PASS, acquired locks released in reverse order |
| Injected Outbox failure | PASS, database transaction rolled back and Redis compensated once |
| Oversell / duplicate orders / inventory | 0 / 0 / consistent |

`clean package` passed. The backend image was rebuilt from the new jar and reached `health=UP`.

## Trusted Phase 6C-3A before baseline (25 VU, 30 s warmup + 60 s measurement)

| Run | TPS | Transaction P95 ms | P99 ms | Create P95 ms | Redisson P95 ms | Stock update P95 ms | System errors | Hikari active/pending |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| 201 | 19.588 | 1790.75 | 2397.75 | 807.50 | 738.181 | 0.999 | 0 | 19 / 0 |
| 202 | 19.134 | 1886.10 | 2499.71 | 1090.85 | 805.298 | 1.032 | 0 | 17 / 0 |
| 203 | 19.551 | 1824.90 | 2456.14 | 1039.90 | 1006.625 | 1.032 | 0 | 20 / 1 |
| Median | 19.551 | 1824.90 | 2456.14 | 1039.90 | 805.298 | 1.032 | 0 | — |

## Short diagnostic after atomic decrement

Both runs used 25-second measurement-only diagnostics, so they are gate evidence rather than formal replacements for the Phase 6C-3A baseline.

| VU | TPS | Transaction P95/P99 ms | Create P95 ms | Redisson mean/max ms | Stock update mean/max ms | CPU peak/median | Hikari active/pending | Errors |
|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 10 | 20.744 | 707.70 / 784.16 | 67.95 | 0.499 / 8.909 | 1.611 / 33.863 | 16.246% / 6.770% | 12 / 0 | system=33, timeout/network/parse=0 |
| 25 | 22.723 | 1362.10 / 1824.73 | 273.60 | 0.529 / 6.916 | 17.886 / 373.899 | 17.020% / 7.378% | 20 / 7 | system=71, timeout/network/parse=0 |

The Micrometer percentile snapshot reported `0 ms` for both Redisson and stock-update P95 in these low-duration stage distributions; mean and max are therefore retained as the reliable diagnostic evidence. Relative to the formal before medians, the non-comparable short 25 VU diagnostic showed directional reductions of 25.4% in transaction P95 and 73.7% in Create P95, while Redisson wait collapsed from hundreds of milliseconds to sub-millisecond mean. These improvements cannot be accepted as a formal A/B result because the error gate failed.

## Gate failure and next bottleneck

The formal 25 VU x3 gate required zero system errors and zero timeouts. Both short diagnostics crossed the system-error threshold, so no formal after runs and no 50 VU exploration were permitted.

Backend errors were MySQL deadlocks in `seat_lock` insert/release paths, not failures of the new `activity_session` decrement. `SHOW ENGINE INNODB STATUS` captured two concurrent inserts for different seats in session `910001`, each holding an X lock on the `idx_seat_lock_unique` supremum record and waiting for an insert-intention lock on that same index page; InnoDB rolled one transaction back. At 25 VU, the stock row also became visible as secondary contention (`db_stock_update` mean 17.886 ms, max 373.899 ms; Hikari pending peak 7), but it was not the error source.

- Atomic stock decrement: `ATOMIC_STOCK_DECREMENT_CONFIRMED`.
- End-to-end seat-lock optimization: `SEAT_LEVEL_LOCK_OPTIMIZATION_INSUFFICIENT` for formal benchmark acceptance.
- Next bottleneck: `SEAT_LOCK_UNIQUE_INDEX_INSERT_GAP_DEADLOCK` in the lock/selection lifecycle.
- Formal after 25 VU x3: not run because the short-diagnostic gate failed.
- 50 VU exploratory: not run because 25 VU did not satisfy the gate.

The post-failure reset safely drained the workload: expected events `1160`, actual events `1160`, missing consumed `0`, orphan events `0`, final pending/processing/failed `0`, time-to-zero `0.917 s`.

## Phase 6C-3C-2: seat-lock unique-index deadlock remediation

### Reconstructed deadlock graph

The retained InnoDB graph at `2026-09-22 15:52:16` showed two transactions in the same session:

- Transaction A executed `INSERT INTO seat_lock ... VALUES (910001, 4, 16, ...)`. It held an X record/gap lock on the `idx_seat_lock_unique` supremum record and waited for an X insert-intention lock on that same supremum gap.
- Transaction B executed `INSERT INTO seat_lock ... VALUES (910001, 4, 17, ...)`. It also held an X record/gap lock on the same unique-index supremum and waited for an X insert-intention lock there. InnoDB rolled back Transaction B.

Code order and `EXPLAIN` completed the graph: each transaction first executed target-seat expired-row `DELETE`, then the old user-selection `DELETE`, then a consistent active-lock read and finally `INSERT`. Both DELETE statements selected `idx_seat_lock_unique`; the user-selection DELETE could only use its `schedule_id` prefix. Under `REPEATABLE-READ`, missing-row range writes established gap/next-key locks before the later inserts, allowing different logical seats in the same terminal index gap to deadlock.

### Persistence model

The unique index remains unchanged: `idx_seat_lock_unique(schedule_id,row_num,col_num)`.

The hot path now uses:

1. Plain state read by the full unique key.
2. Existing `expired + unbound` or released row: guarded primary-key UPDATE of owner, token, expiry and status.
3. Active or order-bound row: business conflict.
4. Missing row: direct INSERT without a preceding range DELETE/UPDATE.
5. Duplicate-key race: one current `FOR UPDATE` reread and at most one guarded reclaim; otherwise business conflict.

Old selections are marked released by primary key. The selection and canonical seat locks now enclose the complete `TransactionTemplate`, including commit, so another replacement cannot acquire Redis locks while the previous database transaction remains uncommitted. The independent unlock and low-frequency global cleanup capabilities remain available.

### Correctness and deadlock reproduction

| Check | Result |
|---|---|
| Missing seat insert | PASS |
| Active seat conflict | PASS |
| Expired unbound row reclaim | PASS, same row id and row count=1 |
| Expired bound row | PASS, cannot reclaim |
| Duplicate-key race | PASS, one reread/reclaim maximum |
| Same-seat 8-way | PASS, one owner, DB row count=1, system error=0 |
| Different seats 8/16/25 | PASS, all successful |
| 25-way deadlock reproduction | PASS, 3/3 rounds, deadlock=0 |
| Full Order Create correctness regression | PASS |
| Oversell / duplicate order / inventory | 0 / 0 / consistent |

After all correctness and benchmark runs, backend logs contained zero new deadlocks. `SHOW ENGINE INNODB STATUS` still reported the old `15:52:16` event as the latest detected deadlock, confirming no newer `idx_seat_lock_unique` supremum/insert-intention cycle.

### Short diagnostic gate

| VU | TPS | Transaction P50/P95/P99 ms | Lock P95 | Create P95 | Payment P95 | Redisson mean/max ms | Stock mean/max ms | Hikari active/pending | CPU peak/median | Errors |
|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 10 | 34.901 | 237.5 / 453.1 / 720.05 | 51.0 | 262.0 | 161.05 | 0.563 / 4.010 | 122.756 / 314.683 | 11 / 0 | 22.994% / 14.464% | 0 |
| 25 | 27.358 | 855.0 / 1201.35 / 1894.55 | 134.75 | 665.0 | 360.95 | 1.032 / 19.979 | 373.361 / 917.160 | 20 / 7 | 15.079% / 7.570% | 0 |

Both short runs had system/network/parse/timeout errors equal to zero and passed the formal-run gate.

### Formal 25 VU after runs

Each run used 30 seconds warmup and 60 seconds measurement.

| Run | TPS | Transaction P50/P95/P99 ms | Lock P95 | Create P95 | Payment P95 | Redisson mean/max ms | Stock mean/max ms | CPU peak/median | Hikari active/pending |
|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 421 | 30.423 | 842 / 967 / 1041.70 | 112 | 646.70 | 273.0 | 0.431 / 7.980 | 393.232 / 1103.317 | 10.593% / 4.092% | 20 / 7 |
| 422 | 31.636 | 812 / 902 / 969.00 | 106 | 672.65 | 249.0 | 0.342 / 7.161 | 411.313 / 985.699 | 6.898% / 4.087% | 20 / 7 |
| 423 | 30.065 | 862 / 1010 / 1092.78 | 116 | 729.90 | 226.90 | 0.409 / 12.434 | 373.987 / 982.197 | 12.632% / 4.341% | 20 / 7 |
| Median | 30.423 | 842 / 967 / 1041.70 | 112 | 672.65 | 249.0 | 0.409 / 7.980 | 393.232 / 985.699 | — | 20 / 7 |

TPS range was `30.065–31.636`, or 5.2% of the median. All formal runs had system/network/parse/timeout errors and deadlocks equal to zero. Outbox missing/orphan/failed counts were zero in every run; all events drained safely. Peak pending was `2679/2903/2712` and time-to-zero was `124.017/115.350/108.687 s`.

Against the Phase 6C-3A median, TPS improved from `19.551` to `30.423` (+55.6%), transaction P95 from `1824.90` to `967.00 ms` (-47.0%), P99 from `2456.14` to `1041.70 ms` (-57.6%), lock P95 from `840` to `112 ms` (-86.7%), and Create P95 from `1039.90` to `672.65 ms` (-35.3%). The P99 target was met, but the P95 target below 800 ms was not, so 50 VU exploration was not run.

Final classification:

- `SEAT_LOCK_DEADLOCK_FIXED`
- `SEAT_LEVEL_LOCK_OPTIMIZATION_CONFIRMED`
- Next bottleneck: `SESSION_STOCK_ROW_CANDIDATE`. Formal stock-update mean was about `374–411 ms` with maxima near one second; Hikari active/pending peaks of `20/7` are consistent with connections waiting behind the serialized session stock row while CPU remained low.
