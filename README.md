# Aurum Android

Aurum Android is the Android client for Aurum, a personal AI system. This repository contains only the Android application source and public Android CI configuration; it does not contain Aurum Core, private project memory, deployment configuration, credentials, or private repository history.

Current app milestone: **A3 native voice** (`0.3.0-a3`, version code 4).

## What the app does

- authenticated text conversation with a compatible Aurum Core endpoint;
- foreground push-to-talk voice input using Android `SpeechRecognizer`;
- partial/final speech transcript display;
- Android `TextToSpeech` playback of Aurum replies;
- Filipino, English, and Taglish-friendly device-language behavior;
- Android Keystore protection for the user-supplied Aurum remote access key;
- sanitized in-app diagnostics.

Aurum Android does **not** ship with a Core endpoint or access key. The user supplies both at runtime.

## Voice and privacy

A3 is foreground push-to-talk only. `RECORD_AUDIO` is requested only when voice input is invoked. Aurum Android does not intentionally persist raw microphone audio. Speech recognition and text-to-speech are Android platform services; whether speech data is processed locally or by a service provider depends on the device and configured Android speech services.

The app can share a sanitized diagnostics report. Diagnostics do not include the Aurum access key, but they can include the configured backend hostname, so review a report before sharing it if hostnames are sensitive.

## Requirements

- JDK 17
- Android SDK API 36
- Android build-tools 36.0.0
- Gradle 9.5.0
- Android Gradle Plugin 9.3.0
- minSdk 26 / targetSdk 36

## Build

With the required Android SDK and Gradle installed:

```bash
gradle --no-daemon clean testDebugUnitTest lintDebug assembleDebug
```

The debug APK is produced at:

```text
app/build/outputs/apk/debug/app-debug.apk
```

The public GitHub Actions workflow performs the same tests, lint, and debug APK build and uploads the APK plus SHA-256 metadata as a workflow artifact.

## Pairing with Aurum Core

A compatible Aurum Core must expose the authenticated remote API expected by this client. Enter the Core base URL and remote access key inside the app. Do not put credentials in the URL.

Release builds require HTTPS. Debug builds may use plain HTTP only for localhost/private-LAN development addresses accepted by the app's URL policy.

## Signing

Public CI uses a fresh ephemeral debug signing identity and does not contain the private project's previous signing key. An APK produced here therefore may not update an older Aurum APK signed with a different key; uninstall/reinstall may be required, which clears app-local configuration.

## License status

This repository intentionally does **not** grant an open-source license at this time. The source is publicly visible for transparent development, review, and CI, but no MIT or other open-source license should be inferred. See `NOTICE.md` for the current provenance/license-status notice.

Do not publish access keys, private endpoints, signing keys, or other credentials in issues, pull requests, build logs, or commits.
