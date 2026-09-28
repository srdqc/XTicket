# Phase 6C-7C Seat-Lock `order_no` Index Experiment

## Scope and result

- Benchmark commit: `38c38599dd972fb379b533bf2d304ad35616c2fa`
- Variable under test: `idx_seat_lock_order(order_no)` only.
- Removed candidate: `idx_seat_lock_order_status(order_no, status)`.
- Environment: local benchmark; client and server share the same host. This is not production-capacity evidence.
- Final result: `SEAT_LOCK_ORDER_INDEX_INSUFFICIENT`.
- Reason: the index removed the 7B composite-index deadlock pattern and materially improved the payment update, but one of three formal full-transaction runs required 132.076 seconds to drain the Outbox, exceeding the 120-second reliability gate. The formal experiment therefore cannot confirm the production change.

## 7B deadlock evidence

The run742 InnoDB evidence showed a cycle involving the composite index introduced by 7B:

1. Payment updated `seat_lock.status` from 1 to 2 while filtering by `order_no` and `status`. It held an X gap lock in `idx_seat_lock_order_status` and waited for an insert-intention lock on `idx_seat_lock_user`.
2. Concurrent order-create seat-lock binding held an X lock on `idx_seat_lock_user` and waited for an insert-intention lock on `idx_seat_lock_order_status`.

Because `status` was part of the 7B index key, payment changed the composite secondary-index key during the update. An `order_no`-only index does not change its key on the 1-to-2 status transition, so the evidence supported this narrower experiment rather than contradicting it.

## EXPLAIN

Query under test:

```sql
UPDATE seat_lock
SET status = 2, update_time = ?
WHERE order_no = ? AND status = 1;
```

| State | type | key | key_len | rows | Extra |
|---|---:|---|---:|---:|---|
| Before, no `order_no` index | `index` | `PRIMARY` | - | 33 | `Using where` |
| After, `idx_seat_lock_order(order_no)` | `range` | `idx_seat_lock_order` | 259 | 1 | `Using where` |

`status=1` remains a residual filter. No `FORCE INDEX` was used.

## Quick gate

Quick passed. Payment-only run751 and full-transaction run752 had zero deadlocks, system errors, timeouts, network errors, and parse errors. The additional 3-seat and 6-seat payment probes also completed without those failures.

## Formal payment-only, 25 VU

| Run | TPS | P50 ms | P95 ms | P99 ms | UPDATE P95 ms | Row-lock waits | Row-lock wait ms | Hikari active/pending peak |
|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 761 | 46.283 | 34 | 91 | 216.24 | 1.688 | 0 | 0 | 19 / 9 |
| 762 | 46.733 | 26 | 71.85 | 246.94 | 1.753 | 0 | 0 | 2 / 0 |
| 763 | 47.100 | 24 | 65 | 164.75 | 1.556 | 0 | 0 | 11 / 0 |
| Median | **46.733** | - | **71.85** | - | **1.688** | **0** | **0** | **11 / 0** |

Reference before the index: payment UPDATE P95 117.408 ms, payment P95 165 ms, and 519 row-lock waits totaling 14,153 ms. The 7B composite-index experiment reached about 10.994 ms median UPDATE P95 but was rejected because it introduced the run742 deadlock.

All three 7C payment-only runs had zero deadlocks, system errors, timeouts, network errors, parse errors, and business conflicts.

## Formal full transaction, 25 VU

| Run | TPS | P50 ms | P95 ms | P99 ms | UPDATE P95 ms | Row-lock waits | Row-lock wait ms | Hikari active/pending peak |
|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 771 | 76.833 | 290 | 511 | 687 | 12.567 | 4,976 | 563,480 | 20 / 6 |
| 772 | 50.633 | 473 | 664 | 793.67 | 17.809 | 3,336 | 866,714 | 20 / 7 |
| 773 | 69.133 | 335 | 524 | 688.53 | 11.518 | 4,443 | 586,930 | 20 / 7 |
| Median | **69.133** | **335** | **524** | **688.53** | **12.567** | **4,443** | **586,930** | **20 / 7** |

All three runs had zero deadlocks, system errors, timeouts, network errors, and parse errors. Each run recorded 15 expected business conflicts.

## Outbox reliability gate

| Run | Measurement-end open | 30s open | Time to zero | Final pending/processing/failed | Missing/orphan |
|---:|---:|---:|---:|---|---|
| 771 | 7,297 | 0 | 74.358s | 0 / 0 / 0 | 0 / 0 |
| 772 | 5,040 | 1,842 | 59.477s | 0 / 0 / 0 | 0 / 0 |
| 773 | 6,611 | 2,908 | **132.076s** | 0 / 0 / 0 | 0 / 0 |

`OUTBOX_DRAIN_THROUGHPUT_RELIABILITY_GATE`: **FAIL**. Run773 exceeded the 120-second cleanup safety threshold, although it eventually drained to zero without failed, missing, or orphan events. Formal data is retained as failure evidence and is not promoted to a confirmed production optimization.

## Classification

- The single-column index achieves its intended access path and avoids the reproduced 7B composite-index deadlock mechanism.
- Payment-only evidence is strong: median UPDATE P95 fell from 117.408 ms to 1.688 ms, with row-lock waits falling from 519 / 14,153 ms to 0 / 0 ms.
- Full transactions remain dominated by broader row-lock contention and Outbox drain throughput variability. Hikari reaches 20 active connections with 6-7 pending in all formal full-transaction runs, but the large InnoDB row-lock wait totals and Outbox backlog are the more direct evidence.
- Next bottleneck: full-transaction InnoDB row-lock contention and the resulting Outbox drain reliability under sustained load; the experiment does not justify Hikari tuning or any prohibited transaction/outbox change.

Final conclusion: `SEAT_LOCK_ORDER_INDEX_INSUFFICIENT`.
