#!/usr/bin/env bash
# Build Rooms, install it on the TV and, optionally, push its Home Assistant address and token.
#   TV_HOST=<tv ip> addons/ha-rooms/install.sh [config.json]
#   # {"url": "http://192.168.1.10:8123", "token": "...", "glance": ["weather.home", "sensor.station_e10"]}
set -euo pipefail
CONFIG="${1:-}"
if [ -n "$CONFIG" ]; then
  [ -f "$CONFIG" ] || { echo "no such file: $CONFIG" >&2; exit 1; }
  CONFIG="$(cd "$(dirname "$CONFIG")" && pwd)/$(basename "$CONFIG")"
  if command -v cygpath >/dev/null 2>&1; then CONFIG="$(cygpath -m "$CONFIG")"; fi
fi
: "${TV_HOST:?set TV_HOST to the TV IP address}"
cd "$(dirname "$0")"
TOOLS="$(cd ../../tools && pwd)"
PY=$TOOLS/venv/bin/python
[ -x "$PY" ] || PY=$TOOLS/venv/Scripts/python.exe
export MSYS_NO_PATHCONV=1
PKG=au.id.altus.harooms
# Script paths stay relative: with MSYS_NO_PATHCONV, Git Bash would hand Windows Python a /c/... path.
tv() { "$PY" -I ../../tools/tv.py "$@"; }

[ -f local.properties ] || { [ -n "${ANDROID_HOME:-}" ] && echo "sdk.dir=${ANDROID_HOME//\\//}" > local.properties; } || true
if [ -n "${JAVA_HOME:-}" ]; then export PATH="$JAVA_HOME/bin:$PATH"; fi
./gradlew assembleRelease -q --console=plain

"$PY" -I ../../tools/b64push.py app/build/outputs/apk/release/app-release.apk /data/local/tmp/ha-rooms.apk
OUT=$(tv 'pm install -r /data/local/tmp/ha-rooms.apk; rm -f /data/local/tmp/ha-rooms.apk')
echo "$OUT"
case "$OUT" in *Success*) ;; *) echo "ERROR: pm install failed" >&2; exit 1 ;; esac

# The server and login go in as a file through the app's provider (only the ADB shell may write it), so the
# password never sits on a command line on the TV. The app reads it once and deletes it.
if [ -n "$CONFIG" ]; then
  STAGE=/data/local/tmp/ha-rooms-config.json
  tv --push "$CONFIG" "$STAGE" >/dev/null
  # On some TVs the shell cannot start this provider until the app is running.
  # Start it before writing, then allow its process to come up.
  tv "am start -n $PKG/.MainActivity >/dev/null; sleep 2; echo opened"
  # The provider can take a moment to appear after the app starts. tv.py does not pass the remote
  # shell's exit status through, so require a success marker from content write itself. Android's
  # content command can print a provider error and still exit 0, so success must also be silent.
  OUT=$(tv "ok=0; for attempt in 1 2 3; do if write_out=\$(content write --user 0 --uri content://$PKG.config/config.json < $STAGE 2>&1) && [ -z \"\$write_out\" ]; then ok=1; break; fi; [ -z \"\$write_out\" ] || printf '%s\n' \"\$write_out\"; [ \"\$attempt\" -eq 3 ] || sleep 1; done; if rm -f $STAGE && [ \"\$ok\" -eq 1 ]; then echo __HA_ROOMS_CONFIG_OK__; fi")
  case "$OUT" in
    *__HA_ROOMS_CONFIG_OK__*) echo "config pushed" ;;
    *) echo "$OUT" >&2; echo "ERROR: config write failed (or staged config could not be removed)" >&2; exit 1 ;;
  esac
fi
# The launcher's wallpapers, so this screen sits on the same pictures. Another app can't read the launcher's folder,
# so the ADB shell copies them into this one. Skipped when the launcher has none.
WALL=/sdcard/Android/data/com.gathin.tvhome/files/wallpapers
OUT=$(tv "if ls $WALL 2>/dev/null | grep -qiE '[.](jpe?g|png|webp)\$'; then D=/sdcard/Android/data/$PKG/files/wallpapers; mkdir -p \$D; for f in $WALL/*; do case \$f in *.jpg|*.jpeg|*.png|*.webp|*.JPG|*.PNG) cp \$f \$D/;; esac; done; echo copied; fi")
case "$OUT" in *copied*) echo "launcher wallpapers copied" ;; esac
tv "am force-stop $PKG; am start -n $PKG/.MainActivity >/dev/null; echo opened"
echo "Installed. Rooms is open on the TV; press Home and answer Show card for the card."
