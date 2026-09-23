# Phase 6C-5 Outbox Drain Throughput Summary

## Scope

- Branch: `feat/observability-benchmark`
- PRE_OPT_COMMIT: `160b521bd664dd247b289c56a9549c441c5918c7`
- Environment: local Docker benchmark with client and server on the same host.
- Classification: **LOCAL BENCHMARK — NOT PRODUCTION CAPACITY**.
- Order Create, seat locks, atomic stock, payment, Hikari sizing and RocketMQ broker configuration were not changed.

## Publisher audit

The real path is business transaction plus `outbox_event` insert, followed by `OutboxEventPublisher`, candidate selection, CAS claim, RocketMQ send and status update. The business row and Outbox event remain atomic in the originating database transaction.

Before optimization the publisher used one scheduler thread with `fixedDelay=1000 ms`. Each invocation selected at most 50 rows, then processed each row serially as `claim UPDATE -> RocketMQTemplate.syncSend(timeout=1000 ms) -> markPublished UPDATE`. A failed send used `markFailed`, incremented retry count and applied bounded exponential backoff. A PROCESSING row older than 30 seconds was eligible for stale recovery. The consumer used RocketMQ's default concurrent listener model and `consumed_event(consumer_group,event_id)` idempotency.

For N successfully published events, the normal database work was approximately `ceil(N/50)` candidate SELECTs, N claim UPDATEs and N mark UPDATEs: two UPDATEs per event plus batch SELECTs. The delivery contract remains at-least-once plus consumer idempotency.

## Profiling

Low-cardinality metrics were added for poll duration, selected count, claim duration/success/conflict, synchronous send duration, mark-published duration, batch duration and published count per batch. No event ID, order number or trace ID is used as a metric tag.

### Baseline fixed-event drain

All fixtures inserted real PENDING Outbox rows and required both PUBLISHED state and a matching `consumed_event` row before cleanup.

| Events | Drain seconds | Events/s | Poll total/mean ms | Claim total/mean ms | Send total/mean ms | Mark total/mean ms | Batch count/total ms |
|---:|---:|---:|---:|---:|---:|---:|---:|
| 100 | 3.394 | 29.464 | 25.247 / 4.208 | 449.792 / 4.498 | 254.838 / 2.548 | 552.157 / 5.522 | 6 / 1371.974 |
| 500 | 16.293 | 30.688 | 72.979 / 4.561 | 2085.937 / 4.172 | 864.208 / 1.728 | 2392.850 / 4.786 | 16 / 5567.203 |
| 1000 | 29.124 | 34.336 | 65.859 / 2.634 | 3581.159 / 3.581 | 1213.359 / 1.213 | 3884.863 / 3.885 | 25 / 8940.184 |
| 3000 | 86.976 | 34.492 | 180.521 / 2.777 | 11050.362 / 3.683 | 3291.590 / 1.097 | 11614.629 / 3.872 | 65 / 26586.800 |

For 3000 events, measured publisher work occupied only 26.587 seconds of an 86.976-second drain. Sixty full batches implied about sixty additional one-second scheduler delays. Poll and synchronous MQ send were small; claim and mark database operations were the largest active work, but the dominant elapsed-time loss was `INTER_BATCH_IDLE_GAP`.

## Single optimization variable

The publisher remains single-threaded and serial per event. The only control-flow change is that a full 50-row batch immediately selects the next batch. A result smaller than 50 returns to the scheduler and retains the normal one-second fixed delay, preventing busy-spin. Claim, syncSend, markPublished/markFailed, retry backoff and stale recovery were unchanged. Publisher concurrency remains 1.

### After fixed-event drain

