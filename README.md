# 💛 Locket — Native Android Client

[![Build Locket Android APK](https://github.com/ngocminhvn/locket/actions/workflows/build.yml/badge.svg)](https://github.com/ngocminhvn/locket/actions/workflows/build.yml)

A native Android client clone of **Locket Camera** built with **Kotlin**, **Jetpack Compose**, **Material 3 Expressive**, **CameraX**, and **Liquid Glass** UI effects.

Directly integrates with the live Locket production protocol:
* **Firebase Cloud Functions (v2 Callable):** `https://api.locketcamera.com`
* **Firebase Identity Toolkit (REST Auth):** `https://identitytoolkit.googleapis.com`
* **Google Cloud Storage Bucket:** `gs://locket-img`
* **Google Cloud Firestore (REST and Listen):** Real-time users, friends, history, and conversations.

---

## ✨ Features

- 📸 **CameraX Viewfinder**: High-performance photo capture, flash toggle, front/rear camera flip, and smooth spring shutter animation.
- 🖼️ **Interactive Feed & Moments**: Squircle aspect-ratio moment view, in-photo caption capsule, floating emoji reaction animations (`🔥`, `😍`, `💛`), and direct messaging input.
- 👥 **Friend Filter Dropdown**: Filter moments and 3-column grid by all friends or a specific friend (e.g., "Huy Trần" -> "Huy v").
- 📅 **Infinite History & Memory Viewer**: Dynamic multi-month scrollable calendar covering 2026 and 2025. Tap any photo day to open the full-screen `MemoryDetailDialog` with high-resolution image, author details, exact timestamp (`HH:mm • dd/MM/yyyy`), caption, and reaction bar.
- 💬 **Live Conversations & Chat**: Real-time conversation stream, direct chat detail with moment attachments and sent/received chat bubbles.
- 🤝 **Friends Management**: Live friends list, username search (`searchUserByUsername`), friend deletion, and multi-channel social sharing (QR Code, Messenger, Zalo, Instagram).
- 🎨 **Liquid Glass UI**: Material 3 Expressive design with frosted glass blur, ambient glow borders, and translucent dark capsules.

---

## 🛠️ Build & Development

### Requirements
- **JDK 17** (e.g. Eclipse Temurin 17)
- **Android SDK** (compile SDK 37.1; target 34, minimum 26)
- **Rust toolchain**, Android NDK and `cargo-ndk` for the native core; see [Rust core](docs/rust-core.md).

### Local Build
```bash
# Clone the repository
git clone https://github.com/ngocminhvn/locket.git
cd locket

# Build native core and Debug APK
./scripts/build-rust-android.sh
./gradlew assembleDebug
```
The output APK is generated at:
`app/build/outputs/apk/debug/app-debug.apk`

---

## 🤖 AI Agent & Architecture Guide

For architecture and developer rules, see [AGENT.md](AGENT.md). API contracts: [human-readable specification](Locket-API-Spec-1.247.3.md) and [JSON specification](locket-api-1.247.3.json). Implementation notes and verification checklist: [audit status](docs/loading-audit-v2-status.md).

## APK downloads and updates

[Download from GitHub Pages](https://ngocminhvn.github.io/locket/). Run the [build workflow](https://github.com/ngocminhvn/locket/actions/workflows/build.yml) manually: Release unchecked builds Dev, checked builds Release. In the app, tap the version to check Release or hold it to check Dev. See [update channels and signing](docs/update-channels.md).

---

## 📄 License
The project's own source code, to the extent the project has the rights to license it, is distributed under the **GNU General Public License v3.0 only (GPL-3.0-only)**. See [LICENSE](LICENSE).

Earlier versions released under Apache-2.0 remain available under their original license; previously granted rights are not revoked. Applicable earlier material remains subject to its original Apache-2.0 terms. Third-party dependencies, fonts, and other assets retain their respective licenses.

This is an unofficial client; no rights to Locket's trademarks, proprietary assets, or services are granted by this license.
