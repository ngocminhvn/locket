# Locket update channels

- Run workflow: checkbox Release off (default) => 1.0.<run>dev; on => 1.0.<run>R. Both are signed Android release builds with R8 and the same package/certificate. A marker-triggered explicit build defaults to Dev unless its commit contains [release build].
- Source: GitHub Pages (Settings > Pages > Build and deployment > Source > GitHub Actions). Deploy the complete site each time, preserving the other channel using the latest successful Pages snapshot or verified public files. No new GitHub Release or tag.
- Tap version: Release. Hold version: Dev. Explicit channel only, no fallback to another channel. Monotonic versionCode: no downgrade to an older Release after installing a newer Dev.
- APK and XZ payload checks include length, SHA-256, expected URL, channel/version, APK package and signature (Android installer final verification).
- Older APKs still check GitHub Releases. Existing historical releases are untouched; installing a new Pages APK manually once is required for those versions. Do not create a bridge tag automatically without owner approval.
- CI not triggered by ordinary source commits. First Pages deployment requires repository Pages source to be enabled. No build/device check was run in this change.
