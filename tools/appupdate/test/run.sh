#!/bin/sh
# teslalinux: every app-updater test that runs without a network, an account or a VM.
set -eu
cd "$(dirname "$0")/../../.."
python3 tools/appupdate/test/test_appupdate.py
python3 services/api/test/test_api.py
(cd tools/appupdate && cargo test --release --locked)
