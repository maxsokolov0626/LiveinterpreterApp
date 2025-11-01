# Live Interpreter (RU → EN) — Android (External Mic + Bluetooth Output)

- Locks input to **external USB‑C / wired mic**
- **Offline** STT via **Vosk** (Russian)
- **Translate** via ML Kit (RU → EN)
- **Speak** via Android TTS → **Bluetooth earbuds**

## Build without a PC (GitHub Actions)
1. Create a new **GitHub repo** on your phone/tablet.
2. Upload this whole project preserving folders (or create files one-by-one).
3. Push. GitHub will auto-build a **debug APK**.
4. Repo → **Actions** → latest run → **Artifacts** → download `app-debug.apk`.

## First Run
1. Pair **Bluetooth earbuds**.
2. Plug in **USB‑C wireless receiver**.
3. Place Vosk RU model in:
   `/Android/data/com.max.liveinterpreter/files/vosk-model`
4. Open app → pick Input Mic & Output → **Start**.

### Vosk RU model
Use `vosk-model-small-ru-0.22` (or similar) and copy its *contents* into the folder above.
