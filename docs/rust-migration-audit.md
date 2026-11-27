# Rust migration audit

Baseline: `358bc7f` (2026-11-27). This document records what was verified before
moving business logic. It is not evidence that an endpoint works at runtime.

## Contract evidence

- Live evidence in `Locket-API-Spec-1.247.3.md`: `postMomentV2`, `fetchUserV2`
  and `getLatestMomentV2` returned callable envelopes on 2026-11-27. The live
  probe also established logical status 401 for unsigned requests.
- Static-only evidence: the other callable request fields, Firestore collection
  layouts, chat message fields and history queries came from the decompiled
  1.247.3 client. They remain static-only until runtime traffic confirms them.
- Firebase token refresh is standard Identity Toolkit behavior used by this
  client, but was not observed in the decompiled JavaScript bundle.
- Unknowns stay unknown: realtime listener transport/resume semantics, the
  production meaning of `last_fetch`, and whether every callable tolerates a
  missing analytics object.

## Current ownership and call sites

| Area | Kotlin owner before migration | Important behavior to preserve |
|---|---|---|
| Auth | `LocketApiClient`, `SessionManager` | one refresh retry, account-scoped sync token, encrypted token storage |
| Feed | `fetchMomentsFeed`, `syncAllData` | 304 keeps cache; commit token after Room write; enrichment does not block feed |
| History | `fetchHistoryPage`, `loadOlderMoments`, `loadOwnHistory` | 24 rows; exact RFC3339 timestamp plus document name cursor |
| Users | `fetchFriends`, `resolveUserNames` | cached names first; unresolved/expired UIDs only; never replace a real name with UID |
| Chat | `fetchChatMessages`, `openChat`, `refreshChat`, `loadOlderChatMessages` | cache-first; 12 s polling; 24-row refresh and 100-row overlap fallback |
| Storage | Room `LocketDao`/`LocketCache` | changed-row reconcile; account data cleared on account switch/logout |
| UI/media | Compose, Coil, CameraX, widget | remains Android-owned |

## Risks found before migration

- Network helpers collapse several transport/parse failures to `null`; migrated
  parsers must return typed errors rather than a valid empty result.
- Feed timestamps currently ignore `_nanoseconds`. Feed ordering therefore needs
  a stable secondary key when moved, while history cursor strings must remain
  byte-for-byte RFC3339 values.
- A 304 feed response is represented as an empty delta. Callers must not confuse
  that with an authoritative empty feed.
- Cancellation must be rethrown across every Kotlin transport boundary. JNI
  parsing is batch-oriented and runs from an IO dispatcher.

## Baseline validation

- `./gradlew compileDebugKotlin` with the host default Java 25 fails during
  Gradle/Kotlin initialization (`25.0.5`); use
  `/home/minh/.jdks/jdk-17.0.21+9` as `JAVA_HOME`.
- No performance numbers were recorded: no device benchmark was authorized or
  run. Startup, fresh-feed latency, jank, PSS/CPU, request count and APK size
  remain unmeasured rather than estimated.
- No live mutation test was run and no real account data was copied into tests.

## Migration gates

Each endpoint moves only after an anonymized fixture proves old/new semantic
parity. Room remains the sole database writer in phases 1-4. The old Kotlin
parser stays available until its Rust replacement passes malformed, null,
oversized and trailing-byte tests on both sides of the wire boundary.
