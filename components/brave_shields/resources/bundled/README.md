# Android filter baseline

These public upstream data files provide Android's packaged Shields baseline.
With `IndependentAdBlockUpdates` enabled (the default), the five baseline groups
and resources use one verified cached snapshot for the process, or these APK
files when no cache is valid. The catalog and permissions remain fixed by the
APK. Their duplicate upstream components are not registered. With the feature
disabled, the providers prefer upstream signed components and use the APK only
as fallback. Other component delivery and its signature verification remain
unchanged.

Signing, verification, storage and provider integration are implemented; the
independent downloader and public feed are still pending. Until delivery is
completed, refresh these packaged data with each release. See
[the signed update format](../../../../tools/tv/signed-updates.md).

`provenance.json` records immutable upstream revisions, source/mirror URLs and
SHA-256 hashes. The catalog preserves upstream component IDs, defaults,
first-party protection policy and scriptlet permission masks. Upstream user
preferences still decide which optional default groups are enabled.

From the project Nix shell, run:

```
pnpm --dir brave/tools/tv --ignore-workspace install --ignore-scripts --frozen-lockfile
CARGO_BUILD_JOBS=2 cargo build --release --locked --manifest-path brave/tools/tv/node_modules/adblock-rs/js/Cargo.toml
cp brave/tools/tv/node_modules/adblock-rs/target/release/libadblock_rs.so brave/tools/tv/node_modules/adblock-rs/js/index.node
node brave/tools/tv/update-adblock.mjs /path/to/pinned/brave-uBlock
node --test brave/tools/tv/*.test.mjs
```

The uBlock checkout must be clean at `4dfbb048607ec4fd19c1c252fd255df6cc653720`
from https://github.com/brave/uBlock. The generator checks its revision before
importing the public scriptlet definitions. Trusted scriptlets require permission
bit 0, preserving their `requiresTrust` declaration. Android DAT caches carry the
bundle digest so a changed snapshot is reparsed after an APK update.

Update the pinned revisions in the generator only after reviewing upstream
changes. The conditional preprocessor is copied from the public Brave component
packager at the revision recorded in its source header. The pinned engine is
adblock-rust 0.13; no compatibility transform for older 0.8 engines is needed.

Filter files retain their source notices and license links. uBlock Origin lists
are distributed under GPL-3.0; EasyList/EasyPrivacy and Fanboy lists carry their
own upstream notices. Brave resources and preprocessing code use MPL-2.0.
See the original source URLs and retained headers for attribution and terms.
