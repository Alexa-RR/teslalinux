#!/bin/sh
# teslalinux: build tlplay (release) for the machine this runs on.
#   Pi / Debian trixie arm64:  apt-get install -y cargo rustc libssl-dev pkg-config && tools/appupdate/build.sh
#   macOS (development):       brew install rust && tools/appupdate/build.sh
# Dependencies are pinned by Cargo.lock (--locked); .cargo/config.toml keeps them at versions that build
# with Debian trixie's rustc 1.85. Output: tools/appupdate/target/release/tlplay (about 3 MB).
set -eu
cd "$(dirname "$0")"
cargo build --release --locked
cargo test --release --locked
ls -la target/release/tlplay
