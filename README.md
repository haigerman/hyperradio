# HyperRadio

Android-App für Webradio – auf dem Handy und in **Android Auto**.

## Sender

| Sender | Stream |
|---|---|
| 🤘 **Radio Bob Live** | `streams.radiobob.de/bob-live` (MP3 192 kbit/s, Ausweich: 128 kbit/s) |
| 🎸 **Radio Bob Grunge** | `streams.radiobob.de/bob-grunge` (MP3 192 kbit/s, Ausweich: 128 kbit/s) |
| 🏔️ **Bayern 1** | BR-Stream Oberbayern über `streams.br.de/bayern1obb_2.m3u` (Ausweich: `dispatcher.rndfnk.com`, MP3 high/mid) |
| 🎧 **Virgin Radio** | Virgin Radio Switzerland, `stream.streambase.ch/vrock` (MP3 192 kbit/s, Ausweich: 128 kbit/s) |
| 🎷 **Smooth Jazz Radio DE** | smooth-jazz-radio.de über laut.fm, `stream.laut.fm/pure-smooth-jazz` |

Jeder eingebaute Sender hat mehrere Adressen. Fällt eine aus, wechselt die App automatisch
zur nächsten. Die Liste steht in `data/Station.kt` (`Presets`).

**Eigene Sender:** Unten in der Liste auf **＋ Sender hinzufügen** tippen, Name und
Stream-URL eingeben – fertig. Neben direkten Streams (MP3/AAC/Ogg) gehen auch
Playlist-Links (`.m3u`, `.pls`) und HLS (`.m3u8`). Über ✎ lassen sich eigene Sender
umbenennen, ändern oder löschen.

## Funktionen

- Wiedergabe im Hintergrund mit Benachrichtigung und Sperrbildschirm-Steuerung
- **Android Auto:** Senderliste auf dem Auto-Display, Weiter/Zurück am Lenkrad wechselt
  den Sender, Sprachbefehl („Spiele Bayern 1 auf HyperRadio“), nach dem Einsteigen wird
  der letzte Sender angeboten
- Anzeige des laufenden Titels, wenn der Sender ihn mitschickt
- Pause bei Anruf/Navigationsansage/anderer Audio-App und beim Abziehen der Kopfhörer
- Längere Pause (> 1 min) → beim Fortsetzen geht es wieder live weiter statt zeitversetzt

## Puffer & Verbindungsabbrüche

Damit die Wiedergabe auch unterwegs (Funklöcher, Tunnel, Wechsel WLAN ↔ Mobilfunk) ohne
Aussetzer läuft:

| Einstellung | Wert | Wirkung |
|---|---|---|
| Start nach | 4 s Puffer | schneller Start, aber schon mit Polster |
| Nach einem Aussetzer weiter nach | 10 s Puffer | größeres Polster, damit es nicht gleich wieder stockt |
| Vorhalten | mind. 30 s, höchstens 120 s | lädt voraus, solange das Netz schneller ist als der Stream |
| Netzfehler beim Laden | bis zu 6 interne Wiederholungen (1–5 s Abstand) | kurze Aussetzer werden aus dem Puffer überbrückt, ohne dass die Wiedergabe abbricht |
| Verbindung weg | automatisch neu verbinden, nach mehreren Fehlern nächste Ausweich-URL, Abstand wächst bis 30 s | |
| Kein Netz | wartet und verbindet **sofort**, wenn wieder Netz da ist | |

Außerdem hält die App CPU und WLAN wach, solange sie spielt, und folgt Weiterleitungen
zwischen http und https. Unten im Bildschirm zeigt ein Balken, wie viel gerade gepuffert ist.

Die Werte stehen in `playback/PlaybackService.kt` (`MIN_BUFFER_MS` …).

## Aufbau

| Datei | Aufgabe |
|---|---|
| `MainActivity.kt` | Startet die Oberfläche, steuert den Dienst über einen `MediaController` |
| `ui/RadioScreen.kt` | Hauptbildschirm: Sender, Hinzufügen-Dialog, Play-Knopf, Puffer-Anzeige |
| `playback/PlaybackService.kt` | Media3 `MediaLibraryService`: Player, Puffer, Wiederverbinden, Senderliste für Android Auto |
| `playback/StreamResolver.kt` | Löst `.m3u`/`.pls`-Playlists zur eigentlichen Stream-Adresse auf |
| `data/Station.kt` | Sender und die eingebauten Streams |
| `data/RadioRepository.kt` | Gemeinsamer Zustand von UI und Dienst, gespeicherte eigene Sender |

Mindestens Android 8.0 (API 26), Ziel-API 36.

## Android Auto ausprobieren

Apps, die nicht aus dem Play Store kommen, zeigt Android Auto erst nach einer Einstellung an:
Android Auto-Einstellungen öffnen → mehrmals auf *Version* tippen, bis die
Entwicklereinstellungen frei sind → dort *Unbekannte Quellen* aktivieren. Ohne Auto lässt
sich das mit der *Desktop Head Unit* (DHU) aus dem Android SDK testen.

## Lokal bauen

Voraussetzungen: JDK 17+ und das Android SDK (Platform 36).

```bash
./gradlew :app:assembleDebug
```

Die APK liegt danach unter `app/build/outputs/apk/debug/app-debug.apk`.
Tests: `./gradlew :app:testDebugUnitTest`.

## Release (Installation über Obtainium)

1. Einmalig Repository-Secrets anlegen (*Settings → Secrets and variables → Actions*):
   - `KEYSTORE_BASE64` – Keystore, base64-kodiert (`base64 -w0 release.jks`)
   - `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`

   Alternativ funktionieren auch die Namen wie bei First Light (`SIGNING_KEYSTORE_BASE64`,
   `SIGNING_KEYSTORE_PASSWORD`, `SIGNING_KEY_ALIAS`, `SIGNING_KEY_PASSWORD`).

   Keystore erzeugen (falls noch keiner vorhanden):
   ```bash
   keytool -genkeypair -v -keystore release.jks -alias hyperradio -keyalg RSA -keysize 4096 -validity 36500
   ```
   Den Keystore gut aufbewahren – Updates funktionieren nur mit derselben Signatur.
2. Tag pushen:
   ```bash
   git tag v0.1.0 && git push origin v0.1.0
   ```
   Der Workflow baut die signierte APK und hängt sie an ein GitHub-Release.
3. In Obtainium: *App hinzufügen* → `https://github.com/haigerman/hyperradio`.

Versionscode wird aus dem Tag berechnet (`v1.2.3` → 10203).
