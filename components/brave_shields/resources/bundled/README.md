# Android filter baseline

These public upstream data files bootstrap Shields before signed components are
available. The native providers prefer an installed signed component. This is
an APK snapshot, not an independent update service: refresh it with each release
until a separately verified public update path is implemented. Optional lists
outside this fallback catalog still require component delivery.

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
