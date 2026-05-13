#!/bin/bash
# TrefferPoint Pre-Release-Check
# Verifiziert dass alle 6 Version-/Asset-Stellen synchron sind.
# Aufruf: bash scripts/check_versions.sh
# Exit 0 = alles ok, Exit 1 = Drift erkannt.
#
# Hintergrund: Code-Review 2026-05-13 (CODE_REVIEW_FINDINGS_2026-05-13.md) hat
# Drift gefunden — Root-sw.js stand auf tp-2.3.168, Asset-Kopie auf tp-2.3.140.
# Verletzt Projektregel "Version synchron halten" → Offline-/Update-Tests unzuverlässig.

set -u
cd "$(dirname "$0")/.."

red()   { printf '\033[31m%s\033[0m\n' "$1"; }
green() { printf '\033[32m%s\033[0m\n' "$1"; }
yellow(){ printf '\033[33m%s\033[0m\n' "$1"; }

drift=0

# --- Versions auslesen (POSIX-grep + sed, kompatibel mit Git-Bash auf Windows) ---
extract_ver() { sed -n "s/.*$1[ ]*=[ ]*'\\?\"\\?\\([0-9.]\\{3,\\}\\)['\"].*/\\1/p" "$2" | head -1; }
extract_after_eq() { sed -n "s/.*$1[ ]*\"\\?\\([0-9]\\{1,\\}\\)\"\\?.*/\\1/p" "$2" | head -1; }

v_index=$(extract_ver "APP_VERSION" index.html)
v_sw=$(sed -n "s/.*CACHE_VER[ ]*=[ ]*'tp-\\([0-9.]\\{3,\\}\\)'.*/\\1/p" sw.js | head -1)
v_json=$(sed -n 's/.*"version": "\([0-9.]\{3,\}\)".*/\1/p' version.json | head -1)
v_gradle=$(sed -n 's/.*versionName "\([0-9.]\{3,\}\)".*/\1/p' android/app/build.gradle | head -1)
code_gradle=$(sed -n 's/.*versionCode \([0-9]\{1,\}\).*/\1/p' android/app/build.gradle | head -1)

# --- Asset-Kopien ---
v_index_asset=$(extract_ver "APP_VERSION" android/app/src/main/assets/trefferpoint/index.html)
v_sw_asset=$(sed -n "s/.*CACHE_VER[ ]*=[ ]*'tp-\\([0-9.]\\{3,\\}\\)'.*/\\1/p" android/app/src/main/assets/trefferpoint/sw.js | head -1)
v_json_asset=$(sed -n 's/.*"version": "\([0-9.]\{3,\}\)".*/\1/p' android/app/src/main/assets/trefferpoint/version.json | head -1)

# --- Hashes ---
hash_index=$(md5sum index.html | awk '{print $1}')
hash_index_asset=$(md5sum android/app/src/main/assets/trefferpoint/index.html | awk '{print $1}')

# --- Bericht ---
echo "TrefferPoint Version Sync Check"
echo "================================"
printf '%-44s %s\n' "index.html APP_VERSION:"           "$v_index"
printf '%-44s %s\n' "sw.js CACHE_VER:"                  "tp-$v_sw"
printf '%-44s %s\n' "version.json:"                     "$v_json"
printf '%-44s %s (Code %s)\n' "build.gradle versionName:" "$v_gradle" "$code_gradle"
printf '%-44s %s\n' "assets/index.html APP_VERSION:"    "$v_index_asset"
printf '%-44s %s\n' "assets/sw.js CACHE_VER:"           "tp-$v_sw_asset"
printf '%-44s %s\n' "assets/version.json:"              "$v_json_asset"
echo

# --- Konsistenz: alle 7 Versionen müssen identisch sein ---
ref="$v_index"
for label_val in \
  "sw.js:$v_sw" \
  "version.json:$v_json" \
  "build.gradle:$v_gradle" \
  "assets/index.html:$v_index_asset" \
  "assets/sw.js:$v_sw_asset" \
  "assets/version.json:$v_json_asset"
do
  label="${label_val%%:*}"
  val="${label_val##*:}"
  if [ "$val" != "$ref" ]; then
    red "  ✗ DRIFT: $label = $val (erwartet $ref)"
    drift=1
  fi
done

# --- Hash-Gleichheit Root- vs Asset-Kopie index.html ---
if [ "$hash_index" != "$hash_index_asset" ]; then
  red "  ✗ HASH-DRIFT: index.html (root) ≠ index.html (asset)"
  red "    Root:  $hash_index"
  red "    Asset: $hash_index_asset"
  drift=1
else
  green "  ✓ index.html (root) == index.html (asset)  [$hash_index]"
fi

# --- Hash sw.js auch checken ---
hash_sw=$(md5sum sw.js | awk '{print $1}')
hash_sw_asset=$(md5sum android/app/src/main/assets/trefferpoint/sw.js | awk '{print $1}')
if [ "$hash_sw" != "$hash_sw_asset" ]; then
  red "  ✗ HASH-DRIFT: sw.js (root) ≠ sw.js (asset)"
  drift=1
else
  green "  ✓ sw.js (root) == sw.js (asset)             [$hash_sw]"
fi

echo
if [ $drift -eq 0 ]; then
  green "ALLES SYNCHRON · Version $ref · versionCode $code_gradle"
  exit 0
else
  red "DRIFT erkannt — vor Release fixen!"
  yellow "  → cp index.html android/app/src/main/assets/trefferpoint/"
  yellow "  → cp sw.js android/app/src/main/assets/trefferpoint/"
  yellow "  → cp version.json android/app/src/main/assets/trefferpoint/"
  yellow "  → Versionsnummer in den 4 Quellen vereinheitlichen"
  exit 1
fi
