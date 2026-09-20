# Phase 6C-1 Seat Layout JSON Compression Optimization

> LOCAL BENCHMARK — NOT PRODUCTION CAPACITY

## Experiment freeze

- Branch: `feat/observability-benchmark`
- PRE_OPT_COMMIT: `45606137305fb825e474fc008baad4b974f0fe1f`
- Workload: `read-seat-layout`, 20,000 seats, warmup 30 seconds, measurement 60 seconds
- Matrix: 25 VU × 3 runs and 50 VU × 3 runs, before and after
- k6 2.2.0 already sent `Accept-Encoding: gzip`; no benchmark script changed
- Only production change: `docker/nginx/nginx-init.conf`

## Configuration

The following directives were added to the existing `http` context:

```nginx
gzip on;
gzip_min_length 1024;
gzip_comp_level 4;
gzip_vary on;
gzip_types application/json;
```

`nginx -t` passed. `nginx -T` showed all five directives as effective. Only Nginx was reloaded; backend, MySQL, Redis and RocketMQ were not rebuilt or restarted.

## Payload and semantics

| Metric | Before | After |
|---|---:|---:|
| Content-Encoding | absent | gzip |
| Transfer body | 1,355,557 bytes | 113,438 bytes |
| Logical body | 1,355,557 bytes | 1,355,557 bytes |
| Compression ratio | 1.000000 | 0.083684 |
| Size reduction | 0% | 91.632% |
| Seat count | 20,000 | 20,000 |
| Logical SHA-256 | `477F6870409E3FD99A03A12BA08F178CB9E7A06786F96A828F0A40CC57E74B1E` | same |

The decompressed response is byte-identical to the Before response. After also includes `Vary: Accept-Encoding`.

## 25 VU runs

| Phase | Run | QPS | P50 ms | P95 ms | P99 ms | Max ms | Waiting P95 ms | Receiving P95 ms | Backend CPU peak/median % | Heap peak | GC count/time | Threads | Hikari active/pending | Nginx CPU peak/average % |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| Before | 1 | 66.402 | 336.794 | 498.279 | 644.258 | 958.265 | 44.559 | 466.682 | 5.164 / 4.313 | 154,555,912 | 241 / 2.042 s | 251 | 2 / 0 | 40.34 / 23.276 |
| Before | 2 | 65.894 | 338.237 | 505.113 | 663.957 | 1,044.703 | 46.704 | 463.812 | 4.532 / 4.075 | 159,574,968 | 199 / 1.611 s | 251 | 1 / 0 | 29.16 / 22.164 |
| Before | 3 | 55.940 | 402.918 | 622.199 | 751.730 | 864.154 | 59.887 | 561.208 | 4.638 / 3.935 | 177,697,312 | 173 / 1.937 s | 250 | 3 / 0 | 28.48 / 20.613 |
| After | 1 | 72.356 | 299.692 | 460.593 | 832.092 | 1,653.251 | 42.326 | 423.669 | 4.793 / 4.072 | 140,424,120 | 226 / 1.753 s | 248 | 1 / 0 | 31.21 / 25.242 |
| After | 2 | 77.923 | 291.366 | 383.754 | 438.371 | 541.844 | 38.119 | 356.064 | 4.876 / 4.259 | 164,420,768 | 242 / 1.835 s | 249 | 3 / 0 | 29.80 / 25.715 |
| After | 3 | 74.593 | 298.969 | 424.649 | 532.190 | 1,787.082 | 40.347 | 385.550 | 4.543 / 4.095 | 129,828,568 | 227 / 1.787 s | 251 | 2 / 0 | 28.19 / 23.390 |

| Metric | Before median [min, max] | After median [min, max] | Change |
|---|---:|---:|---:|
| QPS | 65.894 [55.940, 66.402] | 74.593 [72.356, 77.923] | +13.201% |
| P95 | 505.113 [498.279, 622.199] ms | 424.649 [383.754, 460.593] ms | -15.930% |
| P99 | 663.957 [644.258, 751.730] ms | 532.190 [438.371, 832.092] ms | -19.846% |
| Waiting P95 | 46.704 ms | 40.347 ms | -13.611% |
| Receiving P95 | 466.682 ms | 385.550 ms | -17.385% |

