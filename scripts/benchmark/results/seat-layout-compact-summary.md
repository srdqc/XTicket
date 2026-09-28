# Phase 6C-2 Compact Seat Layout Response Protocol

> LOCAL BENCHMARK — NOT PRODUCTION CAPACITY

## Experiment freeze

- Branch: `feat/observability-benchmark`
- HEAD: `08f2389d309700dded7d9f8dbbce78d2a5c20921`
- Workload: fixed session `910001`, 100 × 200 = 20,000 seats
- A/B: old full-object endpoint versus compact endpoint; 25/50 VU × 3 runs
- Every run: 30-second warmup, 60-second measurement, gzip enabled, identical Docker resources and two-second backend sampler
- Client and server ran on the same local host.

## Contract and semantic evidence

The old seat object contains `row`, `col`, `label`, `status`, and `couple`. The frontend uses all five, but only `status` is dynamic; the other four values are restored from the grid metadata and seat index.

The compact endpoint is `GET /api/seat/layout/compact?scheduleId={id}`. It keeps the old endpoint intact and returns:

```json
{
  "sessionId": 910001,
  "hallName": "BENCH_HALL_001",
  "hallType": "BENCHMARK",
  "layout": {
    "rows": 100,
    "cols": 200,
    "aisles": [],
    "coupleRows": [],
    "disabled": []
  },
  "sold": [],
  "locked": [],
  "myLocked": []
}
```

Indexes are zero-based row-major: `index = (row - 1) * cols + (col - 1)`. State priority remains `DISABLED > SOLD > OTHER_LOCKED / MY_LOCKED > AVAILABLE`. The frontend reconstructs the old `SeatLayoutData` without fallback to the old endpoint.

The compact service path directly uses the existing session, hall, active-lock and sold-seat queries. It does not invoke `buildSeatLayout`, create 20,000 `SeatInfo` objects, alter SQL/indexes, or call Redis.

- Service-level comparison: all 20,000 seats, zero mismatches; includes disabled seats, couple rows, sold, own/other locks and overlapping-state priority.
- Docker API empty-session comparison: 20,000 seats, zero mismatches.
- After locking two seats: 20,000 seats, zero mismatches; both endpoints reported the same locked state.
- After lock → create → pay: 20,000 seats, zero mismatches; both endpoints reported the two seats as sold.
- Frontend production build passed after switching the page to the compact parser.

## Payload

| Metric | Old | Compact | Reduction |
|---|---:|---:|---:|
| Logical JSON body | 1,355,557 bytes | 227 bytes | 99.983% |
| Gzip transfer body | 113,475 bytes | 205 bytes | 99.819% |

For the clean fixture, the old response contains 20,000 seat objects. The compact DTO contains one layout container, five scalar/string metadata values, six metadata/state arrays, and zero index elements (`aisles=0`, `coupleRows=0`, `disabled=0`, `sold=0`, `locked=0`, `myLocked=0`). Index counts grow only with static exceptions or non-available seats.

## 25 VU formal runs

| Protocol | Run | QPS | P50 ms | P95 ms | P99 ms | Waiting P95 ms | Receiving P95 ms | Backend CPU peak/median % | Heap peak bytes | GC count/time | Threads | Hikari active/pending | Nginx CPU peak/average % |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| Old | 1 | 64.774 | 342.644 | 478.784 | 900.575 | 54.173 | 439.655 | 7.912 / 5.375 | 158,042,768 | 233 / 2.133 s | 228 | 3 / 0 | 27.76 / 21.606 |
| Old | 2 | 61.160 | 361.607 | 519.162 | 667.989 | 53.789 | 479.644 | 4.800 / 4.357 | 153,876,616 | 224 / 2.146 s | 232 | 2 / 0 | 28.49 / 22.617 |
| Old | 3 | 65.543 | 349.218 | 460.492 | 590.703 | 46.182 | 424.970 | 4.609 / 4.214 | 186,541,832 | 233 / 1.889 s | 232 | 2 / 0 | 29.31 / 23.251 |
| Compact | 1 | 2,361.122 | 9.878 | 15.785 | 20.435 | 15.261 | 1.724 | 25.819 / 24.978 | 144,523,904 | 480 / 1.676 s | 266 | 20 / 4 | 72.28 / 64.962 |
| Compact | 2 | 2,197.520 | 10.525 | 17.542 | 23.321 | 17.058 | 1.764 | 25.395 / 23.843 | 143,188,656 | 448 / 1.740 s | 266 | 20 / 5 | 69.86 / 63.201 |
| Compact | 3 | 2,398.296 | 9.749 | 15.374 | 19.385 | 14.839 | 1.719 | 26.030 / 24.956 | 149,394,344 | 487 / 1.588 s | 266 | 20 / 5 | 68.95 / 63.209 |

