# AGENT.md — Developer & AI Agent Guide for Locket Android Client

This document is the authoritative architectural and operational specification for AI agents and human developers maintaining, enhancing, or debugging this repository.

---

## 1. Project Overview & Mission

This repository contains a full-featured, native Android client clone of **Locket Camera** built with **Kotlin**, **Jetpack Compose**, **Material 3 Expressive**, **CameraX**, and **Liquid Glass** UI effects.

The client communicates directly with Locket's production backend:
* **Firebase Cloud Functions (v2 Callable):** `https://api.locketcamera.com`
* **Firebase Identity Toolkit (REST Auth):** `https://identitytoolkit.googleapis.com`
* **Google Cloud Storage Bucket:** `gs://locket-img`
* **Google Cloud Firestore (REST):** `projects/locket-4252a/databases/(default)` and `databases/locket` (History)

### Non-Negotiable Core Rules
1. **NO FAKE, DEMO, MOCK, OR FABRICATED DATA — STRICT RULE**: All user-facing data must come from the authenticated live backend, genuine user input, or a local cache of previously retrieved real data. Never invent or seed users, friends, photos, moments, messages, conversations, reactions, badges, account status, statistics, streaks, counts, timestamps, or API responses. Do not use hardcoded sample records, random/generated values presented as real data, stock/placeholder photos presented as actual content, or fake success states. If data is missing, show an honest empty state; while loading, show a loading state; on failure, show an appropriate error or retry option. Never silently substitute fabricated data when the API fails or a field is unavailable. Compute derived values only from verified real records, and do not treat unknown values as confirmed facts. This applies to all screens, repositories, storage, networking, and future features. Do not introduce demo modes or mock-data fixtures unless the repository owner explicitly requests them; any authorized test fixtures must remain isolated from the actual app and its production data paths.
2. **REGISTRATION IS DISABLED**: The "Tạo một tài khoản" (Sign up) action on the landing screen (`WelcomeScreen`) **must remain disabled**. Only login and authenticated session operations are permitted.
3. **CALLABLE ENVELOPE PROTOCOL**: All Locket Callable endpoints wrap data in `{ "result": { "status": 200, "data": ... } }`. Always unwrap `result` before evaluating `status` or reading `data`.
4. **IDENTITY TOOLKIT HEADERS**: All Identity Toolkit REST requests must include headers `X-Android-Package: com.locket.Locket` and `X-Android-Cert: 187A27D3D7364A044307F56E66230F973DCCD5B7` to pass client API key restrictions.

---

## 2. Technology Stack & Environment

| Component | Version / Specification |
|---|---|
| **Language** | Kotlin 2.0.21, Java 17 |
| **Android SDK** | compileSdk 34, targetSdk 34, minSdk 26 |
| **Android Gradle Plugin (AGP)** | 8.7.2 |
| **UI Framework** | Jetpack Compose (BOM 2024.10.01), Compose Compiler 2.0.21 |
| **Design System** | Material 3 Expressive, Liquid Glass modifier (Blur, Specular highlight) |
| **Camera** | CameraX 1.4.0 (`camera-core`, `camera-camera2`, `camera-lifecycle`, `camera-view`) |
| **Networking** | OkHttp 4.12.0, Gson 2.11.0 |
| **Image Loading** | Coil Compose 2.6.0 |
| **State Management** | Android Lifecycle ViewModel (`androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7`), Kotlin Coroutines & `StateFlow` |

---

## 3. Architecture & Codebase Map

```
app/src/main/java/com/locket/app/
├── MainActivity.kt               # Root Activity, edge-to-edge, screen router
├── LocketApplication.kt          # Application class, SessionManager DI singleton
├── model/
│   └── Models.kt                 # Data classes: User, Moment, Friend, Conversation, CalendarDay
├── storage/
│   └── SessionManager.kt         # Token & cached session management (SharedPreferences + Gson)
├── api/
│   └── LocketApiClient.kt        # All API protocol, auth, upload, Firestore & Callable calls
└── ui/
    ├── LocketViewModel.kt        # Central ViewModel, reactive flows, data synchronization
    ├── theme/
    │   ├── Color.kt              # LocketYellow (#FFB800), LocketBlack (#000000), dark palette
    │   └── Theme.kt              # MaterialTheme wrapping
    ├── components/
    │   ├── LiquidGlass.kt        # Custom Compose modifier for frosted glass and glow borders
    │   └── LocketComponents.kt   # TopBar, BottomBar, FeedBottomBar, ShutterButton, FriendFilterSheet
    └── screens/
        ├── WelcomeScreen.kt      # Landing screen with mockup phone, login/signup buttons
        ├── LoginScreen.kt        # Email / Phone OTP authentication screen
        ├── CameraScreen.kt       # Live camera viewfinder, shutter capture, navigations
        ├── HomeMomentScreen.kt   # Feed viewer, caption pill overlay, floating reactions, chat input
        ├── HomeGridScreen.kt     # 3-column grid view of moments with friend filter
        ├── HistoryScreen.kt      # Infinite dynamic calendar history, stats, MemoryDetailDialog
        ├── ConversationsScreen.kt# Chat conversations list
        ├── ChatDetailScreen.kt   # Direct messaging with moment attachment
        ├── FriendsScreen.kt      # Friends list, user search by username, QR, social share
        └── ProfileScreen.kt      # User profile, badges, QR card, direct token login
```

