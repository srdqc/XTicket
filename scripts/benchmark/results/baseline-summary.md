# Phase 6B-2G Targeted Baseline Completion

> LOCAL BENCHMARK — NOT PRODUCTION CAPACITY

## Status

- Benchmark commit: `85cc0f60a88fd6172f18fa3369d248eff2a4e760`
- Branch: `feat/observability-benchmark`
- Result: `PARTIAL / TRANSACTION_50_VU_THRESHOLD_FAILED`
- Phase 6C gate: `PHASE_6C_INCONCLUSIVE`
- Production or benchmark harness changes: none
- The 50 VU transaction measurement crossed the existing `timeout_error count==0` threshold (12 timeouts), so `run-one.ps1` exited 99. The raw measurement is retained as invalid overload evidence; 50 VU run2/run3 were not retried.

## Frozen environment and stability

- Host: Intel Core i7-12700F, 20 logical cores, 15.84 GiB RAM
- Docker: 20 CPU, 8,238,301,184 bytes memory
- Backend: one instance; Temurin 17.0.20; `-Xms128m -Xmx200m -XX:+UseSerialGC`
- MySQL 8.0.46; Redis 7.4.11; RocketMQ 5.1.4
- Hikari: minimum 5, maximum 20, connection timeout 10 seconds
- k6: v2.2.0; Nginx upstream keepalive: 64; client/server: same host
- Preflight: all six core containers running with restart count 0; backend health `UP`; 60-second idle observation stable
- End of run: the same container start timestamps and restart count 0; backend health `UP`
- Targeted-window Nginx counters: HTTP 502 = 0; `errno 99` = 0; connection refused = 0

## Fixture gate

- `PREPARE_OK`: 100 benchmark users; 8 sessions; DB and Redis stock 20,000 per session
- Initial reset: orders 0; locks 0; open Outbox 0
- Every successful formal round ended in `RESET_OK`, stock restored, final pending/processing/failed 0, missing consumed 0 and orphan events 0
- After the invalid 50 VU transaction round, a separate safety reset/drain completed with expected/actual events 34/34 and final open Outbox 0

## Preserved read baseline

| Scenario | VU | QPS | P50 ms | P95 ms | P99 ms | CPU peak/median % | Hikari active/pending peak |
|---|---:|---:|---:|---:|---:|---:|---:|
| Activities | 1 | 404.637 | 2.060 | 3.995 | 7.301 | 2.285 / 2.050 | 1 / 0 |
| Activities | 10 | 1,969.113 | 4.356 | 7.752 | 11.070 | 13.394 / 12.892 | 10 / 0 |
| Activities | 25 | 2,652.498 | 8.016 | 15.410 | 20.963 | 21.625 / 20.229 | 20 / 0 |
| Activities | 50 | 2,539.782 | 17.580 | 30.767 | 40.188 | 24.229 / 20.457 | 20 / 19 |
| Seat Layout | 1 | 26.560 | 12.335 | 24.083 | 31.194 | 1.120 / 0.822 | 1 / 0 |
| Seat Layout | 10 | 44.427 | 150.678 | 207.955 | 257.790 | 2.176 / 1.978 | 1 / 0 |
| Seat Layout | 25 | 54.454 | 412.879 | 526.254 | 746.116 | 3.258 / 2.223 | 6 / 0 |
| Seat Layout | 50 | 57.142 | 840.325 | 1,043.973 | 1,141.816 | 4.674 / 2.503 | 1 / 0 |

- Activities conclusion: single-run `KNEE_AROUND_25`; 25→50 VU reduced QPS 4.25% while P95 doubled and Hikari pending reached 19.
- Seat Layout conclusion: single-run `KNEE_AROUND_25`; 25→50 VU added only 4.94% QPS while P95 nearly doubled. CPU remained low and Hikari pending remained zero.

## Lock paired experiment

All lock rounds used warmup 30 seconds, measurement 60 seconds, 0.5-second pacing, the same deterministic user pool and a 2-second sampler. Every round had app success equal to request count and zero business conflict, rate limit, system error, network error, timeout and parse error.

