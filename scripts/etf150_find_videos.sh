#!/bin/bash
# ETF150 Video-Path-Diagnose
# Sucht systematisch nach VID*.mp4-Dateien in verschiedenen Pfad-/Datums-Varianten.
# Vorausgesetzt: Tablet im ETF150-Hotspot (Gateway 192.168.10.1), ADB verbunden.
#
# Aufruf: bash scripts/etf150_find_videos.sh

set -u

probe_status() {
    local url="$1"
    adb shell "curl -sI -m 3 -o /dev/null -w '%{http_code}' '$url'" 2>/dev/null
}

probe_body() {
    local url="$1"
    adb shell "curl -s -m 3 '$url' 2>&1 | head -3" 2>/dev/null
}

echo "=========================================="
echo "ETF150 Video-Path-Diagnose"
echo "=========================================="
echo

echo "1) Cam alive?"
echo "------------------------------------------"
probe_body "http://192.168.10.1/roc/version"
echo

echo "2) Aktuelle Cam-Zeit (verschiedene Endpoints)"
echo "------------------------------------------"
for ep in \
    "/roc/system/time" \
    "/roc/system/datetime" \
    "/roc/system/info" \
    "/roc/system/status" \
    "/roc/time" \
    "/roc/datetime" \
    "/roc/setting/datetime" \
    "/roc/network/info"
do
    rc=$(probe_status "http://192.168.10.1$ep")
    if [ "$rc" = "200" ]; then
        echo "  ✓ $ep:"
        probe_body "http://192.168.10.1$ep" | head -2 | sed 's/^/      /'
    fi
done
echo

echo "3) REC-Status / SD-Card-Info"
echo "------------------------------------------"
for ep in \
    "/roc/record/status" \
    "/roc/record/info" \
    "/roc/storage/info" \
    "/roc/sdcard/info" \
    "/roc/sd/info" \
    "/roc/system/storage"
do
    rc=$(probe_status "http://192.168.10.1$ep")
    if [ "$rc" = "200" ]; then
        echo "  ✓ $ep:"
        probe_body "http://192.168.10.1$ep" | head -3 | sed 's/^/      /'
    fi
done
echo

echo "4) File-Listing-Endpoints"
echo "------------------------------------------"
for ep in \
    "/roc/file/list" \
    "/roc/files" \
    "/roc/storage/list" \
    "/roc/storage/files" \
    "/roc/sdcard/list" \
    "/roc/record/list" \
    "/roc/record/files"
do
    rc=$(probe_status "http://192.168.10.1$ep")
    body=$(probe_body "http://192.168.10.1$ep" | head -1)
    if echo "$body" | grep -qiE "file|VID|list|name" >/dev/null 2>&1; then
        echo "  ✓ $ep ($rc):"
        echo "$body" | head -2 | sed 's/^/      /'
    elif [ "$rc" = "200" ]; then
        echo "  · $ep ($rc): $body" | head -c 100
        echo
    fi
done
echo

echo "5) Date-Format-Permutationen (heute 2026-05-14)"
echo "------------------------------------------"
for fmt in \
    "20260514" \
    "2026-05-14" \
    "2026_05_14" \
    "20260514_0" \
    "20260514_1" \
    "2026/05/14" \
    "year2026/05/14" \
    "260514" \
    "140526" \
    "20260513" \
    "20260515" \
    "2025-05-14" \
    "2024-05-14"
do
    rc=$(probe_status "http://192.168.10.1/roc/file/${fmt}/VID0001.mp4")
    if [ "$rc" = "200" ]; then
        size=$(adb shell "curl -sI -m 3 'http://192.168.10.1/roc/file/${fmt}/VID0001.mp4' | grep -i content-length" 2>/dev/null | tr -d '\r')
        echo "  ✓ /roc/file/${fmt}/VID0001.mp4 · $size"
    fi
done
echo

echo "6) Volle VID-Range im 20260514 (komplett-Scan 1-50)"
echo "------------------------------------------"
for n in $(seq 1 50); do
    num=$(printf '%04d' $n)
    rc=$(probe_status "http://192.168.10.1/roc/file/20260514/VID${num}.mp4")
    if [ "$rc" = "200" ]; then
        size=$(adb shell "curl -sI -m 3 'http://192.168.10.1/roc/file/20260514/VID${num}.mp4' | grep -i content-length" 2>/dev/null | tr -d '\r' | awk '{print $2}')
        echo "  ✓ VID${num}.mp4 · $size bytes"
    fi
done
echo

