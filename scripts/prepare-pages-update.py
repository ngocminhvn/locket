#!/usr/bin/env python3
"""Create a manifest for the exact signed APK and compressed package."""
import hashlib
import json
import os
from pathlib import Path

channel = os.environ["LOCKET_CHANNEL"]
assert channel in ("release", "dev")
build = int(os.environ["GITHUB_RUN_NUMBER"])
version = f"1.0.{build}" + ("R" if channel == "release" else "dev")
apk = Path("output") / f"Locket-{version}.apk"
package = Path(str(apk) + ".xz")
base = "https://ngocminhvn.github.io/locket/" + channel + "/"

def digest(path):
    with path.open("rb") as source:
        return hashlib.file_digest(source, "sha256").hexdigest()

manifest = {
    "channel": channel,
    "versionCode": build,
    "versionName": version,
    "apkUrl": base + "Locket.apk",
    "packageUrl": base + "Locket.apk.xz",
    "compression": "xz",
    "sha256": digest(apk),
    "sizeBytes": apk.stat().st_size,
    "packageSha256": digest(package),
    "packageSizeBytes": package.stat().st_size,
    "certificateSha256": os.environ["LOCKET_CERT_SHA256"],
    "commit": os.environ["GITHUB_SHA"],
}
Path("output/update.json").write_text(json.dumps(manifest, indent=2) + "\n")
print(json.dumps(manifest, indent=2))
