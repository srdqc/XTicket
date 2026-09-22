# Phase 6C-4 Order Create Stock Row Summary

## Scope

- Branch: `feat/observability-benchmark`
- PRE_OPT_COMMIT: `bc5855d40ca1663beda28dcbce337f74afcbd426`
- Environment: local Docker benchmark with client and server on the same host.
- Classification: **LOCAL BENCHMARK — NOT PRODUCTION CAPACITY**.
- Single optimization variable: move the existing atomic `activity_session` stock decrement later inside the existing `TransactionTemplate`.
- Cache placement, Redis debit/compensation, seat-level Redisson locks, transaction isolation, Hikari, Outbox publisher, RocketMQ, SQL and indexes were not changed.

## Transaction and lock audit

Before the optimization, the transaction callback executed:

1. Load `activity_session`.
2. Create a missing seat lock only when no lock token was supplied.
3. Check lock-token idempotency.
4. Read and validate active `seat_lock` rows.
5. Load the immutable order snapshot source.
6. Pre-debit Redis stock once.
7. Atomically decrement database stock.
8. Insert `ticket_order`.
9. Bind `seat_lock` rows to the order.
10. Refresh the session-detail cache.
11. Insert the CREATED Outbox event.
12. Map the response and return from the callback.
13. Commit the database transaction.

After the optimization, steps 7-11 are ordered as `ticket_order insert -> seat_lock bind -> Outbox insert -> activity_session stock decrement -> cache refresh`. Response mapping and commit remain last.

| Stage | Table / system | Lock or effect |
|---|---|---|
| session, idempotency, lock and snapshot reads | `activity_session`, `ticket_order`, `seat_lock` | consistent reads; no new write lock |
| Redis pre-debit | Redis | one debit per create; outside the database fact source |
| order insert | `ticket_order` | inserted row and unique-key protection held to commit |
| seat bind | `seat_lock` | target seat rows updated and held to commit |
| Outbox append | `outbox_event` | inserted event row held to commit |
| atomic stock decrement | `activity_session` | primary-key row X lock held to commit |
| cache refresh | Redis plus an `activity_session` read | remains inside the callback and after stock decrement so it observes the decremented value |

The database stock decrement, order insert, seat bind and Outbox insert remain in one transaction. Any later failure rolls the entire transaction back. Redis debit remains at most once and its outer failure path compensates at most once.

## Baseline before moving the stock update

Both diagnostics used a 25-second measurement-only run. The 10 VU profile started from a fresh backend process. The 25 VU Actuator snapshot was cumulative with the preceding 10 VU run; its k6 and server-sampler metrics are per-run, while its stage percentiles are explicitly retained as cumulative diagnostic evidence rather than represented as isolated percentiles.

| VU | TPS | Transaction P50/P95/P99 ms | Create P95 ms | Stock mean/max ms | Post-stock P50/P95/max ms | Hikari active/pending | CPU peak/median | Errors |
|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 10 | 41.177 | 227 / 329.20 / 497.66 | 143.00 | 60.331 / 382.730 | 22.544 / 37.224 / 66.461 | 10 / 0 | 21.231% / 16.012% | 0 |
| 25 | 34.283 | 848 / 1035.00 / 1086.68 | 750.70 | 210.229 / 668.820 cumulative | 29.884 / 49.807 / 73.980 cumulative | 20 / 7 | 9.045% / 5.555% | 0 |

The work after a successful stock decrement was `ticket_order insert`, `seat_lock bind`, cache refresh, Outbox insert, response mapping and transaction commit. At 25 VU the profile attributed meaningful time to binding and transaction completion while the same `activity_session` row lock remained held.

## Correctness

`clean package` passed after the final change. The rebuilt backend reached the profiling endpoint successfully.

| Check | Result |
|---|---|
| same lockToken, 8 concurrent creates | PASS: one order and one CREATED event |
| same seat, 8 users | PASS: one order |
| same session, different seats | PASS: 8/8, 16/16 and 25/25 |
| different sessions | PASS: 8/8 |
| reverse-order multi-seat contention | PASS |
| multi-seat create | PASS |
| expired target reclaim / expired token rejection | PASS |
| concurrent selection replacement | PASS |
| injected Outbox failure | PASS: DB transaction rollback; Redis compensated once; stock UPDATE not reached |
| insufficient DB stock after prior writes | PASS: transaction rollback, no residual order/binding/CREATED event, Redis compensated once |
| deadlock / oversell / duplicate order | 0 / 0 / 0 |
| inventory | consistent |