echo "7) Live REC-Smoke-Test"
echo "------------------------------------------"
echo "  STARTE REC..."
adb shell "curl -s -m 5 -X POST 'http://192.168.10.1/roc/record/start' 2>&1 | head -1"
echo "  Warte 8 s (Cam aufnehmen lassen)..."
sleep 8
echo "  STOPPE REC..."
adb shell "curl -s -m 5 -X POST 'http://192.168.10.1/roc/record/stop' 2>&1 | head -1"
echo "  Warte 2 s (File-Finalize)..."
sleep 2
echo "  Suche neue VIDs in 20260514..."
for n in $(seq 1 50); do
    num=$(printf '%04d' $n)
    rc=$(probe_status "http://192.168.10.1/roc/file/20260514/VID${num}.mp4")
    if [ "$rc" = "200" ]; then
        date=$(adb shell "curl -sI -m 3 'http://192.168.10.1/roc/file/20260514/VID${num}.mp4' | grep -i 'last-modified'" 2>/dev/null | tr -d '\r')
        echo "  ✓ VID${num}.mp4 · $date"
    fi
done
echo "  Suche auch in 20260513 (falls Cam-Uhr -1d):"
for n in $(seq 1 50); do
    num=$(printf '%04d' $n)
    rc=$(probe_status "http://192.168.10.1/roc/file/20260513/VID${num}.mp4")
    if [ "$rc" = "200" ]; then
        date=$(adb shell "curl -sI -m 3 'http://192.168.10.1/roc/file/20260513/VID${num}.mp4' | grep -i 'last-modified'" 2>/dev/null | tr -d '\r')
        echo "  ✓ 20260513/VID${num}.mp4 · $date"
    fi
done
echo "  Suche auch im Vormonat/Vorjahr (falls Cam-Uhr unset):"
for d in 20240101 20240514 20250514 20260101 19700101; do
    for n in 1 2 3; do
        num=$(printf '%04d' $n)
        rc=$(probe_status "http://192.168.10.1/roc/file/${d}/VID${num}.mp4")
        [ "$rc" = "200" ] && echo "  ✓ ${d}/VID${num}.mp4"
    done
done

echo
echo "8) KeepAlive-Endpoint-Diagnose"
echo "------------------------------------------"
echo "Welche Endpoints existieren (HEAD-Probe)?"
for ep in \
    "/ROC/System/keepAlive" \
    "/roc/system/keepAlive" \
    "/roc/system/keepalive" \
    "/roc/keepAlive" \
    "/roc/power/status" \
    "/roc/power/keepAlive" \
    "/roc/setting/autoShutdown" \
    "/roc/system/autoShutdown" \
    "/roc/setting/standby" \
    "/roc/heartbeat" \
    "/roc/ping" \
    "/roc/version"
do
    rc_get=$(adb shell "curl -sI -m 3 -o /dev/null -w '%{http_code}' 'http://192.168.10.1$ep'" 2>/dev/null)
    rc_post=$(adb shell "curl -sI -m 3 -X POST -o /dev/null -w '%{http_code}' 'http://192.168.10.1$ep'" 2>/dev/null)
    if [ "$rc_get" != "404" ] || [ "$rc_post" != "404" ]; then
        echo "  $ep  GET=$rc_get  POST=$rc_post"
    fi
done
echo

echo "Stand jetzt: wann schaltet Cam ab (ohne Calls)?"
echo "Cam ist gerade online. Warte 30 s ohne Calls..."
sleep 30
rc=$(adb shell "curl -sI -m 3 -o /dev/null -w '%{http_code}' 'http://192.168.10.1/roc/version'" 2>/dev/null)
echo "  Nach 30 s: HTTP $rc (200 = noch online)"
echo "Warte weitere 30 s..."
sleep 30
rc=$(adb shell "curl -sI -m 3 -o /dev/null -w '%{http_code}' 'http://192.168.10.1/roc/version'" 2>/dev/null)
echo "  Nach 60 s: HTTP $rc"
echo "Warte weitere 30 s..."
sleep 30
rc=$(adb shell "curl -sI -m 3 -o /dev/null -w '%{http_code}' 'http://192.168.10.1/roc/version'" 2>/dev/null)
echo "  Nach 90 s: HTTP $rc"
echo "→ Wenn alle 200 sind, schläft Cam nicht innerhalb 90s ein."
echo "→ Wenn 000/timeout, schläft Cam schon ohne Calls vor 90s ein."
echo

echo "=========================================="
echo "Diagnose fertig."
echo "=========================================="
