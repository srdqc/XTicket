# Phase 6B-2 Retry Local Baseline Summary

> LOCAL BENCHMARK — NOT PRODUCTION CAPACITY

## Status

- New benchmark commit: `8e1038fd9a0aa3a6ee9e442ca4a507adcf9079d4`
- Gateway remediation: confirmed active (`upstream backend_upstream`, `keepalive 64`, HTTP/1.1, cleared `Connection` header)
- Previous baseline commit `c1c6dc0ac47a6cfa33bb4786f731597f98b3c0e5`: permanently `INVALID`; none of its results are mixed into this retry
- Retry result: `INCOMPLETE / BLOCKED`
- Phase 6C gate: `PHASE_6C_INCONCLUSIVE`
- Stop reason: the first transaction exploration run completed its 60-second measurement successfully, but 21 benchmark Outbox events were still open after the mandatory 30-second drain window. The runner therefore returned non-zero and classified the run as invalid. No testing infrastructure was changed and no later transaction or repeat runs were started.

## Frozen environment

- Branch: `feat/observability-benchmark`
- Host: Intel Core i7-12700F, 20 logical cores, 15.84 GiB RAM
- OS: Windows 11 Pro build 26200
- Docker Desktop: 4.88.1; Engine 29.7.2; 20 CPU; 8,238,313,472 bytes memory
- Client/server: same host
- k6: v2.2.0
- Host JDK: Zulu OpenJDK 17.0.20.1
- Backend: one instance; Temurin 17.0.20; `-Xms128m -Xmx200m -XX:+UseSerialGC`; container memory 350 MiB; no CPU limit
- MySQL: 8.0.46
- Redis: 7.4.11
- RocketMQ: 5.1.4
- Hikari: minimum 5, maximum 20, connection timeout 10 seconds

## Fixture gate

- Users: 100
- Sessions: 8 (`910001`–`910008`)
- Capacity: 20,000 seats per session
- Initial DB and Redis stock: 20,000 for every session
- Initial benchmark orders, locks and open Outbox rows: 0
- Final cleanup: `RESET_OK`; benchmark orders 0, locks 0, all eight DB stocks 20,000, open Outbox rows 0

## Completed exploration measurements

All rows below use the new benchmark commit. Latency is HTTP latency except the transaction row, whose latency is end-to-end transaction duration. The transaction row is evidence from an invalid run and must not be used as a formal capacity result.

| Scenario | VU | Run validity | QPS | TPS | P50 ms | P95 ms | P99 ms | Max ms | App success | Rate limited | System/network/timeout/parse |
|---|---:|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| read-activities | 1 | valid | 356.089 | — | 2.122 | 4.909 | 7.451 | 54.396 | 21,367 | 0 | 0 |
| read-activities | 10 | valid | 1,684.913 | — | 5.134 | 9.019 | 12.767 | 103.113 | 101,101 | 0 | 0 |
| read-activities | 25 | valid | 1,905.739 | — | 10.470 | 21.020 | 29.209 | 4,005.727 | 114,354 | 0 | 0 |
| read-activities | 50 | valid | 1,953.032 | — | 21.908 | 48.518 | 79.519 | 572.005 | 117,223 | 0 | 0 |
| read-seat-layout | 1 | valid | 32.777 | — | 12.680 | 25.786 | 34.142 | 59.073 | 1,968 | 0 | 0 |
| read-seat-layout | 10 | valid | 59.225 | — | 114.141 | 272.772 | 446.901 | 2,269.987 | 3,568 | 0 | 0 |
| read-seat-layout | 25 | valid | 47.057 | — | 460.344 | 704.391 | 1,173.720 | 2,016.358 | 2,839 | 0 | 0 |
| read-seat-layout | 50 | valid | 49.359 | — | 928.736 | 1,409.600 | 1,558.917 | 1,834.372 | 2,999 | 0 | 0 |
| lock-same-session | 1 | valid | 117.632 | — | 7.730 | 12.065 | 16.865 | 37.160 | 7,058 | 0 | 0 |
| lock-same-session | 10 | workload rate-limited | 333.323 | — | 3.771 | 83.227 | 154.701 | 423.525 | 8,294 | 11,706 | 0 |
| lock-same-session | 25 | workload rate-limited | 157.270 | — | 109.562 | 321.690 | 355.169 | 551.252 | 7,275 | 2,200 | 0 |
| lock-same-session | 50 | valid | 82.745 | — | 598.785 | 692.919 | 750.266 | 951.741 | 5,015 | 0 | 0 |
| lock-different-session | 1 | valid | 64.837 | — | 14.664 | 20.542 | 25.795 | 49.727 | 3,891 | 0 | 0 |
| lock-different-session | 10 | valid | 85.231 | — | 106.988 | 168.015 | 194.201 | 238.126 | 5,123 | 0 | 0 |
| lock-different-session | 25 | valid | 74.630 | — | 336.291 | 498.844 | 611.344 | 737.509 | 4,498 | 0 | 0 |
| lock-different-session | 50 | valid | 201.115 | — | 241.681 | 356.082 | 421.477 | 578.728 | 12,107 | 0 | 0 |
| transaction-flow | 1 | invalid: Outbox drain timeout | 47.782 HTTP | 15.927 | 61.000 | 83.000 | 103.440 | 189.000 | 2,871 | 0 | 0 |

Business conflicts and authentication failures were zero in every completed measurement. The lock 10/25 VU same-session rows are explicitly workload-rate-limited and cannot support a capacity or lock-bottleneck conclusion.

## Gateway and post-stop observations

- Nginx HTTP 502 in the retry observation window: 0
- Nginx `errno 99` in the retry observation window: 0
- Gateway remediation remained effective throughout the completed runs.
- The invalid transaction measurement created 957 successful transactions and 1,914 Outbox events. At the 30-second reset deadline, 21 remained open. They subsequently reached `PUBLISHED`; the final cleanup then succeeded.
- Post-stop snapshot only (not a peak sample): backend CPU 1.08%, JVM process CPU 0.66%, JVM memory used 269,556,960 bytes across heap and non-heap, live threads 223, Hikari active 0 / idle 5 / pending 0, Outbox pending 0 / processing 0 / failed 0.
- Container memory in the post-stop snapshot was 338.4 MiB of 350 MiB. Because no synchronized peak series exists for the invalid transaction run, this does not prove JVM, Hikari, DB, or host saturation.

## Knee and bottleneck assessment

- Activities: throughput largely flattened between 25 and 50 VU while latency continued rising; `KNEE_AROUND_25_VU` for this local read workload.
- Seat layout: throughput peaked at 10 VU and degraded at 25/50 VU with sharply higher tail latency; `KNEE_AROUND_10_VU`.
- Same vs different session: `INCONCLUSIVE`. Required 25/50 VU three-run pairs were not executed, and the same-session 25 VU exploration row was rate-limited.
- Transaction: `INCONCLUSIVE`. Only the 1 VU measurement completed, and its mandatory Outbox drain failed.
- DB/JVM/Hikari: no synchronized, repeatable evidence supports attribution to any one of them.
- Host limits: this is a same-host local benchmark and cannot be interpreted as production capacity.

## Gate decision

`PHASE_6C_INCONCLUSIVE`

The gateway blocker is resolved, but the official baseline remains incomplete. The mandatory transaction cleanup gate failed, the paired three-run lock experiments were not reached, and no repeatable server-side peak series exists. Selecting a Phase 6C optimization variable from this evidence would be speculative. No production code or runtime configuration was modified during this retry.
