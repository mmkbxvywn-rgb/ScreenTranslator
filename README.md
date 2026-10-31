# ScreenTranslator

Android app prototype for translating on-screen text and spoken audio into Simplified Chinese.

## Features

- Android MediaProjection screen capture and floating subtitles.
- On-device Chinese OCR using Google ML Kit.
- Online translation through a configurable OpenAI-compatible `/chat/completions` API.
- Offline translation mode using Google ML Kit on-device translation. Translation models are downloaded on first use and retained on the device; after the needed language model has downloaded, text translation can work without network access.
- A visible button switches between online and offline translation.
- Android internal playback audio capture, with speech transcription sent to a configurable `/audio/transcriptions` API.

## Build and install

GitHub Actions builds a debug APK on each push to `main`. Open the repository's **Actions** tab, choose a successful **Build Android APK** run, and download the `ScreenTranslator-debug-apk` artifact.

## First use

1. Install the debug APK.
2. Allow **Display over other apps** and tap **Authorize and start live recognition**; approve Android's screen-capture prompt.
3. Choose online or offline translation with the translation-mode button.
4. For online translation, configure the API endpoint, API key, and model.
5. For speech translation, configure a provider that supports `/audio/transcriptions`, choose the audio language, grant Android's audio permission, then start internal audio capture after screen-capture authorization.

## Offline mode notes

- The translation engine runs on-device, but ML Kit language models are **downloaded on first use rather than bundled inside the APK**. Keep a network connection for the first use of each source language; after download, that language's translation model can work offline.
- Offline mode applies to text translation, including recognized screen text and transcribed speech. **Speech-to-text itself still uses the configured online `/audio/transcriptions` API**, so full offline audio translation is not implemented in this version.
- If the detected source language is not supported by ML Kit, switch to online translation.

## Limitations

- The APK must be tested on the target physical device; Android overlay behavior, internal audio capture support, and manufacturer background restrictions can vary.
- Some apps prohibit internal playback capture.
- Screen OCR samples frames periodically, so very fast-changing text can be missed.
- Online mode sends recognized text to the configured translation provider. Audio speech recognition sends captured audio to the configured transcription provider.
- API keys are stored in app-private local preferences. Debug APKs are development builds, not signed for public distribution.
