# UC Dub (Android)

Captures audio playing on the phone (YouTube, Instagram, browsers, any app that allows capture),
streams it to Gemini Live for near real-time translation, and plays the translated voice
on top of the original audio, with optional floating subtitles.

## How it works

1. Audio is captured with AudioPlaybackCapture (Android 10+), then downsampled to 16 kHz mono.
2. The audio is streamed to Gemini Live (`gemini-3.5-live-translate-preview`).
3. Translated speech (24 kHz) is played through AudioTrack with the accessibility usage,
   so the dubbed voice is never captured again.
4. Source and translated text are shown as floating subtitles when the overlay permission is granted.

## Setup

1. Get a Gemini API key and enter it in the app. It is stored encrypted on the device.
2. Pick the target language (Persian is the default).
3. Tick the consent box, then press Start and allow screen/audio capture.

## Limits

- Apps that block capture (for example DRM-protected streaming apps) are silent in the capture.
- Expect roughly 1 to 3 seconds of delay.
- Original audio is not ducked automatically. Lower the original volume manually if needed.
- Each session is renewed by the server periodically; the app reconnects automatically.

## Build

Pushing to `main` (or uploading the zip to the repository root) runs the GitHub Actions workflow,
which builds the arm64-v8a, armeabi-v7a and universal release APKs.
