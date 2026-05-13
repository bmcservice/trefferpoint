# TrefferPoint Code Review Findings

Datum: 2026-05-13  
Scope: Kritischer Review der bisherigen Appentwicklung, ohne Codeaenderungen an der App.  
Kontext: TrefferPoint ist noch in der Findungsphase fuer robuste Treffererkennung. Offline-Faehigkeit bleibt Direktive.

## Kurzfazit

Die App ist erstaunlich weit fuer ein bewusst simples Offline-Projekt: USB-UVC, RTSP/MJPEG, Kalibrierung, Overlay, Referenzvergleich und Android-Bridge sind praktisch testbar und am Stand gewachsen. Die groessten Risiken liegen aktuell nicht in einem einzelnen Algorithmusdetail, sondern in technischer Drift und Kopplung:

- Versionen und Asset-Kopien koennen auseinanderlaufen.
- Native WebView/Bridge ist fuer Debugging sehr offen.
- Stream-/Snapshot-Loops koennen ueber Mode-Wechsel hinweg weiterlaufen.
- Die Trefferlogik ist noch zu stark mit UI, Canvas, globalem Zustand und Kamera-Pfaden verflochten.
- `index.html` ist als Lieferformat sinnvoll, aber als Denkmodell inzwischen zu gross.

## Findings

### P1: Version-/Asset-Drift

Root-Dateien stehen auf `2.3.168`:

- `index.html`: `APP_VERSION = '2.3.168'`
- `version.json`: `{"version": "2.3.168"}`
- `android/app/build.gradle`: `versionName "2.3.168"`, `versionCode 172`
- Root-`sw.js`: `CACHE_VER = 'tp-2.3.168'`

Aber:

- `android/app/src/main/assets/trefferpoint/sw.js` steht noch auf `CACHE_VER = 'tp-2.3.140'`

`index.html` und die Android-Asset-Kopie waren hash-identisch, aber `sw.js` nicht. Das verletzt die Projektregel "Version synchron halten" und kann Offline-/Update-Tests unzuverlaessig machen.

Empfehlung:

- Einen kleinen lokalen Check ergaenzen, der vor Releases prueft:
  - `APP_VERSION`
  - `CACHE_VER`
  - `version.json`
  - Android `versionName`/`versionCode`
  - Hash-Gleichheit von Root- und Android-Asset-Kopien.
- Dies kann ohne npm/build system als PowerShell- oder Python-Skript passieren.

### P1: WebView/Bridge ist sehr offen

In `MainActivity.kt` sind fuer die WebView gesetzt:

- `javaScriptEnabled = true`
- `allowFileAccess = true`
- `allowFileAccessFromFileURLs = true`
- `allowUniversalAccessFromFileURLs = true`
- `mixedContentMode = MIXED_CONTENT_ALWAYS_ALLOW`
- `addJavascriptInterface(..., "tpBridge")`
- `WebView.setWebContentsDebuggingEnabled(true)`

Dazu laeuft ein lokaler Dev-HTTP-Server auf Port 8090.

Fuer Standtests ist das praktisch, aber fuer eine installierbare App ist das eine grosse Angriffs- und Fehlerflaeche. Besonders kritisch ist die Kombination aus File-URL, Universal Access, JavaScript-Bridge und Debugging.

Empfehlung:

- Debug-/Dev-Funktionen klar an `BuildConfig.DEBUG` binden.
- Dev-HTTP-Server nur in Debug-Builds starten.
- WebView-Debugging nur in Debug-Builds aktivieren.
- Langfristig Bridge-Funktionen enger whitelisten und nur eigene Asset-URL laden.

### P1: Stream-Cleanup ist nicht vollstaendig zentralisiert

`stopAllActiveStreams()` stoppt MediaStream, MJPEG-Abort, RTSP-Bridge und rAF. Es stoppt aber nicht sicher:

- `rtspSnapshotInterval`
- `rtspKeepAliveInterval`

Die RTSP-Stop-Button-Logik ruft diese Stopper separat auf, Mode-Wechsel aber nicht zwingend. Dadurch koennen nach Wechsel auf USB/Web weiterhin Snapshot- oder Keepalive-Timer laufen.

Empfehlung:

- Alle Stream-/Timer-Ressourcen zentral in `stopAllActiveStreams()` beenden.
- `inputMode`, Body-Klassen, Snapshot-Loop, Keepalive, pending flags und rAF gemeinsam zuruecksetzen.

### P1: RTSP-Pixelzugriff kann UI blockieren

Im RTSP-SurfaceView-Modus ruft JS alle 250 ms synchron `window.tpBridge.captureCurrentFrame()` auf. Kotlin macht dafuer:

- neues Bitmap
- neuen `HandlerThread`
- `PixelCopy.request(...)`
- Wait bis 1500 ms
- JPEG-Kompression
- Base64-Rueckgabe an JS

Wenn PixelCopy oder JPEG-Kompression haengt, blockiert der JS-Thread. Das ist plausibel als Quelle fuer Freeze/Lag.

Empfehlung:

- Snapshot-Capture entkoppeln:
  - native Seite erzeugt neuesten Detection-Snapshot asynchron
  - JS fragt nur den letzten fertigen Snapshot ab
  - kein 1500-ms-Wait im JS-Bridge-Call
- Thread/Bitmap-Puffer wiederverwenden statt pro Tick neu erzeugen.
- Snapshot-Rate adaptiv reduzieren, wenn Decode/Bridge laenger als Intervall dauert.

