# Stream-Extraktion — Build-fertiges Rezept (2026-05-16)

> Ergebnis der GitHub/Web-Recherche („jemand hat es vor uns gelöst") +
> statischer Befunde. **Die Nuss ist konzeptionell geknackt.** Kein
> Protokoll-Nachbau, kein mehrwöchiges Krypto-Reverse nötig.

## Kern-Erkenntnis

Die Vendor-Libs `lib/arm64-v8a/libIOTCAPIs.so` + `libAVAPIs.so` sind
**echte, ungepatchte ThroughTek/TUTK-Libs** (Thread B: Easter-Egg „Charlie
is the designer of P2P!!"; JNI-Exporte `Java_com_tutk_IOTC_IOTCAPIs_IOTC_*`,
`Java_com_tutk_IOTC_AVAPIs_av*`). Damit bindet der **öffentlich verfügbare
Standard-Java-Wrapper `com.tutk.IOTC`** (Quelle: cnping/TUTK,
TutkKalay/Kalay_Kit_Sample_App) **1:1 an die mitgelieferten .so** — keine
Signatur-Rekonstruktion, kein Reimplement.

Die „Verschlüsselung" ist **XOR** mit dem 32-Byte-String
`Charlie is the designer of P2P!!` (TUTK `P2P_Proprietary_En/Decrypt`).
Trivial reversibel — und bei .so-Reuse ohnehin intern erledigt.

## Kanonische Call-Sequenz (LAN-direkt, kein Cloud/AuthKey)

Aus TutkKalay Sample_AVAPIs_Client + taishanmayi/tutk_test + wyzecam tutk.py:

```
IOTC_Initialize2(0)                       # port-only, kein AuthKey (alte Gen)
avInitialize(3)                           # n max AV channels
sid     = IOTC_Connect_ByUID("A5H9X6ZEXT86GJKL111A")   # LAN-direkt am Cam-AP
avIndex = avClientStart(sid, "admin", <PW>, 20000, &srvType, 0)
avSendIOCtrl(avIndex, IOTYPE_USER_IPCAM_START /*0x1FF*/, byte[8], 8)
loop: avRecvFrameData2(avIndex, buf, ...) -> H.264-Frame
      (Fehler-States AV_ER_DATA_NOREADY / SESSION_CLOSE_BY_REMOTE / INVALID_SID)
```

Cam-spezifisch aus `libaw_net_client.so` (Strings):
- Account = **`admin`** (Literal vorhanden), Passwort via
  `AWNetwork::setPassword(char*)` zur Laufzeit gesetzt → **einzige echte
  Restunbekannte** (s.u.).
- Vendor hat eigene IOCTRL `AW_IOTYPE_USER_IPCAM_START_ENCODER` /
  `..._STOP_ENCODER` — Wert aus libaw_net_client.so/libipcamera.so
  Disassembly zu bestätigen (sonst Standard 0x1FF probieren).

## Einzige echte Restunbekannte: das avClientStart-Passwort

`admin`/`<PW>`. Auflösungswege (in Reihenfolge des Aufwands):
1. **Defaults durchprobieren:** `""`, `888888`, `8888`, `6666`, `123456`,
   `admin`, `12345678` (das AP-Pwd). Cheap-LAN-Cams akzeptieren am eigenen
   AP fast immer einen Fixwert. Schnellster Test.
2. **classes.dex / `c.x` / `com.weapons18.api.v` tiefer dekompilieren**
   (jadx) — wo `setPassword`/native `cd(String,String)` aufgerufen wird,
   steht das Literal oder die Ableitung (z.B. aus UID).
3. **Live-Sniff** des avClientStart-Handshakes (nur falls 1+2 scheitern;
   PCAPdroid scheitert same-subnet — Tablet bräuchte cross-subnet, oder
   `tcpdump`-Binary nach /data/local/tmp pushen).

## Empfohlene Umsetzung (zwei tragfähige Wege)

**Weg A — Android-Harness mit öffentlichem com.tutk.IOTC + Vendor-.so
(geringster Aufwand, robust):**
- Minimal-App/Dex: `com/tutk/IOTC/IOTCAPIs.java` + `AVAPIs.java` (public
  Quelle) unverändert; `System.loadLibrary` der **Vendor**-libIOTCAPIs.so
  + libAVAPIs.so (JNI-Symbole passen). Call-Sequenz oben. Frames →
  `/sdcard/...` als `.h264` (Annex-B), 1 Frame als JPG-Beleg (MediaCodec
  oder ffmpeg).
- Bauen via Projekt-GitHub-Actions (lokal kein NDK/SDK) ODER NDK in der
  Codex-Umgebung. APK/Dex nach Tablet, läuft im Cam-AP → erreicht Cam.

**Weg B — nativer C-Harness (dlopen libaw_net_client.so, vendor C-ABI):**
`tutk_initNetClient → tutk_searchDevice → tutk_connectDevice(UID) →
tutk_registerNotificationHandler → tutk_startIpcamStream` → Callback-Buffer
dumpen. Umgeht den Passwort-Punkt evtl., weil der Vendor-Layer das Pwd
selbst setzt. Braucht arm64-NDK-Build.

## Referenzen (öffentlich, verifiziert relevant)

- TutkKalay/Kalay_Kit_Sample_App — Sample_AVAPIs_Client/Client.java
  (kanonische Sequenz, `avClientStart(sid,"admin","888888",...)`,
  `IOTYPE_USER_IPCAM_START=0x1FF`)
- cnping/TUTK — `Include/AVAPIs.h`, `IOTCAPIs.h`,
  `Lib/Android/.../com/tutk/IOTC/AVAPIs.java` (Wrapper-Quelle)
- taishanmayi/tutk_test — `source/AVAPIs_Client.c` (C-Referenzclient)
- StackerDEV/ipcam2000v2-to-rtsp — Präzedenz: LAN-AVAPI → lokaler
  RTSP-Server, ohne Cloud
- kroo.github.io/wyzecam (tutk.py) — saubere dokumentierte ctypes-Sequenz
  + Frame-Recv + IOCTRL
- palant.info 2025-11 „An overview of the PPPP protocol for IoT cameras"
  — XOR-Key `Charlie is the designer of P2P!!`, P2P_Proprietary_De/Encrypt,
  Discovery/Framing-Details; nennt Elastic Security Labs- + eufy-Open-Clients

## No-Build-Versuch (screenrecord) — Teilresultat 2026-05-16

`samples/xdv_live_screen.mp4` (25 s, 1600×2560 H.264, vom Tablet-Screen
während XDV-App „läuft" lt. User). Numerisch NICHT eingefroren (82 % Frames
>2 KB, avg 24,6 KB, max 191 KB → Bewegtbild). **Aber:** wlan0-RX während der
25 s = 0 B gemessen → kein bestätigter Cam-Netzverkehr. Unklar ob echter
Live-Feed (RX-Messung evtl. danebengelegen) oder UI/gecachte Bewegung.
Visuelle Bestätigung nötig (Bild-Tools hier per Memory-Hardregel gesperrt).
Codex als Build-Vehikel in dieser Windows-Umgebung hart blockiert
(`CreateProcessAsUserW failed: 5`, 2× bestätigt). Lokal kein NDK.

## Status / nächster Schritt

Konzeptionell **gelöst**, Implementierung ausstehend (Codex-Plugin braucht
Update für die Build-Eskalation; Briefing + dieses Rezept liegen bereit).
Restunbekannte Passwort ist mit Weg-1-Defaults in Minuten testbar, sobald
ein lauffähiger Harness steht. Cam dozt — XDV-App kurz öffnen oder
`:8082`-Ping als KeepAlive vor Verbindungsaufbau.
