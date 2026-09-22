# Phase 6C-3B Order-Create Session Lock Scope

> LOCAL DIAGNOSTIC — NOT PRODUCTION CAPACITY

## Scope and decision

- Branch: `feat/observability-benchmark`
- HEAD / A/B code base: `486dae92afbab49ebe7c100612e3d6cfe90498a6` plus the uncommitted Phase 6C-3B working-tree change
- Workload: `transaction-flow`, fixed session `910001`, one seat per order
- Short gate: 10 VU × 2 and 25 VU × 2, 25 seconds, no warmup
- Decision: `LOCK_SCOPE_OPTIMIZATION_INSUFFICIENT`
- The 25 VU gate regressed, so the conditional 25 VU ×3 formal run was not executed.

## Correctness audit and implemented boundary

The original session-level Redisson lock contained session read, expired-lock cleanup, optional missing-lock creation, lock-token idempotency, active-lock verification, trusted snapshot read, Redis stock pre-deduct, DB stock/version CAS, order insert, seat-lock bind, cache refresh, transactional Outbox insert, response mapping and transaction commit.

The attempted narrowed path moved request-seat normalization, session read/status validation and expired-lock cleanup before lock acquisition. Cache refresh moved after transaction commit and after Redisson release, and runs only for a newly created order. Response enrichment was already outside the service lock.

The lock still contains optional missing-lock creation, lock-token idempotency, current lock ownership validation, trusted snapshot/status validation, Redis stock pre-deduct, DB stock CAS, order insert, seat-lock bind, Outbox insert, response mapping and transaction completion. DB stock CAS, unique constraints, lock-token semantics and the single required DB transaction were not changed. The order insert, seat-lock bind and Outbox insert remain atomic in one `TransactionTemplate` transaction.

Safety basis for the moved work:

- Session pre-read is a fast availability guard; the in-lock trusted snapshot query still revalidates current sale status before a new order mutates stock.
- Expired rows are ignored by active-lock queries. Physical cleanup is correctness-neutral for the supplied-lock-token path, although the diagnostic shows that concurrently executing the cleanup DELETE is not performance-neutral.
- Cache refresh is best effort and now observes committed DB state. It is outside both the DB transaction and Redisson lock.
- Request normalization is pure in-memory validation.

The internal `ordercreateprofile` actuator bean is conditional on `maoyan.profiling.order-create.enabled=true`. Docker defaults the property to `false`; the endpoint was explicitly enabled only for measurements and returned HTTP 404 after restoring the default environment.

## Correctness results

| Check | Result |
|---|---|
| 8-way same lockToken create | PASS: 8 successful idempotent responses, 1 order, 1 CREATED Outbox event |
| 8-way same seat create | PASS: exactly 1 order |
| 8-way same-session different-seat create | PASS: 8 orders, DB/Redis stock delta 8 |
| Eight different sessions in parallel | PASS: 8 orders, aggregate DB stock delta 8 |
| Expired lock | PASS: create rejected, 0 orders |
| Insufficient stock | PASS: create rejected, 0 orders, non-negative DB/Redis stock |
| Duplicate orders | 0 |
| Oversell | 0 |
| Inventory consistency | PASS |
| Outbox failure after order insert | PASS: transaction manager rollback invoked, Redis pre-deduct compensated, post-commit cache refresh skipped |

The focused PowerShell validation is `scripts/api-order-create-lock-scope-validation.ps1`. It is Windows PowerShell 5.1 parse-clean and UTF-8 with BOM.

## Short A/B results

All latency values are milliseconds. TPS uses the k6 `transaction_success.rate` metric.

