#!/usr/bin/env python3
"""Preserve both channels across full-site Pages deployments.
Use the last successfully deployed workflow snapshot as authoritative prior state;
recover from public Pages if the artifact expired. Never silently drop a known channel.
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
base = "https://ngocminhvn.github.io/locket/"
pages = Path("pages")
pages.mkdir(exist_ok=True)
current = json.loads(Path("output/update.json").read_text())
cert = os.environ["LOCKET_CERT_SHA256"].lower()
run = int(os.environ["GITHUB_RUN_ID"])

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

def verify(name, folder):
    manifest = json.loads((folder / "update.json").read_text())
    build = int(manifest["versionCode"])
    suffix = "R" if name == "release" else "dev"
    assert manifest["channel"] == name and manifest["versionName"] == f"1.0.{build}{suffix}"
    assert manifest["certificateSha256"].lower() == cert
    assert manifest["apkUrl"] == base + name + "/Locket.apk"
    assert manifest["packageUrl"] == base + name + "/Locket.apk.xz"
    assert manifest["compression"] == "xz"
    for filename, size_key, hash_key in [
        ("Locket.apk", "sizeBytes", "sha256"),
        ("Locket.apk.xz", "packageSizeBytes", "packageSha256"),
    ]:
        path = folder / filename
        assert path.stat().st_size == manifest[size_key]
        with path.open("rb") as source:
            assert hashlib.file_digest(source, "sha256").hexdigest() == manifest[hash_key].lower()
    return manifest

# Workflow history is serialized; an earlier successful workflow with a deploy
# job is authoritative. Ignore legacy runs that never deployed Pages.
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

if not restored:
    index = pages / "channels.json"
    has_index = fetch(base + "channels.json", index, optional=previous is None)
    expected = set(json.loads(index.read_text())["channels"]) if has_index else set()
    if previous and not has_index:
        raise RuntimeError("Missing previously deployed Pages index")
    for name in ("release", "dev"):
        folder = pages / name
        folder.mkdir(exist_ok=True)
        exists = fetch(base + name + "/update.json", folder / "update.json", optional=name not in expected)
        if exists:
            for filename in ("Locket.apk", "Locket.apk.xz"):
                fetch(base + name + "/" + filename, folder / filename)
            verify(name, folder)
        else:
            folder.rmdir()
if (pages / "channels.json").exists():
    for name in json.loads((pages / "channels.json").read_text())["channels"]:
        assert name in ("release", "dev") and (pages / name / "update.json").is_file()

for name in ("release", "dev"):
    if (pages / name / "update.json").exists():
        prior = verify(name, pages / name)
        if name == channel and prior["versionCode"] >= current["versionCode"]:
            # Re-running the same run is permitted only for identical bytes.
            if prior["versionCode"] != current["versionCode"] or prior["sha256"] != current["sha256"]:
                raise RuntimeError("Refusing to overwrite channel with same/older incompatible build")

folder = pages / channel
folder.mkdir(exist_ok=True)
version = current["versionName"]
for source, dest in [(f"Locket-{version}.apk", "Locket.apk"), (f"Locket-{version}.apk.xz", "Locket.apk.xz"), ("update.json", "update.json")]:
    shutil.copyfile(Path("output") / source, folder / dest)
channels = {}
for name in ("release", "dev"):
    if (pages / name / "update.json").exists():
        channels[name] = verify(name, pages / name)["versionName"]
(pages / "channels.json").write_text(json.dumps({"channels": channels}, indent=2) + "\n")
(pages / ".nojekyll").touch()
(pages / "index.html").write_text('<!doctype html><meta charset="utf-8"><title>Locket downloads</title><h1>Locket</h1>' + "".join(f'<p><a href="{name}/Locket.apk">{name}: {version}</a></p>' for name, version in channels.items()))
print(channels)
