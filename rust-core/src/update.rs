use serde::Deserialize;

use crate::CoreError;

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct Manifest {
    version_code: i64,
    version_name: String,
    apk_url: String,
    sha256: String,
    size_bytes: i64,
}

pub fn verify_manifest(
    bytes: &[u8],
    build: i64,
    version: &str,
    url: &str,
    size: i64,
    asset_digest: Option<&str>,
) -> Result<String, CoreError> {
    let manifest: Manifest =
        serde_json::from_slice(bytes).map_err(|_| CoreError::Invalid("invalid update manifest"))?;
    if manifest.version_code != build
        || manifest.version_name != version
        || manifest.apk_url != url
        || manifest.size_bytes != size
    {
        return Err(CoreError::Invalid("update manifest does not match release"));
    }
    if manifest.sha256.len() != 64
        || !manifest
            .sha256
            .bytes()
            .all(|value| value.is_ascii_hexdigit())
    {
        return Err(CoreError::Invalid("invalid update SHA-256"));
    }
    if asset_digest.is_some_and(|digest| !digest.eq_ignore_ascii_case(&manifest.sha256)) {
        return Err(CoreError::Invalid("release digest does not match manifest"));
    }
    Ok(manifest.sha256.to_ascii_lowercase())
}

#[cfg(test)]
mod tests {
    use super::verify_manifest;
    #[test]
    fn validates_all_release_bound_fields() {
        let hash = "a".repeat(64);
        let bytes = format!(
            r#"{{"versionCode":29,"versionName":"1.0.29","apkUrl":"https://example.invalid/Locket.apk","sha256":"{hash}","sizeBytes":123}}"#
        );
        assert_eq!(
            verify_manifest(
                bytes.as_bytes(),
                29,
                "1.0.29",
                "https://example.invalid/Locket.apk",
                123,
                Some(&hash)
            )
            .unwrap(),
            hash
        );
        assert!(verify_manifest(
            bytes.as_bytes(),
            30,
            "1.0.29",
            "https://example.invalid/Locket.apk",
            123,
            None
        )
        .is_err());
    }
}
