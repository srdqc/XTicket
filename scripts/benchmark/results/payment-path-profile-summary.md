# Phase 6C-6 Payment Path Profile

## Scope

- Branch: `feat/observability-benchmark`
- PRE_PROFILE_COMMIT: `e2bde48247eb3de0a55e105d54e2b48a1199e1e7`
- Classification: **LOCAL BENCHMARK — NOT PRODUCTION CAPACITY**.
- This phase added profiling only. It did not change payment order, seat, stock, Outbox, RocketMQ, Hikari, rate-limit or ticket issuance behavior.

## Real payment model and transaction

`PaymentController.pay` calls `PaymentService.payOrder`. This is a simulated points payment (`MOCK_POINTS`), not WeChat, Alipay or a third-party callback flow. `payOrder` has `@Transactional(rollbackFor=Exception.class, timeout=8)` and acquires the per-order Redisson lock inside that transaction.

The successful call sequence is:

1. acquire `pay:{orderNo}` bounded Redisson lock;
2. load the caller-owned `ticket_order` and validate PENDING/not expired;
3. load bound `seat_lock` rows and validate count/status;
4. load `sys_user`, then atomically debit points with `points = points - amount WHERE points >= amount`;
5. CAS `ticket_order` from PENDING to PAID;
6. insert one `order_seat` per seat and mark the order's locks purchased;
7. insert one `payment_record` (`channel=MOCK_POINTS`, `status=SUCCESS`);
8. synchronously call `TicketService.issueTickets`, which joins the payment transaction;
9. insert the PAID `outbox_event` in the same transaction;
10. map the response, reload remaining points and commit.

There is no direct RocketMQ call in the request transaction. The Outbox publisher sends after commit. No Redis data operation occurs beyond the order-scoped Redisson lock and the controller's existing Redis token-bucket rate limit.

Idempotency is protected by the per-order lock, the `ticket_order status=0` CAS, unique `payment_record(order_no)`, unique `payment_record(payment_no)` and unique `electronic_ticket(order_seat_id)`. Ticket issuance also reloads the order `FOR UPDATE` and checks existing tickets. Delivery remains at-least-once plus consumer idempotency.

## SQL accounting

On the no-conflict successful path, for N seats:

- SELECT: `8 + N` (order, locks, user, ticket order FOR UPDATE, order seats, existing tickets, per-seat existing-ticket checks, ticket view, updated user);
- INSERT: `2N + 2` (N order seats, N tickets, payment record, Outbox);
- UPDATE: 3 (points, order status, seat locks);
- DELETE: 0;
- total: `13 + 3N`.

| Seats | SELECT | INSERT | UPDATE | DELETE | Total SQL |
|---:|---:|---:|---:|---:|---:|
| 1 | 9 | 4 | 3 | 0 | 16 |
| 3 | 11 | 8 | 3 | 0 | 22 |
| 6 | 14 | 14 | 3 | 0 | 31 |

The product and DTO limit is six seats. Each seat produces one `order_seat` and one `electronic_ticket`.

## Instrumentation

Fixed low-cardinality timers were added for idempotency/lock acquisition, order load, seat-lock load, points debit, order transition, order-seat confirmation, payment-record write, ticket issuance, Outbox insert, response mapping and transaction completion. `payment_tx_completion` starts after the final business SQL and is recorded by transaction synchronization after the database commit. No order number, user ID, event ID or trace ID is used as a tag.

## Payment-only benchmark

Each measurement used independent legal PENDING orders. Warmup used a separate fixture and was cleaned before measurement. Different-user results are the capacity-relevant baseline; same-user is hotspot profiling only. Requests use the unchanged 0.5-second pacing and production rate limiter.

| Run | Mode | VU | Seats | Duration | Success/TPS | P50/P95/P99 ms | Rate limited | System/timeout |
|---:|---|---:|---:|---:|---:|---:|---:|---:|
| 671 | different-users | 1 | 1 | 15s | 27 / 1.790 | 46 / 95.2 / 293.26 | 0 | 0 / 0 |
| 672 | different-users | 10 | 1 | 30s | 570 / 18.681 | 21 / 55.55 / 397.61 | 0 | 0 / 0 |
| 673 | different-users | 25 | 1 | 30s | 1207 / 39.543 | 49 / 322.7 / 787.74 | 0 | 0 / 0 |
| 674 | same-user | 10 | 1 | 15s | 34 / 2.214 | 6 / 43.2 / 400.4 | 256 | 0 / 0 |
| 675 | same-user | 25 | 1 | 15s | 34 / 2.240 | 7 / 120.8 / 215 | 691 | 0 / 0 |
| 676 | different-users | 1 | 3 | 10s | 18 / 1.793 | 42 / 126.95 / 244.59 | 0 | 0 / 0 |
| 677 | different-users | 1 | 6 | 10s | 18 / 1.769 | 47 / 122.0 / 271.6 | 0 | 0 / 0 |

