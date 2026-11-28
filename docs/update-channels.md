# Locket update channels

- Run workflow: checkbox Release off (default) => 1.0.<run>dev; on => 1.0.<run>R. Both are signed Android release builds with R8 and the same package/certificate. An explicit marker-triggered build defaults to Dev unless its commit contains [release build].
- GitHub Pages (Settings > Pages > Source > GitHub Actions). One flat folder, one update.json with schemaVersion, latestStatus (highest versionCode), and independent release/dev entries (null until published). Entries contain status, versionCode/versionName, apkFile/xzFile, compressed/uncompressed lengths and SHA-256, certificate and commit. The APK names contain the actual version; no URL or folder is duplicated per entry.
- The updater reads the same manifest for either gesture. Tap version: Release. Hold version: Dev. No automatic cross-channel fallback. Monotonic versionCode prevents installing older Release over newer Dev.
- Preserve only the latest APK and XZ for each channel using prior successful Pages snapshot or verified live files. Remove only the superseded files of the channel being updated. No extra tag, release, branch, or duplicated payload in the published site. Snapshot artifacts are retained for recovery.
- APK and XZ payload checks include length, SHA-256, strict file names, channel/version, APK package and signature (Android installer final verification).
- The earlier two-directory scheme was never deployed (Pages disabled when changed). Existing GitHub Releases untouched; old APKs need a one-time manual install of a Pages-capable build.
- CI not triggered by ordinary source commits. Pages must be enabled before deployment. No build/device check was run in this change.
