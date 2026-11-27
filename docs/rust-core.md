# Rust core architecture

`rust-core` owns parsing and deterministic business rules; Android remains the
owner of HTTP, cancellation/lifecycle, secure token storage, Room, UI, media,
widgets, APK certificate checks and installation.

## Boundary

- Callable/feed, Firestore history and chat bodies cross JNI once as raw bytes.
- Results use big-endian `LKT1`, schema version 1, a record type, bounded counts
  and length-prefixed UTF-8. Decoders reject invalid tags, lengths, truncation
  and trailing bytes.
- Feed merge, tombstones/hidden-owner filtering, timezone-aware streak, token
  refresh decisions, polling overlap and update-manifest validation are Rust
  policies invoked in batches.
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

The release workflow remains manual-only and builds/tests Rust before Gradle.
It retains the existing package ID, version scheme, persistent signing secret,
certificate continuity checks and `update.json` publication.

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
