"""Sign the records into a bundle and write the static sync site the app reads.

    uv run --with cryptography tools/records/publish.py \
        --records ../kuya-j-records/records --site ../kuya-j-records \
        --base-url https://mr-jones123.github.io/kuya-j-records

Writes, under --site:
    api/v1/sync/kb              the signed manifest (what the app asks for first)
    kb/bundle-<version>.json    the exact bytes the manifest signs (the three newest are kept)

The private key lives at ~/.kuyaj/signing-key.pem (override with --key) and is created on the first run, readable
only by you. It never goes in a repository. The public key and its id are printed: put them in the app's
gradle.properties as kuyaj.syncPublicKeys=<keyId>:<publicKey>.

The format matches the app's BundleSync/BundleVerifier (and Airgap's sync contract): Ed25519 over the exact bundle
bytes, keyId = first 16 hex characters of SHA-256 over the raw 32-byte public key.
"""

import argparse
import base64
import datetime as dt
import hashlib
import json
import os
from pathlib import Path

from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey, Ed25519PublicKey

DEFAULT_KEY = Path.home() / ".kuyaj" / "signing-key.pem"
KEEP_BUNDLES = 3  # older bundles are deleted; phones mid-download of a recent one can still finish


def load_or_create_key(path: Path) -> Ed25519PrivateKey:
    if path.exists():
        return serialization.load_pem_private_key(path.read_bytes(), password=None)
    path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    key = Ed25519PrivateKey.generate()
    pem = key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption())
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, "wb") as f:
        f.write(pem)
    print(f"created a new signing key at {path} (keep it safe and backed up; never commit it)")
    return key


def public_raw(key: Ed25519PrivateKey) -> bytes:
    return key.public_key().public_bytes(serialization.Encoding.Raw, serialization.PublicFormat.Raw)


def key_id(raw: bytes) -> str:
    return hashlib.sha256(raw).hexdigest()[:16]


def build_bundle(records_dir: Path, generated_at: str) -> bytes:
    files = {}
    for f in sorted(records_dir.glob("*.json")):
        text = f.read_text()
        recs = json.loads(text)
        assert isinstance(recs, list) and recs, f"{f.name}: not a non-empty list of records"
        for r in recs:  # the same required fields the app's KbRecord.parseArray checks
            for k in ("id", "category", "title", "content"):
                assert isinstance(r.get(k), str) and r[k], f"{f.name}: record without {k}"
            assert isinstance(r.get("keywords"), list), f"{f.name}: {r['id']} has no keywords list"
        files[f.name] = text
    return json.dumps({"files": files, "generatedAt": generated_at}, ensure_ascii=False, separators=(",", ":")).encode()


def manifest(bundle: bytes, key: Ed25519PrivateKey, version: str, url: str, published_at: str) -> dict:
    raw = public_raw(key)
    return {
        "algorithm": "Ed25519",
        "signatureEncoding": "base64",
        "byteLength": len(bundle),
        "sha256": hashlib.sha256(bundle).hexdigest(),
        "version": version,
        "keyId": key_id(raw),
        "url": url,
        "publishedAt": published_at,
        "signature": base64.b64encode(key.sign(bundle)).decode(),
    }


def verify(bundle: bytes, m: dict, public_key_b64: str) -> None:
    """The app's checks, in the same order, so a bad publish fails here and not on phones."""
    assert m["algorithm"] == "Ed25519" and m["signatureEncoding"] == "base64"
    assert m["byteLength"] == len(bundle), "length"
    assert m["sha256"] == hashlib.sha256(bundle).hexdigest(), "sha256"
    raw = base64.b64decode(public_key_b64)
    assert m["keyId"] == key_id(raw), "keyId"
    Ed25519PublicKey.from_public_bytes(raw).verify(base64.b64decode(m["signature"]), bundle)
    body = json.loads(bundle.decode("utf-8"))
    assert dt.datetime.fromisoformat(body["generatedAt"].replace("Z", "+00:00"))


def main() -> None:
    p = argparse.ArgumentParser()
    p.add_argument("--records", required=True, help="folder of record JSON arrays")
    p.add_argument("--site", required=True, help="the static site folder (the kuya-j-records repo)")
    p.add_argument("--base-url", required=True, help="what the app's kuyaj.syncBaseUrl will be")
    p.add_argument("--key", type=Path, default=DEFAULT_KEY)
    a = p.parse_args()

    key = load_or_create_key(a.key)
    pub = base64.b64encode(public_raw(key)).decode()
    now = dt.datetime.now(dt.timezone.utc).replace(microsecond=0)
    stamp = now.strftime("%Y-%m-%dT%H:%M:%SZ")  # also the version: the app compares its digit groups in order
    bundle = build_bundle(Path(a.records), stamp)
    if len(bundle) > 50 * 1024 * 1024:
        raise SystemExit("bundle is over the app's 50 MB limit")

    site = Path(a.site)
    name = f"bundle-{now.strftime('%Y%m%dT%H%M%SZ')}.json"
    url = f"{a.base_url.rstrip('/')}/kb/{name}"
    m = manifest(bundle, key, stamp, url, stamp)
    verify(bundle, m, pub)

    (site / "kb").mkdir(parents=True, exist_ok=True)
    (site / "kb" / name).write_bytes(bundle)
    (site / "api" / "v1" / "sync").mkdir(parents=True, exist_ok=True)
    (site / "api" / "v1" / "sync" / "kb").write_text(json.dumps(m, indent=1) + "\n")
    (site / ".nojekyll").touch()
    for old in sorted((site / "kb").glob("bundle-*.json"))[:-KEEP_BUNDLES]:
        old.unlink()

    print(f"published version {stamp}: {len(bundle):,} bytes, sha256 {m['sha256'][:16]}…, {url}")
    print(f"app setting: kuyaj.syncPublicKeys={m['keyId']}:{pub}")


if __name__ == "__main__":
    k = Ed25519PrivateKey.generate()
    b = b'{"files":{"a.json":"[]"},"generatedAt":"2026-01-01T00:00:00Z"}'
    test = manifest(b, k, "v", "https://x/kb/b.json", "2026-01-01T00:00:00Z")
    verify(b, test, base64.b64encode(public_raw(k)).decode())
    try:
        verify(b.replace(b"[]", b"[ ]"), test, base64.b64encode(public_raw(k)).decode())
        raise SystemExit("self-check failed: a changed bundle verified")
    except AssertionError:
        pass
    main()
