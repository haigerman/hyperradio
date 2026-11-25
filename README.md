# HyperRadio

Android-App für Webradio – auf dem Handy und in **Android Auto**.

## Sender

| Sender | Stream |
|---|---|
| 🌲 **KEXP 90.3 FM** | Seattle, `kexp.streamguys1.com/kexp160.aac` (AAC 160 kbit/s, Ausweich: MP3 128, AAC 64) |
| 🤘 **Radio Bob Live** | `streams.radiobob.de/bob-live` (MP3 192 kbit/s, Ausweich: 128 kbit/s) |
| 🎸 **Radio Bob Grunge** | `streams.radiobob.de/bob-grunge` (MP3 192 kbit/s, Ausweich: 128 kbit/s) |
| 🏔️ **Bayern 1** | BR-Stream Oberbayern über `streams.br.de/bayern1obb_2.m3u` (Ausweich: `dispatcher.rndfnk.com`, MP3 high/mid) |
| 🎧 **Virgin Radio** | Virgin Radio Switzerland, `stream.streambase.ch/vrock` (MP3 192 kbit/s, Ausweich: 128 kbit/s) |
| 🎷 **smoothjazz.com** | SmoothJazz.com, `smoothjazz.cdnstream1.com/2585_320.mp3` (MP3 320 kbit/s, Ausweich: 128 und 256 kbit/s) |
| 🎧 **Lofi Hip Hop Radio** | Lofi-Hip-Hop über laut.fm, `stream.laut.fm/lofi` (Ausweich: `play.streamafrica.net/lofiradio`) |
| 🌴 **Radio Paradise – Main Mix** | `stream.radioparadise.com/mp3-320` (MP3 320, Ausweich: AAC 320, FLAC) |
| 🌅 **Radio Paradise – Mellow Mix** | `stream.radioparadise.com/mellow-320` (AAC 320, Ausweich: FLAC) |
| 🌋 **Radio Paradise – Rock Mix** | `stream.radioparadise.com/rock-320` (AAC 320, Ausweich: FLAC) |
| ☕ **F.A.Z. Frühdenker** (Podcast) | neueste Folge aus `fazfruehdenker.podigee.io/feed/mp3` |
| 📰 **tagesschau** (Podcast) | neueste 20-Uhr-tagesschau aus dem Audio-Feed von tagesschau.de |

Jeder eingebaute Sender hat mehrere Adressen. Fällt eine aus, wechselt die App automatisch
zur nächsten. Die Liste steht in `data/Station.kt` (`Presets`).

**Kategorien:** Die Sender sind in ausklappbare Gruppen sortiert – anfangs 🎸 **Rock** (KEXP,
Radio Bob Live, Radio Bob Grunge, Virgin Radio), 💼 **Work** (smoothjazz.com, Lofi Hip
Hop Radio), 🌴 **Paradise** (Radio Paradise Main, Mellow, Rock), 🎙️ **Podcasts** (F.A.Z. Frühdenker, tagesschau) und 📻 **Übrige** (Bayern 1). Antippen der Überschrift klappt eine
Gruppe auf oder zu (wird gespeichert); läuft ein Sender in einer zugeklappten Gruppe, zeigt die
Überschrift das an. In Android Auto erscheinen die Gruppen als Tabs (höchstens 4 – bei mehr
Kategorien bündelt der vierte Tab „Weitere“ den Rest).

**Sender verwalten (⋮ an jeder Senderkarte):** Kategorie wechseln (auch direkt in eine neue
Kategorie), eigene Sender bearbeiten, Sender löschen – mit „Rückgängig“. Auch eingebaute Sender
lassen sich löschen; „Gelöschte Sender wiederherstellen“ am Listenende holt sie zurück.

**Podcasts:** Für Podcasts ist der Feed hinterlegt. Bei jedem Start – neu ausgewählt,
fortgesetzt (App, Benachrichtigung, Android Auto) oder per Auto Play – fragt HyperRadio den Feed
frisch ab: Gibt es eine neuere Folge, wird sie geladen und beginnt von vorn, sonst geht es an der
bisherigen Stelle weiter. Am Ende der Folge hört die Wiedergabe auf („Folge zu Ende – tippe für die
neueste Folge“), unten stehen Position und Länge. Bei Podcasts springen Zurück und Vor 20 Sekunden zurück bzw. vor statt
zum Sender davor/danach – in der Benachrichtigung (mit „20“-Symbolen), in Android Auto, über
Lenkrad- und Bluetooth-Tasten. In der App stehen dafür zwei Knöpfe neben Play.

**Auto Play (Schalter unten unter dem Play-Knopf):** Ist er an, setzt HyperRadio beim Verbinden mit
Android Auto automatisch den zuletzt gehörten Sender fort (nur wenn gerade nichts läuft). Ob das
Auto verbunden ist, erkennt die App über Androids offizielle Abfrage (CarConnection). Nach jeder
Unterbrechung von mindestens 30 Sekunden (z. B. kurz einkaufen) startet der Sender beim
Wiedereinsteigen erneut; kürzere Verbindungsaussetzer lösen keinen Neustart aus.

**Kategorien verwalten:** „＋ Kategorie“ am Listenende legt eine neue an (Name + Symbol).
Über ⋮ an der Überschrift: umbenennen/Symbol ändern, nach oben/unten schieben, löschen – die
Sender wandern dann nach „Übrige“, das als Sammelstelle immer bleibt.

**Reihenfolge ändern:** Lange auf einen Sender drücken, bis er sich anhebt, dann an die
gewünschte Stelle innerhalb der Gruppe ziehen. Die Reihenfolge wird gespeichert und gilt auch in Android Auto
(Senderliste und Weiter/Zurück).

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
- **Cover für Android Auto & Benachrichtigung:** Senderemoji, Name und die leuchtende Welle.
  Android Auto erlaubt Apps keine eigenen Animationen, deshalb wird das Cover alle 12 s und bei
  jedem neuen Titel mit einem frischen Schnappschuss der echten Welle neu gezeichnet
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
| `playback/AudioTap.kt` | Greift das dekodierte Audiosignal ab (für die Welle im Cover) |
| `playback/CoverArt.kt` | Zeichnet die Cover mit Welle für Android Auto und Benachrichtigung |
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