The same-user HTTP percentiles include rate-limit rejections and therefore are not capacity percentiles. The successful calls still show points-debit P95 of 176.128ms at 10 VU and 134.185ms at 25 VU, versus 2.458ms/2.589ms for corresponding different-user runs. This is `USER_POINTS_ROW_CONTENTION`, amplified and bounded by the existing per-user payment limiter. It is not the formal different-user bottleneck.

### 25 VU different-user stage profile

| Stage | Mean ms | P95 ms | Max ms |
|---|---:|---:|---:|
| payment_idempotency | 0.614 | 1.106 | 22.614 |
| payment_order_load | 1.376 | 2.867 | 17.378 |
| payment_seat_lock_load | 1.151 | 1.999 | 6.992 |
| payment_points_debit | 1.243 | 2.589 | 25.466 |
| payment_order_transition | 0.746 | 1.425 | 33.051 |
| payment_order_seat | 87.222 | 285.147 | 592.724 |
| payment_record_write | 1.219 | 2.736 | 24.632 |
| payment_ticket_issue | 3.945 | 8.323 | 42.889 |
| payment_outbox_insert | 1.050 | 2.474 | 28.302 |
| payment_response_mapping | 0.514 | 1.171 | 4.815 |
| payment_tx_completion | 13.682 | 29.229 | 208.619 |

`payment_order_seat` is the dominant different-user stage. It contains the per-seat `order_seat` insert(s) followed by the order's `seat_lock` status update. All orders used different seats and users, while sharing benchmark sessions. The evidence supports `PRIMARY_BOTTLENECK=ORDER_SEAT_WRITE`; it does not justify changing SQL in this profiling phase.

Ticket issue P95 for 1/3/6 seats was 28.180/39.584/41.419ms, with means 9.043/14.320/17.932ms. Cost increased with seat count but was neither near-linear at P95 nor dominant. End-to-end Payment P95 was 95.2/126.95/122.0ms, also not linear in this small local sample.

## Runtime evidence

For 25 VU different-user Payment-only, backend CPU peak/median was 14.735%/3.860%, heap peak 92,216,352 bytes, GC delta 35, and Hikari active/pending peak was 17/0. This does not support Hikari wait as the Payment-only bottleneck.

The short full transaction correlation run (25 VU, 25s) produced 1219 transactions at 48.284 TPS. Full transaction P50/P95/P99 was 475/676/1387.68ms; Lock/Create/Payment P95 was 82/126/508ms. Payment represented 75.1% of full-transaction P95 and remained the dominant request stage. In this combined workload Hikari active/pending reached 20/7, but the Payment-only baseline had no pending connections, so pool sizing is not selected as the Payment-path primary variable.

## Correctness

- Eight concurrent payment attempts against one order produced exactly one successful debit, one payment record, one PAID state, one ticket and one PAID event. Final points were 499 from 500 for the one-point order.
- `pay → refund` passed: points returned once, one refund record, order REFUNDED and ticket INVALIDATED.
- `pay → ticket → check-in` passed: ticket became USED; subsequent refund was rejected without restoring points.
- All benchmark runs had system errors, network errors, parse errors and timeouts equal to zero.

## Classification

- `PRIMARY_BOTTLENECK=ORDER_SEAT_WRITE`
- `SECONDARY_BOTTLENECK=USER_POINTS_ROW_CONTENTION` for same-user/shared-account traffic only.
- `NEXT_OPTIMIZATION_CANDIDATE=PAYMENT_ORDER_SEAT_WRITE_PATH`
- Synchronous ticket issuance, Outbox insert, payment-record write and order transition are not supported as primary bottlenecks by this profile.
- The next phase should isolate `order_seat` insert time from `seat_lock` purchased-transition time and inspect their SQL/index lock evidence before selecting one implementation change.