## Short diagnostics after moving the stock update

Each run used a fresh backend process for an isolated profile and a 25-second measurement-only window.

| VU | TPS | Transaction P50/P95/P99 ms | Lock/Create/Payment P95 ms | Stock mean/max ms | Post-stock P50/P95/max ms | Hikari active/pending | CPU peak/median | Errors |
|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 10 | 32.763 | 223 / 596.60 / 822.58 | 58 / 71 / 465 | 2.863 / 165.468 | 6.160 / 19.792 / 27.522 | 12 / 0 | 14.850% / 11.745% | 0 |
| 25 | 44.740 | 509 / 761.00 / 1381.10 | 85 / 138 / 562 | 4.809 / 236.698 | 5.898 / 9.830 / 30.990 | 20 / 7 | 21.983% / 15.547% | 0 |

At 25 VU, the stock mean fell from the cumulative baseline snapshot of 210.229 ms to 4.809 ms and post-stock P95 fell from 49.807 ms to 9.830 ms. The short-run transaction P95 improved from 1035 ms to 761 ms. This confirms that holding the stock row across order insert, seat binding and Outbox insert was material. The 25 VU P99 remained above 1.2 seconds in the short run.

## Formal 25 VU gate

**Formal A/B incomplete: `OUTBOX_DRAIN_THROUGHPUT_RELIABILITY_GATE`.**

Run 660 is retained only as an incomplete diagnostic observation, not as formal final A/B data.

The first formal run used 30 seconds warmup plus 60 seconds measurement.

| Run | TPS | Transaction P50/P95/P99 ms | Lock/Create/Payment P95 ms | Stock mean/max ms | Post-stock P50/P95/max ms | Hikari active/pending | CPU peak/median | Errors |
|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 660 | 51.075 | 409 / 978.55 / 1044.00 | 104 / 157 / 739 | 2.690 / 71.205 | 4.850 / 16.122 / 48.871 | 20 / 7 | 10.566% / 8.448% | 0 |

The measurement itself had `system_error=0`, `network_error=0`, `timeout=0`, `parse_error=0`, and no observed deadlock. However, the Outbox cleanup safety gate failed after 120 seconds with `pending=2230`, `processing=0`, `failed=0`, and `missingConsumed=0`; cleanup had not started and no unconsumed event was deleted. The events later drained naturally. A subsequent safe reset verified `expectedEvents=6180`, `actualEvents=6180`, `missingConsumed=0`, `orphanEvents=0`, and final pending/processing/failed all zero.

Per the phase stop rule, formal runs 2 and 3 and the 50 VU exploration were not executed. Formal TPS variance therefore cannot be calculated. Run 660 is `RELIABILITY_INVALID` and is not a three-run performance acceptance result.

## Classification

- Hot-row lock-hold-tail reduction: confirmed directionally (`post_stock_to_tx_end` P95 49.807 ms cumulative baseline to 9.830 ms short-run at 25 VU).
- `SESSION_STOCK_ROW_NOT_CONFIRMED`: after the move, stock UPDATE mean was 4.809 ms in the isolated short run and 2.690 ms in formal run 660; it was no longer the dominant wait.
- `HIKARI_POOL_CANDIDATE`: active/pending remained 20/7 after stock latency collapsed, but no pool change is justified in this stopped phase.
- Engineering gate: short 25 VU P95 passed 800 ms, but P99 failed 1.2 seconds; formal run 660 P95 failed 800 ms while P99 passed 1.2 seconds.
- Next bottleneck: `OUTBOX_DRAIN_THROUGHPUT_RELIABILITY_GATE`; within request latency, the payment path is the next latency candidate (`Payment P95=739 ms` in formal run 660).
- Final phase status: `RELIABILITY_INVALID`; stop without further tuning.

Suggested commits:

1. `perf(order): minimize session stock row lock hold time`
2. `perf(benchmark): record stock row lock hold experiment`
