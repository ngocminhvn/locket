# Rust Core optimization report

Baseline source is `529b487`. Verification-only baseline `b518a94` passed
Rust tests, all four native ABI builds and the Kotlin compilation/unit tests
on GitHub. The existing signed baseline build also passed. No Android device
was available for matched runtime measurements.

| Phase | Source | Verification | Baseline | After | Decision |
| --- | --- | --- | --- | --- | --- |
| 0 | `b518a94`, performance collection script | GitHub baseline passed. Existing signed baseline passed. | No matched runtime samples | N/A | Keep build dispatch manual. Verification does not sign or publish. |
| 6 | `5844d33`, `8a1cc5c` | Rust and JVM fixtures for known, malformed or null overlays, raw unknown fields, Unicode, half stars, alpha, version and truncated/trailing data | Renderers parsed overlay JSON on demand, with repeated parsing in the widget | Rust maps REST records while parsing. Realtime maps the protobuf overlay. Room hydrates the same DTO via a bounded cache. | Keep Kotlin decoding for legacy cache and unavailable native libraries. Validate rendering on device. |
| 6B | `6d5956a`, delta and widget refinements | Compile and partial-update regression fixtures | DAO filtered writes, but read the full table and serialized the full feed to widget preferences at each update | One row for one changed record and keyed deletes by construction. Latest widget record is serialized only when needed. | Keep Room schema 4. Actual SQLite write counts and latency are N/A. |
| 7 | `587822a` | Compile and source-level comparison of bands, hue bins and tone mapping | Three bitmap crops and four pixel reads per cache miss | One bitmap pixel read, shared hue and weight, and cache-only adjacent prefetch | Keep Kotlin and Android HSV until a separate Android measurement shows whether a Rust boundary helps. Golden device images and frame measurements are N/A. |
| 8 | `3d39359` | JVM file-stream digest parity for padded Base64 and read boundaries | Read the whole queued thumbnail and allocate an encoded Base64 copy | Upload from the durable file and hash streamed Base64. Camera capture and codec settings stay in Android. | Rust codec prototype is deferred at the measurement gate. Physical camera, HDR, gallery and live upload checks are N/A. |
| 9 | `8a1cc5c` | Protobuf nested and timestamp fixtures, Rust History fixture and tests for omitted fields vs explicit null | Listen built a Firestore-shaped Gson tree for every document | Read models from protobuf fields. A Gson tree is retained only for the overlay subtree, which also provides raw persistence. Feed tombstones and upserts share a transaction. | Keep gRPC and the existing window eviction/reconnect policy. Measure on real Listen sessions before considering a Rust protobuf decoder. |
| 10 | `f500b65` | JVM DST, leap day, year boundary, duplicate and 1k/10k fixtures | String keys, per-day lists and unconditional detail sort | Local month/day index and one detail pass when the incoming feed is already ordered | Keep Kotlin/calendar timezone handling. Fixture sizes verify correctness, not device time. |
| 11 | `aecd98b` | JVM tests for recovery states, review rules and raw outgoing fields | Durable journal had a post marker, and the HTTP request body was replayable | Validate before the post marker and use a one-shot body. Confirmed journal state is persisted before cleanup. | Keep file I/O, transport, and provider parsers in Kotlin. Test process death and account switch on device. |
| 12 | Verification workflow and this report | Compare GitHub verification results and artifact packaging. | Runtime baseline unavailable | Runtime comparison unavailable | Keep Room, Android media libraries and network transports. Defer new database and codec dependencies pending evidence. |

## Hot paths

- Cold or warm start → `LocketViewModel` → Room hydration → cached feed → REST and realtime sync.
- Home swipe → Coil thumbnail → one-pass ambient palette → `MomentCaption`.
- Capture → PreviewView or ImageCapture → review → encode thumbnail → atomic journal → stream to Storage → write-ahead marker → `postMomentV2`.
- Live Feed and Chat → Firestore Listen → direct protobuf fields → delta → Room transaction → screen or widget.
- History → REST cursor → feed merge → local calendar index → lazy month/detail UI.

## Measurement and device checks

Collect at least 20 cold and warm samples per scenario on the same Android
device, build mode, account dataset and network conditions. Record p50/p95 and
outliers with the device model, Android version and run count. For frames
and PSS, use `scripts/collect-android-perf.sh reset` before each run and
`scripts/collect-android-perf.sh sample` after it. Use Perfetto/Android
Studio for event-to-UI, shutter-to-review, review-to-enqueue, encode, main
thread CPU, GC/allocation and peak memory. Record DB upsert and delete row
counts without including keys or payloads. Keep benchmark output off git.

Runtime median/p95, jank, CPU, PSS, allocations, GC and transaction measurements
are N/A without a device. APK size is available from the verification workflow
for the unsigned result. Signed APK sizes are not comparable with an unsigned
APK. Build times vary with runner caches and are not runtime measurements.

Device coverage still includes signed update-over-install (Room 3→4 and
existing journals), native loading, minified authenticated Listen,
reconnect and account switch, camera modes and original saving, overlay
rendering, Music/Weather/Location, upload restart and widget refresh.