## 50 VU runs

| Phase | Run | QPS | P50 ms | P95 ms | P99 ms | Max ms | Waiting P95 ms | Receiving P95 ms | Backend CPU peak/median % | Heap peak | GC count/time | Threads | Hikari active/pending | Nginx CPU peak/average % |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| Before | 1 | 78.670 | 594.267 | 768.574 | 1,298.733 | 1,744.605 | 50.235 | 718.578 | 4.758 / 4.351 | 159,736,672 | 245 / 1.904 s | 261 | 1 / 0 | 30.33 / 22.825 |
| Before | 2 | 71.973 | 635.725 | 894.781 | 1,985.967 | 2,463.658 | 59.460 | 845.118 | 4.889 / 4.373 | 177,459,688 | 221 / 2.046 s | 280 | 2 / 0 | 33.74 / 24.647 |
| Before | 3 | 75.044 | 611.941 | 898.155 | 1,506.858 | 2,316.497 | 56.437 | 821.290 | 4.652 / 4.422 | 166,464,168 | 228 / 1.865 s | 282 | 1 / 0 | 68.42 / 25.790 |
| After | 1 | 77.888 | 603.762 | 799.976 | 1,068.039 | 1,726.450 | 52.398 | 736.687 | 5.584 / 4.283 | 153,362,024 | 242 / 1.912 s | 278 | 2 / 0 | 35.93 / 24.838 |
| After | 2 | 76.305 | 618.883 | 786.031 | 1,099.359 | 1,675.413 | 54.021 | 736.157 | 4.969 / 4.479 | 170,708,160 | 231 / 2.107 s | 281 | 3 / 0 | 36.45 / 24.728 |
| After | 3 | 76.226 | 620.654 | 776.829 | 1,152.568 | 1,605.195 | 50.934 | 745.569 | 4.771 / 4.404 | 162,366,056 | 236 / 1.766 s | 283 | 2 / 0 | 28.77 / 23.473 |

| Metric | Before median [min, max] | After median [min, max] | Change |
|---|---:|---:|---:|
| QPS | 75.044 [71.973, 78.670] | 76.305 [76.226, 77.888] | +1.680% |
| P95 | 894.781 [768.574, 898.155] ms | 786.031 [776.829, 799.976] ms | -12.154% |
| P99 | 1,506.858 [1,298.733, 1,985.967] ms | 1,099.359 [1,068.039, 1,152.568] ms | -27.043% |
| Waiting P95 | 56.437 ms | 52.398 ms | -7.157% |
| Receiving P95 | 821.290 ms | 736.687 ms | -10.301% |

All 12 formal measurements had zero system, network, timeout and parse errors. Hikari pending was zero throughout.

## CPU trade-off

- 25 VU median backend CPU peak changed from 4.638% to 4.793%; median process CPU changed from 4.075% to 4.095%.
- 50 VU median backend CPU peak changed from 4.758% to 4.969%; median process CPU changed from 4.373% to 4.404%.
- 25 VU median Nginx CPU peak changed from 29.16% to 29.80%; median sampled average changed from 22.164% to 25.242%.
- 50 VU median Nginx CPU peak changed from 33.74% to 35.93%; median sampled average changed from 24.647% to 24.728%.

Compression has a measurable but bounded Nginx CPU cost. Neither Nginx nor backend CPU became saturated.

## Conclusion

`PAYLOAD_TRANSFER_BOTTLENECK_CONFIRMED`

The payload shrank 91.632%, receiving P95 fell in both VU groups, waiting P95 changed much less, P95 improved 15.930% at 25 VU and 12.154% at 50 VU, and errors remained zero. However receiving still dominates the 50 VU response and the engineering target was not reached:

- 50 VU median P95: 786.031 ms — target `<500ms` not met
- 50 VU median P99: 1,099.359 ms — target `<800ms` not met
- Result: `SEAT_LAYOUT_TARGET_NOT_MET`

The next single-variable candidate is `COMPACT_RESPONSE_PROTOCOL`. It is not implemented in this phase.