| VU / phase / run | TPS | Transaction P50/P95/P99 | Create P50/P95/P99 | system/network/timeout/parse | CPU peak/median % | Hikari active/pending peak |
|---|---:|---:|---:|---:|---:|---:|
| 10 / before / 1 | 28.98 | 294.5 / 609.75 / 1026.91 | 146 / 335.90 / 520.73 | 0 / 0 / 0 / 0 | 16.09 / 10.48 | 9 / 2 |
| 10 / before / 2 | 29.39 | 297 / 655.95 / 1043.00 | 143 / 350.95 / 491.85 | 0 / 0 / 0 / 0 | 15.54 / 10.31 | 7 / 0 |
| 10 / after / 1 | 30.38 | 288 / 622.80 / 908.70 | 149 / 357.10 / 564.36 | 0 / 0 / 0 / 0 | 17.04 / 11.83 | 6 / 0 |
| 10 / after / 2 | 31.91 | 270 / 609.00 / 956.88 | 143 / 290.00 / 445.25 | 0 / 0 / 0 / 0 | 17.02 / 11.28 | 8 / 0 |
| 25 / before / 1 | 23.46 | 844.5 / 1777.55 / 2507.70 | 425 / 1087.10 / 1710.09 | 0 / 0 / 0 / 0 | 14.17 / 10.99 | 20 / 2 |
| 25 / before / 2 | 27.26 | 759 / 1906.10 / 2908.64 | 392 / 1125.90 / 1628.42 | 0 / 0 / 0 / 0 | 13.85 / 9.53 | 17 / 0 |
| 25 / after / 1 | 23.45 | 937.5 / 2190.45 / 2789.05 | 507 / 1301.00 / 1708.49 | 0 / 0 / 0 / 0 | 17.00 / 13.32 | 16 / 0 |
| 25 / after / 2 | 20.04 | 1113 / 2490.25 / 2977.24 | 610 / 1406.15 / 2018.74 | 0 / 0 / 0 / 0 | 18.39 / 13.63 | 18 / 0 |

At 10 VU, median-of-two Create P95 changed from 343.43 to 323.55 ms (5.8% lower) and TPS rose 6.7%. At 25 VU, median-of-two Create P95 changed from 1106.50 to 1353.58 ms (22.3% worse), Transaction P95 changed from 1841.83 to 2340.35 ms (27.1% worse), and TPS fell 14.3%.

## Internal stage evidence

| VU / after run | Redisson wait P50/P95/P99 | cleanup P95 | bind-locks P95/P99 | tx completion P95 | cache refresh P95 |
|---|---:|---:|---:|---:|---:|
| 25 / 1 | 402.64 / 1207.94 / 1811.92 | 27.25 | 15.19 / 18.86 | 6.75 | 3.90 |
| 25 / 2 | 486.52 / 1275.05 / 1811.92 | 31.44 | 16.74 / 20.94 | 7.73 | 4.69 |

Before the change, 25 VU Redisson wait P95 was 872.41 / 771.74 ms, cleanup P95 was 15.71 / 14.66 ms, and bind-locks P95 was 1.29 / 1.03 ms. After the change, median Redisson wait P95 regressed from 822.08 to 1241.50 ms (51.0% worse).

The 10 VU endpoint snapshots were read only after Outbox drain. Their Micrometer percentile reservoirs had rotated to zero despite non-zero counts, means and maxima, so the 10 VU after Redisson P95 values are invalid and are not presented as real zero latency.

The evidence is consistent with the formerly serialized unindexed expired-lock DELETE now overlapping across requests with transactional seat-lock updates. At 25 VU its P95 rose to 27–31 ms and bind-lock P95 rose to 15–17 ms. This inference explains why the logical lock scope became smaller while the measured lock queue became worse. CPU remained below 19% and Hikari pending stayed zero in both after rounds, so neither CPU saturation nor pool waiting explains the regression.

## Gate

The required joint success conditions were not met: 25 VU Redisson wait P95, Create P95, Transaction P95 and TPS all moved in the wrong direction. Correctness, system errors, network errors and timeouts remained clean, but the performance gate failed. Per the phase stop rule, no second optimization was attempted and no formal 25 VU ×3 run was performed. The 25 VU engineering target (`Transaction P95 <800 ms`, `P99 <1.2 s`) was not met.

Final classification: `LOCK_SCOPE_OPTIMIZATION_INSUFFICIENT`.
