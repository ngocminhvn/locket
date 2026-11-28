#!/usr/bin/env python3
"""Publish one flat Pages directory with one shared, two-channel manifest.
Recover the prior successful deployment from its snapshot or the live site.
Only the replaced channel's old APK/XZ files are removed.
"""
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import urllib.error
import urllib.request
import time

repo = os.environ["GITHUB_REPOSITORY"]
channel = os.environ["LOCKET_CHANNEL"]
assert channel in ("release", "dev")
base = "https://ngocminhvn.github.io/locket/"
pages = Path("pages")
pages.mkdir(exist_ok=True)
current = json.loads(Path("output/update.json").read_text())
cert = os.environ["LOCKET_CERT_SHA256"].lower()
run = int(os.environ["GITHUB_RUN_ID"])
names = ("release", "dev")

def gh(*args):
    return subprocess.check_output(["gh", *args], text=True)

def fetch(url, target, optional=False):
    request = urllib.request.Request(url + "?t=" + str(time.time_ns()),
        headers={"Cache-Control": "no-cache", "User-Agent": "Locket-Pages-Publisher"})
    for attempt in range(3):
        try:
            with urllib.request.urlopen(request, timeout=90) as response, target.open("wb") as out:
                shutil.copyfileobj(response, out)
            return True
        except urllib.error.HTTPError as exc:
            if optional and exc.code == 404:
                target.unlink(missing_ok=True)
                return False
            if attempt == 2: raise
        except (OSError, TimeoutError):
            if attempt == 2: raise
        time.sleep(2 ** attempt)
    raise RuntimeError("Download failed: " + url)

def filenames(name, manifest):
    build = manifest["versionCode"]
    assert isinstance(build, int) and not isinstance(build, bool) and build > 0
    suffix = "R" if name == "release" else "dev"
    version = f"1.0.{build}{suffix}"
    assert manifest["status"] == name and manifest["versionName"] == version
    assert manifest["certificateSha256"].lower() == cert
    assert manifest["compression"] == "xz"
    apk = f"Locket-{version}.apk"
    assert manifest["apkFile"] == apk and manifest["xzFile"] == apk + ".xz"
    return apk, apk + ".xz"

def verify(name, manifest, folder):
    apk, xz = filenames(name, manifest)
    for filename, size_key, hash_key in [
        (apk, "sizeBytes", "sha256"),
        (xz, "packageSizeBytes", "packageSha256"),
    ]:
        path = folder / filename
        assert 0 < path.stat().st_size == manifest[size_key]
        with path.open("rb") as source:
            assert hashlib.file_digest(source, "sha256").hexdigest() == manifest[hash_key].lower()

def read_index(path):
    data = json.loads(path.read_text())
    assert data["schemaVersion"] == 1
    result = {name: data[name] for name in names}
    available = [name for name in names if result[name] is not None]
    assert available
    assert data["latestStatus"] == max(available, key=lambda name: result[name]["versionCode"])
    for name in available:
        filenames(name, result[name])
    return result

# A successful deploy job identifies an authoritative snapshot. Legacy build
# runs never had this job and do not represent Pages publication.
runs = json.loads(gh("api", f"repos/{repo}/actions/workflows/build.yml/runs?status=success&branch=main&per_page=100"))["workflow_runs"]
previous = None
for item in runs:
    if item["id"] == run: continue
    jobs = json.loads(gh("api", f"repos/{repo}/actions/runs/{item['id']}/jobs?per_page=100"))["jobs"]
    if any(j["name"] == "deploy" and j["conclusion"] == "success" for j in jobs):
        previous = item["id"]
        break

restored = False
if previous:
    artifacts = json.loads(gh("api", f"repos/{repo}/actions/runs/{previous}/artifacts?per_page=100"))["artifacts"]
    snapshot = next((a for a in artifacts if a["name"] == "locket-pages-snapshot" and not a["expired"]), None)
    if snapshot:
        subprocess.run(["gh", "run", "download", str(previous), "--repo", repo, "--name", "locket-pages-snapshot", "--dir", str(pages)], check=True)
        restored = True

index = pages / "update.json"
if restored:
    channels = read_index(index)
elif fetch(base + "update.json", index, optional=previous is None):
    channels = read_index(index)
    for name in names:
        if channels[name] is not None:
            for filename in filenames(name, channels[name]):
                fetch(base + filename, pages / filename)
else:
    # No prior successful Pages deployment; both channels start empty.
    channels = dict.fromkeys(names)

for name in names:
    if channels[name] is not None:
        verify(name, channels[name], pages)
verify(channel, current, Path("output"))
prior = channels[channel]
if prior is not None and prior["versionCode"] >= current["versionCode"]:
    if prior["versionCode"] != current["versionCode"] or prior["sha256"] != current["sha256"]:
        raise RuntimeError("Refusing to overwrite channel with same/older incompatible build")

channels[channel] = current
keep = set()
for name in names:
    if channels[name] is not None:
        keep.update(filenames(name, channels[name]))
for filename in filenames(channel, current):
    shutil.copyfile(Path("output") / filename, pages / filename)
# Only remove known old payloads belonging to the replaced channel.
if prior is not None:
    for filename in filenames(channel, prior):
        if filename not in keep:
            (pages / filename).unlink(missing_ok=True)
for name in names:
    if channels[name] is not None:
        verify(name, channels[name], pages)

latest = max((name for name in names if channels[name] is not None),
             key=lambda name: channels[name]["versionCode"])
data = {"schemaVersion": 1, "latestStatus": latest, **channels}
index.write_text(json.dumps(data, indent=2) + "\n")
(pages / ".nojekyll").touch()
(pages / "index.html").write_text(
    '<!doctype html><meta charset="utf-8"><title>Locket downloads</title><h1>Locket</h1>'
    + "".join(f'<p><a href="{entry["apkFile"]}">{name}: {entry["versionName"]}</a></p>'
              for name, entry in channels.items() if entry is not None))
print(json.dumps(data, indent=2))
