#!/bin/bash
# Build, package, sign (Tizen author profile "denopub", no Samsung account), install and launch
# Natapub on the TV. Usage: tizen/natapub/deploy.sh [tv-ip]   (default 192.168.0.10)
set -euo pipefail
TV=${1:-192.168.0.10}
HERE=$(cd "$(dirname "$0")" && pwd)
T=~/tizen-studio/tools/ide/bin/tizen
SDB=~/tizen-studio/tools/sdb
APP=natapubTV1.Natapub

cd "$HERE"
[ -d node_modules ] || npm ci
npm run build

B=$(mktemp -d)
trap 'rm -rf "$B"' EXIT
cp -R dist/. "$B"/
$SDB connect "$TV" >/dev/null
(cd "$B" && $T package -t wgt -s denopub -- "$B" >/dev/null)
mv "$B"/*.wgt "$B/Natapub.wgt"   # `tizen package` names it after <name>
$T install -n Natapub.wgt -s "$TV:26101" -- "$B" | tail -1
$T run -p "$APP" -s "$TV:26101" | tail -1
