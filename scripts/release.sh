#!/usr/bin/env bash
# Publish a new denopub version for the TV's self-update (system/Updater.kt).
#
#   scripts/release.sh [patch|minor|major|X.Y.Z] [--notes "что нового"] [--dry-run]
#
# Bumps versionCode (+1) and versionName in app/build.gradle.kts, builds the R8 release APK,
# checks it is signed with the key the TV's install uses, commits the bump, tags vX.Y.Z, pushes,
# and creates a GitHub release on deetan/denopub with the asset denopub-X.Y.Z-<code>.apk.
# The TV sees it within a few hours (or on the next Home start after that) and shows «Обновление».
#
# --dry-run: bump + build + signature check only, then restore build.gradle.kts. Nothing is
# committed, pushed or published. The APK is left in build/release/.
set -euo pipefail
cd "$(dirname "$0")/.."

export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@21}"
export ANDROID_HOME="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}"
REPO=deetan/denopub
GRADLE=app/build.gradle.kts
# SHA-256 of the signing cert of the APK installed on the TV (~/.android/debug.keystore on the
# owner's Mac). A different key = Android refuses the update; reinstalling loses the login.
CERT_SHA256=ef48301cf027ab3003211437400ace4f748ec3db13bbe8853e82bdc87b689869

bump=patch notes="" dry=0
while [[ $# -gt 0 ]]; do
  case $1 in
    --notes) notes=$2; shift 2 ;;
    --dry-run) dry=1; shift ;;
    patch|minor|major) bump=$1; shift ;;
    [0-9]*.[0-9]*.[0-9]*) bump=$1; shift ;;
    *) sed -n '2,12p' "$0"; exit 1 ;;
  esac
done

if [[ $dry == 0 ]]; then
  [[ -z $(git status --porcelain) ]] || { echo "Working tree not clean — commit first." >&2; exit 1; }
  [[ $(git rev-parse --abbrev-ref HEAD) == main ]] || { echo "Release from main only." >&2; exit 1; }
  gh auth status >/dev/null 2>&1 || { echo "gh is not logged in." >&2; exit 1; }
fi

code=$(sed -nE 's/^ *versionCode = ([0-9]+).*/\1/p' $GRADLE)
name=$(sed -nE 's/^ *versionName = "([^"]+)".*/\1/p' $GRADLE)
IFS=. read -r ma mi pa <<<"$name"
case $bump in
  patch) new="$ma.$mi.$((pa + 1))" ;;
  minor) new="$ma.$((mi + 1)).0" ;;
  major) new="$((ma + 1)).0.0" ;;
  *) new=$bump ;;
esac
newcode=$((code + 1))
echo "denopub $name ($code) → $new ($newcode)"

cp $GRADLE "$GRADLE.bak"
restore() { mv "$GRADLE.bak" $GRADLE; }
[[ $dry == 1 ]] && trap restore EXIT
sed -i '' -E "s/^( *versionCode = )[0-9]+/\1$newcode/; s/^( *versionName = )\"[^\"]+\"/\1\"$new\"/" $GRADLE

./gradlew --quiet :app:assembleRelease
apk=app/build/outputs/apk/release/app-release.apk
apksigner=$(ls -d "$ANDROID_HOME"/build-tools/* | sort -V | tail -1)/apksigner
got=$("$apksigner" verify --print-certs "$apk" | sed -nE 's/.*certificate SHA-256 digest: ([0-9a-f]+).*/\1/p' | head -1)
[[ $got == "$CERT_SHA256" ]] || { echo "APK signed with $got, expected $CERT_SHA256 — the TV would refuse it." >&2; exit 1; }

mkdir -p build/release
out=build/release/denopub-$new-$newcode.apk
cp "$apk" "$out"
echo "Built $out ($(du -h "$out" | cut -f1)), signature OK"
[[ $dry == 1 ]] && { echo "Dry run: nothing committed or published."; exit 0; }

rm "$GRADLE.bak"
git commit -qm "Release $new" $GRADLE
git tag "v$new"
git push -q origin main "v$new"
gh release create "v$new" "$out" --repo $REPO --title "denopub $new" --notes "${notes:-$(git log --format='- %s' "$(git describe --tags --abbrev=0 "v$new^" 2>/dev/null || git rev-list --max-parents=0 HEAD)..v$new^" | head -30)}"
echo "Published v$new — the TV offers it on Home within a few hours."