---

## 4. Key Subsystems & Implementation Details

### 4.1 API & Network Protocol (`LocketApiClient.kt`)
* **Base URL:** `https://api.locketcamera.com/{function_name}`
* **Envelope:**
  ```json
  POST https://api.locketcamera.com/<endpoint>
  Headers:
    Content-Type: application/json
    Authorization: Bearer <id_token>
  Body:
    {"data": { ... }}
  Response:
    {"result": {"status": 200, "data": ...}}
  ```
* **Friend Name Resolution:** When fetching friends:
  1. Retrieve friend UIDs from Firestore: `users/{uid}/friends`
  2. Call `fetchUsers({user_uids: [...]})`
  3. For any UID not resolved, fallback to Firestore doc: `users/{friendUid}`
  4. Build clean `displayName = "$first_name $last_name"`, falling back to `username`.

### 4.2 Storage & Image Upload
* **Storage Bucket:** `locket-img`
* **Path:** `users/{uid}/moments/thumbnails/{random20Id}.jpg`
* **MD5 Calculation:** Standard MD5 hash of base64-encoded JPEG bytes.
* **Flow:**
  1. Capture JPEG from CameraX.
  2. Upload to `https://firebasestorage.googleapis.com/v0/b/locket-img/o?uploadType=media&name={path}`.
  3. Call `postMomentV2` callable with `thumbnail_url`, `caption`, `recipients`, `md5`.

### 4.3 History & Memory Viewer (`HistoryScreen.kt`)
* Dynamically generates calendar month blocks backwards from current date.
* Maps moments by `year-month-day`.
* Clicking any day with a photo triggers `MemoryDetailDialog`, presenting:
  - Full-size cropped squircle photo.
  - Author avatar, clean display name, and formatted timestamp (`HH:mm • dd/MM/yyyy`).
  - Translucent caption overlay capsule inside the photo.
  - Reaction picker (`🔥`, `😍`, `💛`, `😂`, `🥺`).
* Bottom stats pill calculates real streak and total moments from `moments.size`.

### 4.4 Friend Filtering (`FriendFilterSheet`)
* In `HomeMomentScreen` and `HomeGridScreen`, clicking the top bar pill opens `FriendFilterSheet`.
* Selecting "Tất cả bạn bè" displays all moments (`selectedFriend = null`).
* Selecting a friend (e.g., "Huy Trần") filters both Feed and Grid to that friend's moments and updates the top bar pill to `"Huy v"`.

---

## 5. Build, Test & CI/CD

### 5.1 Local Build
Ensure `JAVA_HOME` points to JDK 17 and `ANDROID_HOME` points to your Android SDK:
```bash
export JAVA_HOME=/path/to/jdk-17
export ANDROID_HOME=/path/to/android-sdk

# Compile Kotlin syntax
./gradlew compileDebugKotlin

# Build Debug APK
./gradlew assembleDebug
```
The resulting APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

### 5.2 GitHub Actions CI
The workflow `.github/workflows/build.yml` is manual-only (`workflow_dispatch`). Do not add push, pull request, scheduled, or other automatic build triggers. Start a build only when the repository owner explicitly requests one. It:
1. Sets up JDK 17 (Temurin).
2. Sets up Android SDK.
3. Builds `./gradlew assembleDebug`.
4. Uploads the debug APK as a GitHub Actions artifact (`locket-debug-apk`).

---

## 6. Guidelines for AI Agents

When modifying this repository:
1. **Never commit APK or build binaries** into git (`.gitignore` enforces this).
2. **Always test Kotlin compilation** with `./gradlew compileDebugKotlin` before declaring work done.
3. **Verify API responses** against the specifications in `Locket-API-Spec-1.247.3.md` and `locket-api-1.247.3.json`.
4. **Preserve Liquid Glass styling**: Use `.liquidGlassEffect()` modifier rather than flat generic backgrounds for cards and bottom navigation.
5. **Keep Signup disabled** in `WelcomeScreen.kt`.

### Rust core rules

- Business parsing/policy lives in `rust-core`; Android HTTP, Room, UI, media,
  widget, secure storage, package verification and installation stay Kotlin.
- Run `cargo test --manifest-path rust-core/Cargo.toml --locked` and Android
  unit/Kotlin compilation with JDK 17 after boundary changes.
- Build JNI only through `scripts/build-rust-android.sh`; never commit `target/`
  or generated `app/src/main/jniLibs/*.so`.
- Keep the `LKT1` protocol versioned and bounded. JNI input errors must not
  panic or become successful empty data.
- Do not migrate Room to rusqlite without comparable device benchmarks and a
  one-writer transactional migration/rollback plan.
- The GitHub workflow must remain `workflow_dispatch`; do not run it unless the
  repository owner explicitly asks for an APK build.
