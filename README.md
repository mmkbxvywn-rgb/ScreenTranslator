# ScreenTranslator

Android prototype for translating on-screen text and spoken audio into Simplified Chinese.

## Implemented

- Android MediaProjection consent flow and foreground screen-capture service.
- On-device Chinese OCR with Google ML Kit, sampling the screen about every 1.6 seconds and translating changed text.
- Configurable OpenAI-compatible `/chat/completions` endpoint, API key, and model.
- Translation runs in the foreground service, so switching to another app does not depend on the activity staying visible.
- Floating subtitle overlay with a close (×) control; the app can reopen it and restore the latest translation.
- Optional repeated microphone speech recognition and translation, with a configurable recognition language code (default `en-US`).
- Foreground-service handling for screen capture and microphone speech recognition.

## Build and install

GitHub Actions builds a debug APK on each push to `main`. Open the repository's **Actions** tab, choose a successful **Build Android APK** run, and download the `ScreenTranslator-debug-apk` artifact.

## First use

1. Install the debug APK.
2. Enter the API endpoint, API key, and model, then save the settings.
3. For screen translation, allow **Display over other apps**, tap **Authorize and start live recognition**, and approve Android's screen-capture prompt.
4. For speech translation, allow **Display over other apps**, enter the source language code (for example `en-US`, `ja-JP`, or `ko-KR`), tap **Start speech transcription and translation**, and grant microphone permission.
5. To stop screen OCR, tap **Stop screen recognition**. The speech recognizer can continue if it is active. To stop speech recognition, tap its stop button.

## Important limitations

- The APK has been built by GitHub Actions but has not yet been verified on a physical device; overlay behavior and manufacturer background restrictions still need device testing.
- Speech recognition uses the phone microphone and Android's speech-recognition service. It does not capture another app's internal audio stream directly; speaker playback may be picked up, but headphones or quiet playback may not be audible to the microphone.
- Screen OCR samples frames periodically, so very fast-changing text can be missed.
- Recognized screen text and speech transcripts are sent to the configured translation API. Do not use it on sensitive screens unless you trust that provider.
- API keys are stored in the app's private local preferences. Debug APKs are development builds, not signed for public distribution.
