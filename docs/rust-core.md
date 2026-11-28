# Rust core architecture

`rust-core` owns parsing and deterministic business rules; Android remains the
owner of HTTP, cancellation/lifecycle, secure token storage, Room, UI, media,
widgets, APK certificate checks and installation.

## Boundary

- Callable/feed, Firestore history and chat bodies cross JNI once as raw bytes.
- Results use big-endian `LKT1`, schema version 4, a record type, bounded counts
  and length-prefixed UTF-8. Limits are 64 MiB per batch, 100,000 records and
  8 MiB per general string. Decoders reject invalid tags, UTF-8, lengths,
  truncation and trailing bytes.
- Feed and History moments carry the original overlay array, an optional-field
  presence mask and a typed caption. Typed fields use a 64 KiB input bound,
  4 KiB per text field and 16 gradient colors. Keep raw fields in Room and
  upload journals. Raw unknown types remain available for forward compatibility.
- Firestore Listen keeps protobuf documents until mapping to models. Its overlay
  subtree supplies the same Android caption DTO used by the Kotlin legacy
  fallback. The widget renders from that DTO without Compose.
- Feed merge, tombstones/hidden-owner filtering, timezone-aware streak, token
  refresh decisions, polling overlap and update-manifest validation are Rust
  policies invoked in batches.
- Outgoing chat payloads are built and serialized by Rust into UTF-8 bytes.
  Ordinary chat always includes explicit `moment_uid: null`; replies retain
  the canonical moment UID. Kotlin sends those bytes directly with OkHttp
  and retains the original client token. Developer builds without native
  libraries use a contract-compatible Kotlin fallback.
- Native failures become Java `IllegalArgumentException`; Rust release builds
  use `panic=abort`, and network/JNI input paths do not use `unwrap`.

Record types: `1` feed delta, `2` history page, `3` chat page. History cursors
carry the original RFC3339 timestamp plus full document name, preserving the
nanosecond boundary used by Firestore.

## Build and test

```bash
cargo test --manifest-path rust-core/Cargo.toml --locked
./scripts/build-rust-android.sh
JAVA_HOME=/path/to/jdk17 ./gradlew testDebugUnitTest compileDebugKotlin
```

The Android script requires `cargo-ndk`, an Android NDK and four Rust targets.
It builds arm64-v8a, armeabi-v7a, x86_64 and x86 for API 26. Target linker
configuration uses a 16 KiB maximum page size. Generated `.so` files and
`target/` are ignored and must not be committed.

The workflow remains manual-only and builds/tests Rust before Gradle. Use
`verify_only=true` to run Rust tests, Kotlin compilation and JVM tests and
inspect the unsigned R8 APK's native packaging without publishing. The signing
and Pages path retains the existing package ID, version scheme, key and
certificate continuity checks.

## Database decision

Room remains the sole writer. No comparable device benchmark showed Room or
JNI database traffic to be a bottleneck, so phase 5's conditional rusqlite
migration is intentionally not activated. This avoids dual writers and keeps
the widget/cache migration path stable. Reconsider only with same-device cold/
warm feed, query latency, PSS/CPU and APK-size measurements plus a transactional
rollback plan.

## Compatibility and remaining validation

The old Kotlin parsers remain as a fallback when a developer build lacks the
native library. They can be removed only after all four packaged ABIs and a
signed update-over-install pass on real devices. No performance claim is made
without those measurements.

See [the optimization report](rust-optimization-report.md) for phase decisions and device coverage.
