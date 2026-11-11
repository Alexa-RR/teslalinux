#!/usr/bin/env bash
# teslalinux: fetch AOSP framework sources (Apache-2.0) to base ATL implementations on.
#   aosp-fetch.sh <repo-path-under-frameworks_base> [branch]
#   e.g. aosp-fetch.sh media/java/android/media/metrics/PlaybackMetrics.java
#        aosp-fetch.sh core/java/android/location/Location.java android14-release
# Files land in $ATL_WORK/aosp/<same relative path> (ATL_WORK default: <repo>/out/atl-work). Keep the copyright headers when copying code.
set -euo pipefail
# default: the same work dir as hostbuild.sh (hostenv.sh), <repo>/out/atl-work (git-ignored)
WORK="${ATL_WORK:-$(cd "$(dirname "$0")/../.." && pwd)/out/atl-work}"
BRANCH="${2:-android14-release}"
REL="$1"
OUT="$WORK/aosp/$REL"
mkdir -p "$(dirname "$OUT")"
curl -sS -f -L -o "$OUT" "https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/$BRANCH/$REL"
echo "$OUT ($(wc -l < "$OUT") lines)"
