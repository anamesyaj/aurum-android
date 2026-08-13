# Aurum Android

**Aurum Android is the public Android client for Aurum, a personal AI project designed to provide one persistent assistant across devices.**

This repository exists so the Android client can be inspected, built, tested, and validated independently without exposing the private Aurum Core or the owner's private project data.

## What is Aurum?

Aurum is a personal AI system intended to act as an ongoing assistant rather than a standalone chat app. The broader project is being built around one Aurum identity that can eventually support conversation, voice, memory, automation, device interaction, and other personal-assistant capabilities across platforms.

The full Aurum system has two important parts:

- **Aurum Core** — the private backend that owns conversation/orchestration, durable state, memory, automation, model routing, and protected operational configuration.
- **Aurum Android** — the phone client in this repository. It provides the Android user interface, secure pairing, text conversation, and native voice interaction with a compatible Aurum Core.

This repository contains **only the Android client and its public Android CI configuration**. It does not contain Aurum Core source, private project memory, deployment configuration, credentials, signing keys, private infrastructure details, or the canonical private repository history.

## Why is this repository public?

The Android client is deliberately separated into a fresh public repository so that:

- its source can be reviewed transparently;
- Android builds can run on standard public GitHub-hosted CI;
- APK build/test behavior can be reproduced without granting access to the private Aurum repository;
- private Aurum Core code, credentials, infrastructure, memory, and operational history remain isolated.

Making this Android repository public does **not** make the full Aurum system public.

## What can this app do today?

Current milestone: **A3 native voice** (`0.3.0-a3`, version code 4).

The current client supports:

- authenticated text conversation with a compatible Aurum Core endpoint;
- foreground push-to-talk voice input using Android `SpeechRecognizer`;
- visible partial and final speech transcripts;
- Android `TextToSpeech` playback of Aurum replies;
- Filipino, English, and Taglish-friendly device-language behavior;
- Android Keystore protection for the user-supplied Aurum remote access key;
- sanitized in-app diagnostics for phone-first testing.

Aurum Android does **not** include an AI model, public Aurum service, Core endpoint, or access key. To actually converse with Aurum, the user must pair the app with a compatible Aurum Core that they are authorized to use.

## Current development direction

A3 is the first native Android voice slice. It is intentionally foreground push-to-talk rather than an always-listening assistant.

Later Aurum work is intended to expand toward capabilities such as improved conversational voice, wake-word support, deeper Android integration, automation, screen/device interaction, and useful local/offline behavior. Those capabilities are not implied to exist merely because they are part of the broader project direction.

## Voice and privacy

A3 requests `RECORD_AUDIO` only when the user invokes voice input. Aurum Android does not intentionally persist raw microphone audio.

Speech recognition and text-to-speech use Android platform services. Whether speech data is processed locally or by an external speech-service provider depends on the device and the Android speech services configured by the user.

The app can generate a sanitized diagnostics report. Diagnostics do not include the Aurum access key, but they can include the configured backend hostname, so review a report before sharing it if hostnames are sensitive.

## Pairing with Aurum Core

A compatible Aurum Core must expose the authenticated remote API expected by this client.

The user enters the Core base URL and remote access key inside the app. Credentials must not be embedded in the URL or committed to this repository.

Release builds require HTTPS. Debug builds may use plain HTTP only for localhost/private-LAN development addresses accepted by the app's URL policy.

## Build requirements

- JDK 17
- Android SDK API 36
- Android build-tools 36.0.0
- Gradle 9.5.0
- Android Gradle Plugin 9.3.0
- minSdk 26 / targetSdk 36

With the required Android SDK and Gradle installed:

```bash
gradle --no-daemon clean testDebugUnitTest lintDebug assembleDebug
```

The debug APK is produced at:

```text
app/build/outputs/apk/debug/app-debug.apk
```

The public GitHub Actions workflow performs the same unit tests, lint, and debug APK build and publishes the APK plus SHA-256 metadata as a workflow artifact.

## Signing

Public CI currently creates a fresh ephemeral debug signing identity. It does not contain the private project's earlier signing key.

Because Android requires matching signing identities for in-place updates, an APK produced here may not update an older Aurum APK signed with a different key. During the current test phase, uninstall/reinstall may therefore be required, which also clears app-local configuration.

## Repository safety boundary

Do not publish access keys, private endpoints, signing keys, tokens, private infrastructure information, or other credentials in issues, pull requests, build logs, screenshots, or commits.

If you are reviewing this repository, keep in mind that it is intentionally only one public client component of a larger private personal-AI system.

## License status

This repository intentionally does **not** grant an open-source license at this time.

The source is publicly visible for transparent development, review, and Android CI, but no MIT or other open-source license should be inferred from that visibility. See [`NOTICE.md`](NOTICE.md) for the current provenance and license-status notice.
