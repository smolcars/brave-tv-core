# Signed Android filter snapshots

The packager, native verifier, atomic staging, provider integration, HTTPS
streaming delivery and TV status/manual-check/restart UI are implemented.
Public-feed publication and APK acceptance of delivery/UI remain pending.
Seventeen focused native tests pass, including real signed storage, bounded
streaming above 5 MiB, rejection, timeout, daily scheduling and HTTPS-only
redirects. Earlier APK acceptance establishes provider load/cache behavior and
cold-start blocking; it does not yet establish real network delivery.

The fixed feed is the `filters-current` release asset `filters.bundle` in
`smolcars/brave-tv`. Requests omit credentials, enforce a 24 MiB limit and a
60-second deadline, and reject HTTP redirects. Automatic checks begin no sooner
than 30 seconds after service startup and run at most daily; manual checks can
run sooner. The last attempt persists across restart. A verified download is
staged atomically and becomes active only in a new browser process. The TV
panel distinguishes active publication date, last attempt and restart state.
The restart action requires explicit confirmation and uses Chromium's session
termination path; runtime restoration still needs acceptance.

First refresh and test the pinned snapshot using the instructions in
`components/brave_shields/resources/bundled/README.md`. The signer checks every
snapshot file against its recorded hash and rejects unexpected files in the
provenance. Keep the catalog and its permissions matched to the target APK.

From the project Nix shell:

```sh
node brave/tools/tv/sign-adblock-bundle.mjs \
  --key /private/path/publisher-ed25519.pem \
  --output /artifact/path/filters.bundle \
  --sequence 1 \
  --published 2026-10-10T00:00:00.000Z
node --test brave/tools/tv/sign-adblock-bundle.test.mjs
```

Use a strictly increasing positive sequence for each publication and the actual
UTC publication timestamp. The output must not already exist. Repeating the
same inputs produces identical bytes. Output metadata includes the SHA-256
digest and public key (raw Ed25519 bytes encoded as base64url), never the private
key. Store and back up the publisher key outside Git with owner-only access;
do not include it in logs or release assets. Tests generate disposable keys.

The file contains eight ASCII magic bytes `TVAB0001`, a 64-byte Ed25519 signature,
and UTF-8 JSON. The signature covers the exact JSON bytes. Maximum total size
is 24 MiB. The JSON has format `1`, engine `adblock-rs-0.13.2`, an integer
sequence in [1, 2147483647], canonical UTC ISO `published`, `catalog_sha256`,
`source_version`, the original `provenance`, the resource JSON as a string, and
a `filters` dictionary containing exactly the five compiled-in component IDs.
It carries no replacement catalog or adjustable permission masks.

The native consumer must verify the pinned-key signature before JSON parsing,
then validate schema, engine, catalog identity, monotonic version and freshness.
Do not infer acceptance from this packager's tests: native rejection, atomic
storage, offline fallback and restart/cache transition need their own checks.
Retain source notices inside the lists and publish source/provenance/license
links with each eventual feed artifact.