### P2: Aktive Architektur und alte Kommentare/Dependencies laufen auseinander

Beispiele:

- `pushFrameToWebView()` ist absichtlich no-op, weil Display ueber DevHttpServer/SurfaceView laeuft.
- `setupImageReader()` ist laut Kommentar Dead Code.
- Media3/ExoPlayer-Dependencies sind noch in `android/app/build.gradle`, obwohl der aktive RTSP-Pfad direkt ueber MediaCodec laeuft.
- `RtspSdpProxy.kt` enthaelt viele ExoPlayer-Kommentare aus frueheren Versionen.

Das ist kein akuter Laufzeitfehler, aber es macht Fehlersuche schwerer.

Empfehlung:

- Nach Stabilisierung des RTSP-Pfads einen Cleanup-Sprint:
  - tote Pfade entfernen oder eindeutig als Legacy markieren
  - ungenutzte Dependencies entfernen
  - Kommentare auf aktive Architektur aktualisieren

### P2: `index.html` ist als Lieferformat okay, aber als Denkmodell zu gross

`index.html` ist ca. 382 KB gross und enthaelt 80+ Top-Level-Funktionen. Darin liegen:

- UI
- Kamera-Modi
- Android-Bridge
- Kalibrierung
- Live-Erkennung
- Vergleichsmodus
- Training/Test-Overlay
- Persistenz/localStorage
- Speech/TTS
- Update/Service Worker

Das war fuer schnelle Iteration richtig. Jetzt steigt aber das Risiko fuer Seiteneffekte: eine Aenderung an Kamera/Canvas kann Trefferlogik, Overlay oder Referenzvergleich beeinflussen.

Empfehlung in der aktuellen Findungsphase:

- Nicht sofort in eine echte Mehrdatei-App umbauen.
- `index.html` als Offline-Lieferformat behalten.
- Aber intern klare Module/Namespaces einfuehren:
  - `TP.config`
  - `TP.state`
  - `TP.camera`
  - `TP.calib`
  - `TP.detect`
  - `TP.compare`
  - `TP.ui`
  - `TP.bridge`

## Empfehlung zur Single-File-Frage

Offline-Faehigkeit bleibt die Direktive. Eine echte installierbare App ist mittelfristig sinnvoll, aber jetzt ist noch Findungsphase fuer saubere Treffererkennung. Deshalb:

### Jetzt: Single-File als Lieferformat behalten

Keine grosse Architektur-Migration, solange KK25/GK25 noch fachlich validiert werden. Die aktuelle Form erlaubt schnelles Testen am Stand und vermeidet neue Build-/Packaging-Fehler.

Aber: Single-File nicht mehr als Denkmodell verwenden. Innerhalb von `index.html` sollten klare Grenzen entstehen.

### Als naechster technischer Schritt: Trefferlogik testbar machen

Der groesste Hebel ist eine reine Erkennungsfunktion, die keine DOM-/Canvas-/Bridge-Seiteneffekte hat, z.B. konzeptionell:

```js
TP.detect.findHits({
  refImage,
  curImage,
  calib,
  disc,
  settings
})
```

Rueckgabe:

```js
{
  hits,
  candidates,
  rejected,
  debug
}
```

Diese Funktion sollte nicht direkt:

- Buttons anfassen
- `localStorage` lesen/schreiben
- Speech/TTS ausloesen
- Canvas zeichnen
- globale `hits` mutieren
- Bridge-Funktionen aufrufen

Damit koennen echte Standbilder und Session-Daten reproduzierbar gegen Ground Truth getestet werden.

### Spaeter: App-Architektur entscheiden

Wenn KK25/GK25 fachlich stabil sind, gibt es zwei gute Pfade:

1. Android-WebView-App bleibt Kern  
   HTML/JS bleibt offline gebundled. Android liefert Kamera, Dateisystem, TTS, Updates.

2. Hybrid mit nativer Kamera/IO und JS-Erkennungsmodul  
   Die Trefferlogik wird als eigenes JS-Modul sowohl im Web als auch in Android genutzt.

Eine komplett native Kotlin/OpenCV-Erkennung sollte erst kommen, wenn der Algorithmus fachlich stabil ist. Sonst werden gleichzeitig Bildverarbeitung, Scheibenlogik und App-Architektur umgebaut.

## Priorisierte Naechste Schritte

1. Version-/Asset-Drift beheben und automatischen Check einfuehren.
2. `stopAllActiveStreams()` als zentrale Cleanup-Stelle vervollstaendigen.
3. Dev-/Debug-Funktionen sauber hinter Debug-Builds setzen.
4. Trefferlogik aus UI-Seiteneffekten herausloesen, zuerst ohne Dateisplitting.
5. Danach alte RTSP-/ExoPlayer-Kommentare und ungenutzte Dependencies bereinigen.

## Nicht empfohlen im Moment

- Sofortige Umstellung auf echte Mehrdatei-Web-App.
- Sofortige native Kotlin/OpenCV-Neuimplementierung der Treffererkennung.
- Ein Build-System/npm einfuehren, solange Offline-Direktive und schnelle Standtests Prioritaet haben.

## Arbeitsnotiz fuer Claude

Dieses Dokument ist absichtlich als Handoff-lesbare Review-Notiz geschrieben. Es enthaelt keine Codeaenderungen und keine vorgeschlagene Migration, die sofort umgesetzt werden muss. Wichtig ist die Reihenfolge:

1. Stabilitaet und Release-Disziplin.
2. Saubere, testbare Trefferlogik.
3. Erst danach groessere App-Architektur.
