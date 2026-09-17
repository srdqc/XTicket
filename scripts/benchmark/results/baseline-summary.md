# Phase 6B-2 Local Baseline Summary

> LOCAL BENCHMARK — NOT PRODUCTION CAPACITY

## Status

- Benchmark commit: `c1c6dc0ac47a6cfa33bb4786f731597f98b3c0e5`
- Result: `INCOMPLETE / BLOCKED`
- Phase 6C gate: `PHASE_6C_INCONCLUSIVE`
- Valid formal runs: `read-activities`, 1 VU, run 1 only
- Invalid formal runs: `read-activities`, 10 VU, run 1
- Remaining scenarios and VU levels were not executed after the first invalid exploration run.

## Frozen environment

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
- DB and Redis stock before formal runs: 20,000 for every session
- Benchmark orders, locks, active order seats, payments, tickets, refunds, and target Outbox backlog: 0

## Formal evidence

| Scenario | VU | Valid | Requests | App success | Parse error | HTTP failed | QPS | P50 | P95 | P99 | Max |
|---|---:|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| read-activities | 1 | yes | 20,104 | 20,104 | 0 | 0% | 335.052 | 2.595 ms | 4.815 ms | 6.253 ms | 35.553 ms |
| read-activities | 10 | no | 81,943 | 27,937 | 54,006 | 65.907% | 1,365.528 raw HTTP | 5.702 ms | 16.340 ms | 24.570 ms | 78.029 ms |

The 10 VU result is invalid and must not be interpreted as application capacity. Nginx emitted HTTP 502 responses and `connect() ... failed (99: Address not available) while connecting to upstream` for the backend address. Those HTML error bodies were correctly classified as parse errors. The backend remained running, was not OOM-killed, and had restart count 0. A post-run request returned HTTP 200 JSON.

## Gate decision

`PHASE_6C_INCONCLUSIVE`

The local Nginx-to-backend connection path failed before the required 1/10/25/50 exploration matrix and three-run paired experiments could be completed. No conclusion can be made about session-level locking, DB, JVM, Hikari, Outbox, or transaction saturation. The experimental blocker must be resolved in a separate committed change, then all affected formal runs must restart from a newly frozen commit.