| Events | Drain seconds | Events/s | Drain improvement | Throughput improvement | Claim mean ms | Send mean ms | Mark mean ms |
|---:|---:|---:|---:|---:|---:|---:|---:|
| 100 | 3.537 | 28.273 | -4.2% | -4.0% | 4.700 | 2.556 | 5.739 |
| 500 | 14.637 | 34.160 | 10.2% | 11.3% | 12.563 | 1.587 | 11.512 |
| 1000 | 26.699 | 37.455 | 8.3% | 9.1% | 12.758 | 1.279 | 11.565 |
| 3000 | 74.699 | 40.161 | 14.1% | 16.4% | 12.361 | 0.975 | 10.968 |

The optimization removed the intentional idle gaps. Under continuous drain, publisher and consumer database activity increased claim/mark latency, limiting the improvement to 14.1% drain-time reduction at 3000 events. All four sizes finished with published=consumed=count and pending/processing/failed=0.

## Reliability verification

- CREATED, PAID, CANCELLED and REFUNDED were distributed across every fixed-event fixture and all were published and consumed.
- Existing consumer duplicate-delivery tests passed; the unique consumed-event key prevents duplicate side effects.
- Publisher send-failure tests verify FAILED/retryable marking. A focused retry test verifies that a later eligible poll can publish the same stable event successfully.
- A real 100-event stale PROCESSING fixture (update time older than 30 seconds) recovered through CAS claim, published and consumed all 100 in 1.855 seconds.
- Claim conflicts were zero in the controlled fixtures. Event IDs remained stable.
- Final semantics remain at-least-once delivery plus consumer idempotency, not exactly-once delivery.

## Formal 25 VU validation

Each run used 30 seconds warmup and 60 seconds measurement. Run 660 was excluded because it did not pass the same Outbox reliability gate.

| Run | TPS | Transaction P50/P95/P99 ms | Lock/Create/Payment P95 ms | Produced/s | Published/s during measurement | End backlog | 10s | 30s | Drain seconds | Hikari active/pending | CPU peak/median | Errors |
|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 701 | 37.035 | 501.5 / 1247.45 / 1465.69 | 124 / 204 / 911.25 | 74.069 | 18.948 | 3239 | 1152 | 0 | 28.789 | 20 / 7 | 10.742% / 3.702% | 0 |
| 702 | 60.342 | 408 / 498 / 539.64 | 68 / 93 / 369 | 120.684 | 27.635 | 5458 | 3367 | 0 | 44.745 | 20 / 7 | 8.950% / 7.881% | 0 |
| 703 | 62.670 | 389 / 466 / 535 | 66 / 87 / 348 | 125.340 | 30.375 | 5563 | 3008 | 0 | 51.887 | 20 / 7 | 8.401% / 7.588% | 0 |
| Median | 60.342 | 408 / 498 / 539.64 | 68 / 93 / 369 | 120.684 | 27.635 | 5458 | 3008 | 0 | 44.745 | 20 / 7 | 8.950% / 7.588% | 0 |

Backlog was zero at 30, 60 and 120 seconds in all three runs. Final pending, processing, failed, missing and orphan counts were zero. Approximate post-measurement drain rates derived from end backlog/time-to-zero were 112.5, 122.0 and 107.2 events/s. The backlog continuously decreased and every run passed the 120-second gate.

Run-to-run throughput remained noisy: run 701 was materially slower than runs 702 and 703. The median transaction P95 was 498 ms, below 800 ms and materially better than the Phase 6C-4 comparable observation of 978.55 ms. Background publishing did not increase CPU toward saturation. Hikari remained active/pending=20/7 and is still a candidate, but pool sizing was not changed.

## Result

- `OUTBOX_DRAIN_GATE_PASS`
- `INTER_BATCH_IDLE_GAP_REMOVED`
- Missing events: 0
- Orphan events: 0
- Final failed events: 0
- Consumer duplicate side effects: 0
- Next bottleneck: `NEXT_BOTTLENECK_CANDIDATE=PAYMENT_PATH` because Payment is the dominant request stage at the formal median.
- Secondary observation: `HIKARI_POOL_CANDIDATE`; no pool change in this phase.

Suggested commits:

1. `perf(outbox): drain consecutive full batches without idle delay`
2. `perf(benchmark): record outbox drain throughput experiment`
