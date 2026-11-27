# In-app updates

Manual builds use the GitHub Actions run number for versionCode and 1.0.<run number> for versionName. CI explicitly signs assembleRelease using the existing Locket certificate; it does not use an automatically generated debug key. R8 remains unchanged.

Keep the existing repository secret LOCKET_DEBUG_KEYSTORE_B64 (alias androiddebugkey, store/key password android). Never replace it or commit signing material. The build fails if it is absent. The signing configuration is injected into Gradle, like TKB SV; Locket does not reuse the TKB key.

Before publishing, CI verifies the APK with apksigner, compares its certificate SHA-256 to the configured keystore, then compares with the latest released APK and v1.0.23 if present. A mismatch or failure to download an existing comparison APK blocks publication. This intentionally protects users from incompatible updates. If releases 23 and 24 are already signed differently, resolve that conflict with the original key before publishing; do not bypass the guard.

GitHub Releases include the signed APK and update.json (version, URL, size, SHA-256 file digest, certificate SHA-256). Mod Center checks the manifest and downloaded file hash; existing releases without a manifest use the GitHub asset digest when available. Android's installer enforces signature continuity. If archive signing metadata is missing, the updater does not falsely label it a different certificate. A confirmed mismatch is still blocked.

The workflow remains manual only. No automatic build triggers.
