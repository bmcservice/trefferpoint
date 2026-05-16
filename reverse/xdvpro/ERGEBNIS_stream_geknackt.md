# ✅ XDV-Pro Live-Stream GEKNACKT (2026-05-17)

Der Live-TUTK-P2P-Video-Stream der „4K WIFI"/XDV-Pro-Cam wird jetzt
programmatisch extrahiert — verifizierbar dekodierbares H.264.

## Beweis-Artefakte (`reverse/xdvpro/samples/`)
- `xdv_live.h264` — 6.529.961 B, 672 Frames, roh extrahiert vom Tablet
- `xdv_live.mp4` — daraus, ffprobe: **H.264 Main, 640×480**, 622 Frames dekodiert
- `xdv_live_frame1.png` — dekodierter Einzelframe (Sichtbeleg)
- `xdv_harness.log` — vollständiges Schritt-Protokoll des erfolgreichen Laufs

## Die Lösung

**Zugang:** TUTK/IOTC LAN-direkt mit den mitgelieferten echten ThroughTek-
Vendor-`.so` + öffentlichem `com.tutk.IOTC`-Wrapper (cnping/TUTK 14W36),
**ABI-korrigiert** (entscheidend, s.u.).

**Credentials:** Account **`admin`**, Passwort **`123456`** (gefunden per
Bruteforce mit frischer Session je Versuch; Cam antwortete sauber mit
−20009 = AV_ER_WRONG_VIEWACCorPWD bis `123456` → avClientStart=0).

**Kanonische Sequenz (funktionierend):**
```
IOTC_Initialize2(0)              = 0
avInitialize(3)                  = 3
IOTC_Lan_Search2(.,3000)         -> UID A5H9X6ZEXT86GJKL111A @192.168.10.1:54099
IOTC_Connect_ByUID(uid)          = 0   (sid, LAN-direkt, KEIN Cloud/AuthKey)
avClientStart(sid,"admin","123456",8,int[1],0) = 0  (avIndex)
avSendIOCtrl(avIndex, 0x01FF, byte[8], 8)       = 0  (IOTYPE_USER_IPCAM_START)
avRecvFrameData(avIndex,buf,..,frameInfo,24,..) -> H.264-NALs (Annex-B)
```

**Der entscheidende Durchbruch (ABI-Fix):** Die Vendor-`.so` sind echtes,
ungepatchtes ThroughTek, exportieren die Standard-JNI-Symbole
`Java_com_tutk_IOTC_*`. ABER die SDK-Generation der Cam-Firmware erwartet
`avClientStart(int nSID,String,String,**int** timeout,**int[]** servType,int)`
— der 14W36-Wrapper deklariert `long`/`long[]`. Java-`long` wo nativ `int`
gelesen wird ⇒ Stack-Korruption ⇒ natives `SIGABRT` (avClientStart+216 /
IOTC_Session_Check+472). Nach Umstellung auf `int`/`int[]` lief alles.
`IOTC_Session_Check(St_SInfo)` ist ebenfalls versions-/struktursensibel —
weggelassen (optional, nicht nötig).

## Befund Live-Auflösung (endgültig, dekodierte Ground Truth)

Der Live-P2P-Stream (Default-Channel 0) ist **640×480 H.264 Main** — nicht
1080p, nicht 4K. Bestätigt die frühere These endgültig per echtem Decode
(kein Config-Proxy mehr): **4K existiert nur als SD-Aufnahme**, der Live-
P2P-Preview ist klein. (Höhere Substreams evtl. via Stream-Typ-IOCTRL /
anderem Channel — offene Option, nicht nötig fürs Knacken.)

## Reproduktion

1. Harness-APK via CI: Branch `fue/xdv-harness`, Workflow „Build XDV Harness
   APK" (`gh run download <id> -n xdv-harness-debug-apk`).
2. Tablet im Cam-AP „4K WIFI-…", Cam wach (`:8082`-GET als KeepAlive).
3. `adb install -g app-debug.apk` ; `adb shell am start -n
   de.bmcservice.xdvharness/.MainActivity`
4. Ergebnis: `/sdcard/Android/data/de.bmcservice.xdvharness/files/`
   → `harness.log` (endet mit HARNESS_DONE) + `xdv_live.h264`.
5. `adb pull …/xdv_live.h264` → `ffmpeg -f h264 -i xdv_live.h264 out.mp4`.

Quelle: `reverse/xdvpro/harness/` (eigenständiges Gradle-Projekt, baut über
GitHub Actions; berührt TrefferPoint-Produktion nicht).

## TrefferPoint-Integrationsweg

Technisch ist eine TUTK-Live-Quelle für TrefferPoint jetzt machbar: dieselbe
Sequenz in ein Kamera-Quell-Modul portieren, die `avRecvFrameData`-NALs in
denselben MediaCodec/SurfaceView-Pfad speisen wie die RTSP-Pipeline.
**Praxis-Einordnung unverändert:** Live = 640×480 (< ETF150 1080p) → für
Echtzeit-Detektion kein Vorteil; der echte Cam-Nutzen bleibt der **4K-SD-
Trainingsdaten-Recorder**. Die Live-Extraktion ist damit primär für
„andere Verwendung wegen der Spektiv-Bauart" verfügbar, nicht als
ETF150-Ersatz im Detektionspfad.
