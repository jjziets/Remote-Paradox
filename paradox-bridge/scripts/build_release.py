#!/usr/bin/env python3
"""Build an immutable bridge archive and sign its manifest in GitHub Actions."""
import hashlib
import json
import os
import re
import subprocess
import tarfile
import tomllib
from pathlib import Path

from cryptography.hazmat.primitives.serialization import load_pem_private_key, load_pem_public_key


def build(output: Path, tag: str) -> None:
    if not re.fullmatch(r"bridge-v\d+\.\d+\.\d+", tag):
        raise ValueError("Expected a bridge-vX.Y.Z tag")
    output.mkdir(parents=True, exist_ok=True)
    archive = output / "paradox-bridge.tar.gz"
    with archive.open("wb") as stream:
        subprocess.run(["git", "archive", "--format=tar.gz", "--prefix=release/", "HEAD", "paradox-bridge"], stdout=stream, check=True)
    with tarfile.open(archive) as tar:
        files = {m.name: hashlib.sha256(tar.extractfile(m).read()).hexdigest() for m in tar if m.isfile()}
        config = tomllib.loads(tar.extractfile("release/paradox-bridge/pyproject.toml").read().decode())
    version = config["project"]["version"]
    if tag != f"bridge-v{version}":
        raise ValueError("Tag does not match bridge package version")
    manifest = {
        "schema": 1, "repository": "jjziets/Remote-Paradox", "tag": tag,
        "version": version, "commit": subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip(),
        "archive": archive.name, "sha256": hashlib.sha256(archive.read_bytes()).hexdigest(), "files": files,
    }
    raw = (json.dumps(manifest, sort_keys=True, separators=(",", ":")) + "\n").encode()
    key = load_pem_private_key(os.environ["BRIDGE_SIGNING_KEY"].encode(), password=None)
    signature = key.sign(raw)
    load_pem_public_key(Path("paradox-bridge/deploy/bridge-release.pub").read_bytes()).verify(signature, raw)
    (output / "bridge-manifest.json").write_bytes(raw)
    (output / "bridge-manifest.sig").write_bytes(signature)
    print(f"Signed {tag} at {manifest['commit']}")


if __name__ == "__main__":
    import argparse
    parser = argparse.ArgumentParser()
    parser.add_argument("tag")
    parser.add_argument("--output", type=Path, default=Path("dist/bridge"))
    args = parser.parse_args()
    build(args.output, args.tag)
