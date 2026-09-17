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

Metrics are `app_success`, `business_conflict`, `rate_limited`, `auth_failure`, `system_error`, `network_error`, `timeout_error`, `parse_error`, `transaction_success`, and `transaction_failed`. No user, order, ticket or seat is used as a metric tag.

## Rate-limit boundary

Authenticated write limits use resource plus JWT user ID, not the shared client IP:

- seat lock: token bucket capacity 5, refill 2/s/user
- order create: token bucket capacity 10, refill 3/s/user
- payment: token bucket capacity 5, refill 2/s/user

Scripts rotate prepared users by global iteration. RATE_LIMITED is reported separately and never hidden. If it becomes material, the run is a rate-limit workload and cannot be used as a transaction baseline.

## Scenarios

| Script | Purpose |
|---|---|
| `read-activities.js` | Activity list warm-cache read |
| `read-seat-layout.js` | Fixed benchmark session layout read |
| `lock-same-session.js` | Different users/seats under one `seat:{scheduleId}` lock key |
| `lock-different-session.js` | Equivalent locks distributed over eight lock keys |
| `same-seat-contention.js` | Correctness-only contention; exactly one winner expected |
| `transaction-flow.js` | lock → create → payment; all three must succeed for one transaction |

Transaction TPS means `transaction_success / measurement seconds`. HTTP RPS still contains all three requests and must not be presented as TPS.

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

`reset.ps1` waits up to 30 seconds for benchmark PENDING/PROCESSING/FAILED Outbox rows to drain. Timeout marks the run invalid and cleanup stops. It then deletes only business rows owned by exact `BENCH_USER_` accounts, restores user points and reserved session stock, deletes only reserved session cache keys, restores their Redis stock, and removes only those users' rate-limit keys. It never truncates tables or runs `FLUSHALL`.

Run reset between every write round:

```powershell
.\reset.ps1
```

## Environment and evidence

Copy `environment-template.md` and `results/summary-template.md` for each final Phase 6B-2 run. Capture small Actuator snapshots before and after using internal `/actuator/metrics/<metric>` queries. Keep only normalized summaries in Git; raw JSON, CSV, request logs, JWTs and large time series belong under ignored `results/raw/`.

Final scenarios require three comparable runs and median/min/max reporting. Do not select the best run, do not describe local results as production capacity, and do not change locks, indexes, pools, JVM, logging or rate limits during a baseline series.
