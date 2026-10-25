#!/usr/bin/env bash
# Push every theme in themes/ to the launcher's on-TV catalog, as files/themes/<name>/, and restart Home
# so the Themes row lists them. Mirrors tools/theme.sh: stage in /data/local/tmp, write through
# ThemeProvider (content://com.gathin.tvhome.theme) with a cp fallback.
# Usage: TV_HOST=192.168.1.50 tools/theme-catalog.sh [themes-dir]      (default: themes)
#        TV_HOST=192.168.1.50 tools/theme-catalog.sh --clear
set -euo pipefail
# Resolve the themes dir against the caller's directory BEFORE cd, as theme.sh does for its folder.
THEMES_ARG="${1:-themes}"
case "$THEMES_ARG" in
  --clear|"") ;;
  /*) THEMES_ARG="$(cd "$THEMES_ARG" 2>/dev/null && { pwd -W 2>/dev/null || pwd; } || printf '%s' "$THEMES_ARG")" ;;
  *) THEMES_ARG="$(pwd -W 2>/dev/null || pwd)/$THEMES_ARG" ;;
esac
cd "$(dirname "$0")"
: "${TV_HOST:?set TV_HOST to the TV IP address, e.g. TV_HOST=192.168.1.50 $0}"
PY=venv/bin/python
[ -x "$PY" ] || PY=venv/Scripts/python.exe
[ -x "$PY" ] || { echo "run: python3 -m venv tools/venv && tools/venv/bin/pip install -r tools/requirements.txt" >&2; exit 1; }
export MSYS_NO_PATHCONV=1
PKG=com.gathin.tvhome
DIR=/sdcard/Android/data/$PKG/files
URI=content://$PKG.theme
TMP=/data/local/tmp/cyt-catalog
stage() { echo "$TMP-${1//\//-}"; }
put() { echo "rm -f $DIR/$1 2>/dev/null; { o=\$(content write --uri $URI/$1 < $(stage "$1") 2>&1) && case \"\$o\" in *'Unsupported operation'*) false ;; esac; } || cp $(stage "$1") $DIR/$1 2>/dev/null; rm -f $(stage "$1")"; }
tv() { "$PY" -I tv.py "$@"; }
RESTART="a=\$(settings get secure enabled_accessibility_services); am force-stop $PKG; case \"\$(cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | tail -n 1)\" in $PKG/*) am start -a android.intent.action.MAIN -c android.intent.category.HOME >/dev/null ;; *) am start -n $PKG/.MainActivity >/dev/null ;; esac; case \"\$a\" in *$PKG*) sleep 1; settings put secure enabled_accessibility_services \"\$a\"; settings put secure accessibility_enabled 1; sleep 1; settings put secure enabled_accessibility_services ''; settings put secure accessibility_enabled 0; sleep 1; settings put secure enabled_accessibility_services \"\$a\"; settings put secure accessibility_enabled 1 ;; esac"

if [ "$THEMES_ARG" = --clear ]; then
  tv "rm -rf $DIR/themes 2>/dev/null; $RESTART; echo cleared" | tail -n 1
  exit 0
fi
THEMES_DIR="$THEMES_ARG"
[ -d "$THEMES_DIR" ] || { echo "no such themes directory: $THEMES_DIR" >&2; exit 1; }
tv "mkdir -p $DIR/themes" >/dev/null 2>&1 || true
for FOLDER in "$THEMES_DIR"/*/; do
  NAME=$(basename "$FOLDER")
  case "$NAME" in
    *[!A-Za-z0-9_.-]*|.|..) echo "skip $NAME: unsafe folder name" >&2; continue ;;
  esac
  [ -f "$FOLDER/config.json" ] || continue
  "$PY" -I -c 'import json,sys; json.load(open(sys.argv[1], encoding="utf-8-sig"))' "$FOLDER/config.json" 2>/dev/null \
    || { echo "skip $NAME: config.json is not valid JSON" >&2; continue; }
  tv "mkdir -p $DIR/themes/$NAME" >/dev/null 2>&1 || true
  for FILE in config.json wallpaper.jpg screenshot.jpg; do
    [ -f "$FOLDER/$FILE" ] || continue
    "$PY" -I b64push.py "$FOLDER/$FILE" "$(stage "themes/$NAME/$FILE")" >/dev/null
    tv "$(put "themes/$NAME/$FILE")" >/dev/null
  done
  if [ -d "$FOLDER/wallpapers" ]; then
    for IMAGE in "$FOLDER"/wallpapers/*; do
      [ -f "$IMAGE" ] || continue
      case "$IMAGE" in *.[jJ][pP][gG]|*.[jJ][pP][eE][gG]|*.[pP][nN][gG]|*.[wW][eE][bB][pP])
        I=$(basename "$IMAGE")
        "$PY" -I b64push.py "$IMAGE" "$(stage "themes/$NAME/wallpapers/$I")" >/dev/null
        tv "$(put "themes/$NAME/wallpapers/$I")" >/dev/null ;;
      esac
    done
  fi
  if [ -f "$FOLDER/wallpapers/captions.txt" ]; then
    "$PY" -I b64push.py "$FOLDER/wallpapers/captions.txt" "$(stage "themes/$NAME/wallpapers/captions.txt")" >/dev/null
    tv "$(put "themes/$NAME/wallpapers/captions.txt")" >/dev/null
  fi
  echo "pushed $NAME"
done
tv "$RESTART; echo done" | tail -n 1
echo "Catalog on the TV. Undo with: tools/theme-catalog.sh --clear"