| Mode | VU | Run | QPS | P50 ms | P95 ms | P99 ms | Max ms | CPU peak/median % | Heap peak bytes | GC count/time | Threads | Hikari active/pending |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| Same | 25 | 1 | 46.611 | 20.432 | 99.399 | 180.902 | 392.745 | 2.281 / 1.154 | 167,579,952 | 18 / 0.099 s | 284 | 3 / 0 |
| Same | 25 | 2 | 48.443 | 9.810 | 26.490 | 56.084 | 133.070 | 2.775 / 1.344 | 91,110,584 | 29 / 0.090 s | 216 | 4 / 0 |
| Same | 25 | 3 | 48.791 | 9.163 | 24.025 | 47.856 | 107.544 | 1.491 / 0.863 | 93,186,176 | 29 / 0.055 s | 215 | 5 / 0 |
| Different | 25 | 1 | 48.781 | 8.932 | 21.849 | 55.965 | 134.532 | 0.982 / 0.778 | 101,738,000 | 29 / 0.064 s | 240 | 4 / 0 |
| Different | 25 | 2 | 48.811 | 9.317 | 19.836 | 51.229 | 115.871 | 0.943 / 0.749 | 100,069,576 | 29 / 0.059 s | 240 | 5 / 0 |
| Different | 25 | 3 | 48.756 | 10.649 | 21.102 | 41.387 | 120.988 | 0.931 / 0.748 | 102,109,640 | 29 / 0.061 s | 240 | 4 / 0 |
| Same | 50 | 1 | 93.186 | 9.338 | 159.384 | 222.816 | 393.010 | 3.057 / 1.721 | 167,721,808 | 36 / 0.110 s | 284 | 15 / 0 |
| Same | 50 | 2 | 97.313 | 8.367 | 24.701 | 60.667 | 247.588 | 1.781 / 1.583 | 97,968,696 | 59 / 0.122 s | 240 | 7 / 0 |
| Same | 50 | 3 | 97.048 | 8.535 | 27.787 | 60.492 | 243.959 | 1.829 / 1.529 | 99,968,432 | 60 / 0.129 s | 240 | 8 / 0 |
| Different | 50 | 1 | 97.296 | 8.136 | 25.317 | 81.541 | 243.534 | 1.782 / 1.382 | 104,643,944 | 59 / 0.114 s | 240 | 18 / 0 |
| Different | 50 | 2 | 97.049 | 8.533 | 25.814 | 85.900 | 254.635 | 1.654 / 1.412 | 106,536,400 | 58 / 0.124 s | 240 | 7 / 0 |
| Different | 50 | 3 | 97.195 | 8.599 | 24.015 | 55.167 | 235.146 | 1.669 / 1.378 | 107,189,640 | 59 / 0.118 s | 240 | 6 / 0 |

| Mode | VU | QPS median [min, max] | P95 median [min, max] ms |
|---|---:|---:|---:|
| Same | 25 | 48.443 [46.611, 48.791] | 26.490 [24.025, 99.399] |
| Different | 25 | 48.781 [48.756, 48.811] | 21.102 [19.836, 21.849] |
| Same | 50 | 97.048 [93.186, 97.313] | 27.787 [24.701, 159.384] |
| Different | 50 | 97.195 [97.049, 97.296] | 25.317 [24.015, 25.814] |

- At 25 VU, Same median QPS was 0.692% lower and median P95 was 25.532% higher than Different.
- At 50 VU, Same median QPS was 0.151% lower and median P95 was 9.754% higher than Different.
- QPS separation was below 1%, the direction was not consistent run-by-run, CPU was not saturated and Hikari pending remained zero. Conclusion: `SESSION_LOCK_NOT_PRIMARY`.

## Transaction experiment

TPS is `transaction_success / 60 seconds`; latency is the full `transaction_duration` from lock request start through payment success response end.

| VU | Run | Valid | TPS | HTTP RPS | P50 ms | P95 ms | P99 ms | Max ms | App success | Business conflict | Rate limit | System/network/timeout/parse |
|---:|---:|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 1 | 1 | yes | 27.483 | 82.409 | 36.0 | 44.0 | 52.0 | 72 | 4,947 | 0 | 0 | 0 / 0 / 0 / 0 |
| 10 | 1 | yes | 42.900 | 128.352 | 234.0 | 289.0 | 327.540 | 412 | 7,722 | 0 | 0 | 0 / 0 / 0 / 0 |
| 10 | 2 | yes | 21.033 | 62.691 | 459.0 | 594.900 | 1,362.440 | 3,262 | 3,786 | 0 | 0 | 0 / 0 / 0 / 0 |
| 10 | 3 | yes | 21.800 | 65.018 | 456.0 | 565.650 | 803.200 | 1,109 | 3,924 | 0 | 0 | 0 / 0 / 0 / 0 |
| 25 | 1 | yes | 41.467 | 123.457 | 584.0 | 686.0 | 886.170 | 3,907 | 7,466 | 13 | 0 | 0 / 0 / 0 / 0 |
| 25 | 2 | yes | 16.750 | 51.263 | 1,136.0 | 1,691.800 | 2,586.760 | 9,136 | 3,021 | 102 | 0 | 0 / 0 / 0 / 0 |
| 25 | 3 | yes | 21.283 | 63.010 | 1,139.0 | 1,448.800 | 2,134.200 | 4,708 | 3,833 | 17 | 0 | 0 / 0 / 0 / 0 |
| 50 | 1 | no — timeout threshold | 0.200 | 7.956 | 6,434.5 | 11,777.150 | 14,376.230 | 15,026 | 70 | 425 | 0 | 0 / 0 / 12 / 0 |

