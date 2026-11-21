#!/bin/bash
# Package, sign (Tizen author profile "denopub", no Samsung account), install and launch the spike.
# Usage: tizen/spike/deploy.sh [tv-ip]   (default 192.168.0.10)
set -euo pipefail
TV=${1:-192.168.0.10}
HERE=$(cd "$(dirname "$0")" && pwd)
T=~/tizen-studio/tools/ide/bin/tizen
SDB=~/tizen-studio/tools/sdb
B=$(mktemp -d)
cp "$HERE"/{config.xml,index.html,spike.js,hls.min.js,icon.png} "$B"/
$SDB connect "$TV" >/dev/null
(cd "$B" && $T package -t wgt -s denopub -- "$B" >/dev/null)
mv "$B"/*.wgt "$B/spike.wgt"
$T install -n spike.wgt -s "$TV:26101" -- "$B" | tail -1
$T run -p dnpbSpike1.spike -s "$TV:26101" | tail -1
rm -rf "$B"
