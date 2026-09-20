# XTicket Local Reproducible Benchmark

This harness measures a fixed synthetic workload on the local Docker stack. It is suitable for version-to-version comparisons only; it is not a production capacity claim.

## Prerequisites

- Running XTicket Docker stack with backend health `UP`.
- Windows PowerShell 5.1 for fixture scripts.
- k6. Recommended Windows installation: `winget install k6.k6` or `choco install k6`.
- Run commands from `scripts/benchmark` unless stated otherwise.

Runtime JWTs are written to `results/raw/tokens.json`. That directory is ignored and must never be committed.

## Synthetic fixture

`prepare.ps1` creates or refreshes one activity, one venue, eight sessions and 100 users by default. Reserved IDs are 900001/910001–910008/920001–920008; accounts are exactly `BENCH_USER_0001` onward. Each session exposes 100 × 200 virtual seats without materializing seat rows.

```powershell
.\prepare.ps1
```

Synthetic names are intentional: performance validity comes from fixed environment, data, requests and reset behavior, not real commercial names.

## Result classification

HTTP 200 alone is not success. `helpers.js` parses `Result.code` and classifies:

- `200`: SUCCESS
- `409`, `460`–`463`, and remaining application 4xx: BUSINESS_CONFLICT
- `429`: RATE_LIMITED
- `401`/`403`: AUTH_FAILURE
- application/HTTP 5xx: SYSTEM_ERROR
- transport failure: NETWORK_ERROR or TIMEOUT
- invalid JSON: PARSE_ERROR

Metrics are `app_success`, `business_conflict`, `rate_limited`, `auth_failure`, `system_error`, `network_error`, `timeout_error`, `parse_error`, `transaction_success`, and `transaction_failed`. Transaction profiling also records `transaction_lock_duration`, `transaction_create_duration`, `transaction_payment_duration`, and a success counter for each stage. No user, order, ticket or seat is used as a metric tag.

## Rate-limit boundary

Authenticated write limits use resource plus JWT user ID, not the shared client IP:

- seat lock: token bucket capacity 5, refill 2/s/user
- order create: token bucket capacity 10, refill 3/s/user
- payment: token bucket capacity 5, refill 2/s/user

Lock scripts deterministically partition the prepared user pool by VU and rotate only inside each VU's partition. Their default 0.5-second iteration pacing keeps account reuse below the production seat-lock refill rate through the approved 1/10/25/50 VU matrix. Same-session and different-session workloads use exactly the same assignment and pacing. RATE_LIMITED is reported separately and never hidden; a material count marks the run `WORKLOAD_RATE_LIMITED` and disqualifies the lock comparison.

The transaction workload retains global-iteration rotation across all 100 users. Its three-request transaction rate keeps per-account lock/create/payment frequency below the corresponding production limits; it does not bypass authentication or change limiter keys.

## Scenarios

| Script | Purpose |
|---|---|
| `read-activities.js` | Activity list warm-cache read |
| `read-seat-layout.js` | Fixed benchmark session layout read |
| `lock-same-session.js` | Different users/seats under one `seat:{scheduleId}` lock key |
| `lock-different-session.js` | Equivalent locks distributed over eight lock keys |
| `same-seat-contention.js` | Correctness-only contention; exactly one winner expected |
| `transaction-flow.js` | lock → create → payment; all three must succeed for one transaction |

Transaction TPS means `transaction_success / measurement seconds`. HTTP RPS still contains all three requests and must not be presented as TPS. Stage duration is measured around the corresponding complete HTTP request; full transaction duration remains lock request start through payment success response end.

## Warmup and measurement

Read workloads may run warmup immediately before measurement. Write workloads must reset after warmup because every iteration consumes a unique seat.

```powershell
k6 run -e PHASE=warmup -e VUS=10 .\scenarios\read-activities.js
k6 run -e PHASE=measurement -e VUS=10 -e DURATION=60s .\scenarios\read-activities.js

k6 run -e PHASE=warmup -e VUS=10 .\scenarios\lock-same-session.js
.\reset.ps1
k6 run -e PHASE=measurement -e VUS=10 -e DURATION=60s .\scenarios\lock-same-session.js
```

Defaults are 30s warmup and 60s measurement. Supported planned VU steps are 1, 10, 25 and 50; 100 is configurable but is not approved until Phase 6B-2 smoke and fixture-capacity review. A run must stop as invalid if unique seat capacity is exhausted.

Before `read-seat-layout.js`, issue one request to session 910001 so cold cache construction is not mixed into the warm-cache measurement.

## Correctness smoke commands

These are smoke tests, not baseline measurements:

```powershell
k6 run -e PHASE=measurement -e VUS=1 -e DURATION=3s .\scenarios\read-activities.js
.\reset.ps1
k6 run -e PHASE=measurement -e VUS=1 -e DURATION=3s .\scenarios\transaction-flow.js
.\reset.ps1
k6 run -e PHASE=measurement -e CONTENDERS=5 .\scenarios\same-seat-contention.js
.\reset.ps1
```

## Reset and Outbox safety

`reset.ps1` treats 30 seconds as an observation checkpoint, not a publication SLA. It records open rows at measurement end, 10 seconds and 30 seconds, plus peak status counts and time-to-zero. `ASYNC_BACKLOG_PRESENT` at 30 seconds is reportable but does not by itself invalidate transaction TPS or latency.

Cleanup has a bounded 120-second safety timeout. It starts only after PENDING, PROCESSING and unrecovered FAILED rows are zero, every published benchmark event has a matching `maoyan_order_consumer_group` consumption record, expected and actual event counts match, and no benchmark orphan event exists. A safety timeout or integrity failure stops before fixture deletion, so reset never removes an undrained event. After the gate, cleanup remains restricted to exact `BENCH_USER_` data and reserved sessions; it never truncates tables or runs `FLUSHALL`.

Run reset between every write round:

```powershell
.\reset.ps1
```

## Environment and evidence

`run-one.ps1` starts `metrics-sampler.ps1` for the measurement window and stops it immediately after k6 exits. The default two-second sampler reaches Actuator only through `docker compose exec backend` and `127.0.0.1:8080`, so samples do not traverse Nginx or enter k6 workload counts. Raw JSONL and an aggregate JSON record CPU peak/median, heap peak, GC deltas, thread peak, Hikari peaks and Outbox peaks.

Copy `environment-template.md` and `results/summary-template.md` for each final Phase 6B-2 run. Keep only normalized summaries in Git; raw JSON, CSV, request logs, JWTs and large time series belong under ignored `results/raw/`.

Final scenarios require three comparable runs and median/min/max reporting. Do not select the best run, do not describe local results as production capacity, and do not change locks, indexes, pools, JVM, logging or rate limits during a baseline series.
