# In-app updates

The manual build workflow uses GitHub Actions run number as versionCode and 1.0.<run number> as versionName. It always exports a debug APK artifact; it publishes a GitHub Release only on main with a persistent signing key.

Set repository Actions secret LOCKET_DEBUG_KEYSTORE_B64 to the base64 encoding of your existing debug keystore (alias androiddebugkey, store/key password android). Keep the same key for every build. Never commit the keystore. If prior APKs used ephemeral runner keys, they cannot be updated in place by a different certificate; one-time migration is necessary. Releases are checked manually in Mod Center. The app verifies package ID, version code and signing certificates, then opens the Android installer for user confirmation.

No automatic build triggers.