Median P95 changed from 478.784 ms to 15.785 ms, a 96.703% improvement. Median receiving P95 changed from 439.655 ms to 1.724 ms; median waiting P95 changed from 53.789 ms to 15.261 ms.

## 50 VU formal runs

| Protocol | Run | QPS | P50 ms | P95 ms | P99 ms | Waiting P95 ms | Receiving P95 ms | Backend CPU peak/median % | Heap peak bytes | GC count/time | Threads | Hikari active/pending | Nginx CPU peak/average % |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| Old | 1 | 67.890 | 685.995 | 923.329 | 1,699.111 | 63.436 | 862.168 | 5.617 / 4.632 | 187,216,120 | 246 / 2.502 s | 265 | 2 / 0 | 26.97 / 22.231 |
| Old | 2 | 67.179 | 713.218 | 888.150 | 982.353 | 63.867 | 840.036 | 4.924 / 4.404 | 187,173,112 | 237 / 2.116 s | 266 | 2 / 0 | 39.55 / 24.387 |
| Old | 3 | 66.787 | 675.869 | 1,158.095 | 1,442.518 | 74.497 | 1,081.632 | 5.971 / 4.406 | 174,438,176 | 244 / 2.367 s | 266 | 2 / 0 | 57.36 / 24.731 |
| Compact | 1 | 2,256.217 | 20.680 | 35.739 | 46.042 | 34.866 | 2.834 | 27.738 / 27.180 | 173,127,128 | 445 / 2.507 s | 266 | 20 / 23 | 69.37 / 65.989 |
| Compact | 2 | 2,267.699 | 20.639 | 35.502 | 44.273 | 34.656 | 2.758 | 29.017 / 26.988 | 121,835,576 | 450 / 2.524 s | 276 | 20 / 27 | 70.08 / 65.822 |
| Compact | 3 | 2,199.017 | 21.199 | 36.699 | 45.788 | 35.807 | 2.917 | 27.799 / 26.997 | 162,894,440 | 449 / 2.864 s | 276 | 20 / 27 | 79.31 / 66.715 |

Median P95 changed from 923.329 ms to 35.739 ms, a 96.129% improvement. Median P99 is 45.788 ms. Median receiving P95 changed from 862.168 ms to 2.834 ms (99.671% lower); median waiting P95 changed from 63.867 ms to 34.866 ms (45.408% lower).

All 12 measurements had zero system, network, timeout and parse errors. The compact protocol intentionally exposes the next saturation boundary at much higher throughput: median 50-VU backend CPU peak rose from 5.617% to 27.799%, median Nginx sampled average from 24.387% to 65.989%, Hikari active reached 20 and pending peaked at 23–27. Despite that load, latency remained far below the project target and no request failed. Heap did not regress materially; GC count increased with roughly 33× request throughput while GC time remained bounded.

## Conclusion

`SEAT_LAYOUT_TARGET_MET`

- 50 VU median P95: 35.739 ms — target `<500ms` met.
- 50 VU median P99: 45.788 ms — target `<800ms` met.
- Semantic mismatch: 0.
- System errors/timeouts: 0/0.

The Seat Layout optimization is closed. No additional cache, serialization, SQL, pool, JVM or gateway optimization is justified by the current project target.
