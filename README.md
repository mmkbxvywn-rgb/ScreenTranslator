# ScreenTranslator

Android app prototype for translating on-screen text and spoken audio into Simplified Chinese.

## Implemented

- Android MediaProjection consent flow and foreground screen-capture service.
- On-device Chinese OCR with Google ML Kit, sampling the screen approximately every 1.6 seconds and emitting changed text.
- Configurable OpenAI-compatible `/chat/completions` endpoint, API key, and model.
- Translation of changed OCR text into Simplified Chinese.
- Floating overlay for recognized text and translated output.
- Optional microphone speech recognition with repeated listening sessions and translation of recognized phrases.
- Foreground-service support for microphone use while the app is backgrounded.

## Build and install

GitHub Actions builds a debug APK on each push to `main`. Open the repository's **Actions** tab, choose a successful **Build Android APK** run, and download the `ScreenTranslator-debug-apk` artifact.

## First use

1. Install the debug APK.
2. Enter the API endpoint, API key, and model, then save the settings.
3. To translate on-screen text, allow **Display over other apps**, tap **Authorize and start live recognition**, and approve Android's screen-capture prompt.
4. To translate spoken audio, tap **Start speech transcription and translation** and grant microphone permission. The speech recognizer's source language depends on the Android speech-recognition service and device settings.

## Important limitations

- This is an early prototype and has not yet been verified on a physical device.
- Speech recognition uses the device microphone and the Android speech-recognition service. It is not direct capture of another app's internal audio stream; speaker playback may be picked up, but headphones or quiet playback may not be audible to the microphone.
- Screen OCR samples frames periodically; fast-changing text may be missed. Overlay placement and behavior vary by device.
- Recognized screen text and speech transcripts are sent to the configured translation API. Do not use it on sensitive screens unless you trust that provider.
- API keys are stored in the app's private local preferences. Debug APKs are unsigned development builds and are not intended for public distribution.
