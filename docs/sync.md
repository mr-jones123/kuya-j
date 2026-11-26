# Record sync

Records change: fees go up, officials change, new holidays are proclaimed. Kuya J can download updated records as
a **signed bundle** over Wi-Fi. The design is Airgap's: whoever controls the update server still can't push records
the app will accept unless they hold the signing key.

The app syncs from **https://mr-jones123.github.io/kuya-j-records** (GitHub Pages), signed with the publisher's key.
How records are made and published: [The records pipeline](records-pipeline.md). Without a server and key configured,
the app runs on its built-in records only.

## The flow

```mermaid
sequenceDiagram
    participant W as SyncWorker (every 6 h, Wi-Fi)
    participant S as Sync server
    participant V as BundleVerifier
    participant K as KnowledgeStore
    W->>S: GET {base}/api/v1/sync/kb
    S-->>W: signed manifest
    W->>W: check manifest, same origin, newer version, size limit
    W->>S: GET manifest.url
    S-->>W: bundle bytes
    W->>V: length, SHA-256, pinned key, Ed25519 signature, UTF-8, schema
    V-->>W: ok
    W->>K: install: current → previous, new → current, rebuild search
```

## The manifest

`GET {base}/api/v1/sync/kb` returns:

| Field | Must be |
|---|---|
| `algorithm` | `Ed25519` |
| `signatureEncoding` | `base64` |
| `byteLength` | Exact size of the bundle |
| `sha256` | Lowercase hex SHA-256 of the bundle |
| `version` | Release ID; numbers or an ISO time, compared digit group by group |
| `keyId` | First 16 hex characters of SHA-256 over the raw public key |
| `url` | HTTPS, on the same origin as the base URL |
| `publishedAt` | ISO date-time |
| `signature` | Base64 Ed25519 signature over the exact bundle bytes |

## The bundle

```json
{ "files": { "fees.json": "[ ...records as a JSON string... ]", "hotlines.json": "[ ... ]" },
  "generatedAt": "2026-12-01T00:00:00Z" }
```

Each file is the same JSON array as `assets/kb/`. The signature covers these exact bytes, so nothing may re-format
the JSON between signing and download.

## Every check before a byte is trusted

1. The manifest has every field, the right algorithm, and an HTTPS URL on the configured origin. Redirects are
   refused.
2. The version is newer than the installed one (an older or equal version is skipped, so a replayed old bundle
   can't roll records back).
3. The bundle is at most 50 MB, and the download stops if it grows past the declared length.
4. Length and SHA-256 match the manifest.
5. The `keyId` is one of the app's **pinned public keys**, and the Ed25519 signature verifies.
6. The bytes are valid UTF-8, `generatedAt` is a date, and every record parses with its required fields.

Any failure leaves the current records untouched. Network errors are retried; a bad signature is not, since a retry
won't fix it.

## Installing

`BundleSync.install()` writes the new bundle to a partial file, moves the current bundle to `bundle-previous`,
moves the new one into place, and rebuilds the search index. If loading fails, it restores the previous bundle.
The version is saved only after all of that succeeds. On the next start, `loadInstalled()` loads the current
bundle, or falls back to the built-in records.

## Configuring

In `gradle.properties`:

```properties
kuyaj.syncBaseUrl=https://mr-jones123.github.io/kuya-j-records
kuyaj.syncPublicKeys=a71fd704bf13d226:8e6y53BmSEvqS0KqtpsPw/t21qm10NNDFufdkPh1bMw=
```

Several keys may be listed, comma-separated. That allows rotating keys: ship an app that trusts the old and new
key, sign with the new one, then drop the old key in a later release. The **private** key never goes in the app or
any repository.

Debug builds may also sync over plain HTTP from `10.0.2.2` (the emulator's host), for a local test server; release
builds require HTTPS.

## Built-in records come first when they're newer

The app's built-in records carry their publish version in `assets/kb/VERSION`. A downloaded bundle is only used,
and only downloaded, if it's newer than that. So an app update that ships fresher records isn't overridden by an
older bundle the phone downloaded before.

## When it runs

`SyncWorker` (WorkManager) runs every 6 hours on an unmetered network (Wi-Fi), and once at startup. *Update on next
Wi-Fi* in Settings queues an immediate run that still waits for Wi-Fi. After every run the settings sheet's
freshness updates, and answers carry a [staleness note](answering.md#staleness) when records are over a day old.

## Hosting and scale

The host only serves two static files over HTTPS: the manifest and the bundle, on the same origin, with no
redirects. No server code, no login; the signature is what the app trusts.

| Per phone | Size | How often |
|---|---|---|
| Manifest | about 1 KB | Every 6 hours on Wi-Fi |
| Bundle | about 630 KB, about 77 KB compressed | Only when there's a new version |
| Model (from Hugging Face) | 3 GB | Once |

WorkManager spreads syncs over each phone's own 6-hour window, so a release trickles out rather than spiking.

- **GitHub Pages** (now): free, every release is an auditable commit; fine to tens of thousands of users (about
  100 GB a month of bandwidth, a soft limit).
- **Cloudflare Pages or R2** (at scale): free egress and edge caching; handles millions of phones. A mirror of the
  3 GB model there would also take load off Hugging Face.

Moving hosts means publishing with the new `--base-url` and shipping an app update with the new
`kuyaj.syncBaseUrl`; the keys don't change.

Verified end to end on an emulator: the app fetched the manifest from GitHub Pages, downloaded and verified the
bundle, and switched to 478 records (Settings → Records: "Downloaded update").

Tested in `BundleVerifierTest`: a genuine bundle passes; a changed byte, an attacker's bundle with a matching hash,
an unpinned key, and HTTP in a release build are all rejected; versions order correctly.