| VU | Three-run TPS median [min, max] | P95 median [min, max] ms | P99 median [min, max] ms |
|---:|---:|---:|---:|
| 10 | 21.800 [21.033, 42.900] | 565.650 [289.000, 594.900] | 803.200 [327.540, 1,362.440] |
| 25 | 21.283 [16.750, 41.467] | 1,448.800 [686.000, 1,691.800] | 2,134.200 [886.170, 2,586.760] |
| 50 | unavailable | unavailable | unavailable |

The valid 10/25 VU repeats show a candidate `KNEE_AROUND_10`, but run-to-run throughput varies by roughly 2×. At 25 VU, Hikari active reached 20 and pending reached 4–5. At 50 VU, Hikari pending reached 33, full-transaction P95 exceeded 11 seconds and 12 requests timed out. Because the 50 VU round failed its required threshold and lacks repeats, the transaction conclusion is `INCONCLUSIVE_WITH_OVERLOAD_AT_50`.

### Transaction server and Outbox evidence

| VU/run | CPU peak/median % | Heap peak bytes | GC count/time | Threads | Hikari active/pending | Outbox peak pending/processing | End open | 10s open | 30s open | Time-to-zero | Final P/R/F | Expected/actual | Missing/orphan |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 1/1 | 2.950 / 2.669 | 109,785,296 | 207 / 0.411 s | 268 | 3 / 0 | 1,125 / 0 | 1,104 | 654 | 0 | 32.409 s | 0/0/0 | 3,298/3,298 | 0/0 |
| 10/1 | 4.465 / 3.752 | 115,612,328 | 401 / 0.915 s | 234 | 8 / 0 | 2,898 / 1 | 2,898 | 2,298 | 1,389 | 88.525 s | 0/0/0 | 5,148/5,148 | 0/0 |
| 10/2 | 2.224 / 1.914 | 122,818,616 | 133 / 2.036 s | 237 | 10 / 0 | 1,275 / 1 | 1,264 | 1,014 | 562 | 55.134 s | 0/0/0 | 2,524/2,524 | 0/0 |
| 10/3 | 2.130 / 1.873 | 90,095,744 | 137 / 0.323 s | 236 | 9 / 0 | 1,348 / 1 | 1,348 | 1,063 | 563 | 58.668 s | 0/0/0 | 2,616/2,616 | 0/0 |
| 25/1 | 3.976 / 3.682 | 119,859,376 | 380 / 0.985 s | 260 | 20 / 4 | 2,836 / 1 | 2,836 | 2,212 | 1,342 | 83.032 s | 0/0/0 | 4,976/4,976 | 0/0 |
| 25/2 | 2.004 / 1.831 | 95,338,648 | 98 / 0.256 s | 259 | 20 / 5 | 1,041 / 1 | 1,041 | 767 | 301 | 45.739 s | 0/0/0 | 2,010/2,010 | 0/0 |
| 25/3 | 2.161 / 1.907 | 95,509,672 | 136 / 0.359 s | 259 | 20 / 4 | 1,323 / 1 | 1,323 | 1,073 | 621 | 58.468 s | 0/0/0 | 2,554/2,554 | 0/0 |
| 50/1 invalid | 0.347 / 0.186 | 122,874,832 | 4 / 0.018 s | 286 | 20 / 33 | 7 / 0 | unavailable | unavailable | unavailable | unavailable | 0/0/0 after safety reset | 34/34 after safety reset | 0/0 |

All successful transaction rounds had asynchronous backlog at 30 seconds except 1 VU, but every backlog safely drained within 120 seconds. Outbox failed, missing consumed and orphan event counts remained zero.

## Gate decision

`PHASE_6C_INCONCLUSIVE`

The lock comparison is complete and rules out the session-level lock as the primary bottleneck. Seat Layout remains a strong user-visible optimization candidate, but the requested final baseline is incomplete: transaction 50 VU failed the timeout threshold and has no three-run confirmation, while valid 10/25 VU TPS varies by roughly twofold despite a stable Docker runtime. That noise prevents a defensible single Phase 6C optimization choice from the combined baseline. The next step should isolate the transaction variance and 50 VU Hikari saturation before selecting an implementation target. No Phase 6C change was implemented.
