#!/usr/bin/env bash
# Build and push the app to the TV over Wi-Fi (adb).
#
#   scripts/tv.sh pair <ip:port> <code>  one-time: TV → Wireless debugging → Pair device with pairing code
#   scripts/tv.sh connect [ip[:port]]    connect (remembers the address in .tv-address)
#   scripts/tv.sh deploy                 build release APK (R8), install, launch  (default)
#   scripts/tv.sh run                    launch without rebuilding
#   scripts/tv.sh logs                   app logcat
#   scripts/tv.sh shot [file.png]        screenshot of the TV
#   scripts/tv.sh screensaver [off]      make the paintings screensaver the system one (off = back to Ambient)
set -euo pipefail
cd "$(dirname "$0")/.."

export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@21}"
export ANDROID_HOME="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}"
export PATH="$ANDROID_HOME/platform-tools:/opt/homebrew/bin:$PATH"

PKG=dev.dt.kinopub
ADDR_FILE=.tv-address
addr() { [[ -f $ADDR_FILE ]] && cat "$ADDR_FILE" || { echo "No TV address; run: scripts/tv.sh connect <ip>" >&2; exit 1; }; }

# Wireless debugging (Android 11+) picks a new port after every reboot / toggle: find it via mDNS.
discover() {
  adb mdns services 2>/dev/null | awk '/_adb-tls-connect/ {print $3; exit}'
}

ensure_connected() {
  local a d; a=$(addr)
  d=$(discover)
  if [[ -n $d && $d != "$a" ]]; then a=$d; echo "$a" > "$ADDR_FILE"; fi
  if ! adb devices | grep -q "^$a[[:space:]]*device"; then
    adb connect "$a" >/dev/null
    sleep 1
    adb devices | grep -q "^$a[[:space:]]*device" || { echo "Cannot reach $a — is the TV on and debugging allowed?" >&2; exit 1; }
  fi
  export ANDROID_SERIAL=$a
}

cmd=${1:-deploy}
case $cmd in
  pair)    adb pair "$2" "$3" ;;
  connect)
    a=${2:-$(addr)}; [[ $a == *:* ]] || a="$a:5555"
    echo "$a" > "$ADDR_FILE"
    adb connect "$a" ;;
  deploy)
    ensure_connected
    ./gradlew --quiet :app:installRelease
    adb shell am start -n "$PKG/.MainActivity" >/dev/null
    echo "Installed and launched on $ANDROID_SERIAL" ;;
  run)     ensure_connected; adb shell am start -n "$PKG/.MainActivity" ;;
  logs)    ensure_connected; adb logcat --pid="$(adb shell pidof -s $PKG)" ;;
  shot)    ensure_connected; f=${2:-tv-$(date +%H%M%S).png}; adb exec-out screencap -p > "$f"; echo "$f" ;;
  screensaver)
    ensure_connected
    if [[ ${2:-} == off ]]; then
      adb shell settings delete secure screensaver_components
      echo "Screensaver back to the system default"
    else
      # Remember the current one so "off" can be done by hand if needed.
      echo "was: $(adb shell settings get secure screensaver_components)"
      adb shell settings put secure screensaver_enabled 1
      # Without this the device just sleeps at the idle timeout (seen on the emulator).
      adb shell settings put secure screensaver_activate_on_sleep 1
      adb shell settings put secure screensaver_components "$PKG/.system.ArtDream"
      echo "now: $(adb shell settings get secure screensaver_components)"
    fi ;;
  *) sed -n '2,11p' "$0"; exit 1 ;;
esac
