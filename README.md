# Watch AI

Small-screen Android watch AI client for OpenAI-compatible chat APIs.

## Features

- Native Java Android app with no third-party runtime dependencies.
- Works on small round screens and Android 11-class watch devices.
- Configurable API Base URL, API Key, and model name.
- Auto-fetches model names from OpenAI-compatible `/models`.
- Local multi-session chat history with long-press deletion.
- Compact Markdown and math rendering for common discrete math symbols.

## Build

The project is intentionally minimal and is built with the Android SDK command-line tools.

```powershell
.\build.ps1
```

The signed debug APK is written to:

```text
dist\WatchAI-0.1-watch.apk
```

`build.ps1` expects the Android SDK and Android Studio JBR paths used on the original development machine. Adjust those paths if your environment differs.

## Configuration

Open the settings screen in the app and fill:

- API Base URL
- API Key
- Model name

The API key is stored only in the app's local preferences on the device.
