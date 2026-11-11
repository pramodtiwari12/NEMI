# NEMI — 100% Offline On-Device Android Voice Assistant

<p align="center">
  <strong>Your Voice. Your Device. Your Control.</strong>
  <br>
  A fast, privacy-focused Android voice assistant designed to execute everyday tasks directly on your device.
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Platform-Android-green?logo=android" />
  <img src="https://img.shields.io/badge/Language-Kotlin-purple?logo=kotlin" />
  <img src="https://img.shields.io/badge/UI-Jetpack%20Compose-blue" />
  <img src="https://img.shields.io/badge/Internet-100%25%20Offline-success" />
  <img src="https://img.shields.io/badge/Min%20SDK-26-orange" />
</p>

<p align="center">
  <a href="https://github.com/YOUR_GITHUB_USERNAME/YOUR_REPOSITORY_NAME/releases/latest">
    <img src="https://img.shields.io/badge/Download-Latest%20APK-brightgreen?style=for-the-badge&logo=android" alt="Download NEMI APK">
  </a>
</p>

---

## About NEMI

**NEMI** is a fast, 100% offline Android voice assistant built using Kotlin, Jetpack Compose, and Material 3.

Unlike conventional voice assistants that depend on cloud processing for everyday operations, NEMI is designed to execute routine commands directly on your Android device.

Its goal is simple:

**Make everyday voice interactions feel almost instantaneous.**

With a target response time of approximately **100 milliseconds for supported routine tasks**, NEMI aims to deliver a responsive experience inspired by the speed and simplicity of Apple Siri.

### Privacy First

- Zero internet permissions.
- Zero API keys.
- Zero cloud API calls.
- On-device speech recognition using Vosk.
- Local command processing and execution.

Your everyday commands stay on your device.

---

## Key Features

| Feature | Description |
|---|---|
| Offline Voice Recognition | On-device speech recognition using Vosk |
| Fast Execution | Optimized routine command processing |
| Wake Word Detection | Customizable wake phrases |
| Smart App Launcher | Fuzzy matching and app aliases |
| Contact Calling | Smart contact and call-history selection |
| Hinglish Support | Understands English and Hinglish commands |
| System Controls | Flashlight, volume, brightness and more |
| Privacy Focused | No cloud API dependency |

---

## Technology Stack

- **Language:** Kotlin
- **UI Framework:** Jetpack Compose
- **Design System:** Material 3
- **Speech Recognition:** Vosk Android (`com.alphacephei:vosk-android:0.3.47`)
- **Speech Model:** `vosk-model-small-en-in-0.4`
- **Audio Processing:** Android AudioRecord and AudioTrack
- **Speech Output:** Android Text-to-Speech
- **Minimum Android SDK:** 26 (Android 8.0)

---

## Bundling the Indian English Vosk Model

NEMI uses the Vosk Android library with the small Indian English speech recognition model.

**Model:** `vosk-model-small-en-in-0.4`

**Approximate size:** 36 MB

### 1. Download the Model

Download the Indian English model from the official Vosk models repository:

https://alphacephei.com/vosk/models

Download:

`vosk-model-small-en-in-0.4.zip`

### 2. Extract the Model

Extract its contents into:

```text
app/src/main/assets/model-en-in/
```

Expected directory structure:

```text
model-en-in/
├── am/
│   └── final.mdl
├── conf/
│   ├── mfcc.conf
│   └── model.conf
├── graph/
│   ├── HCLr.fst
│   ├── Gr.fst
│   └── phones/
│       └── word_boundary.int
├── ivector/
│   ├── final.dubm
│   ├── final.ie
│   ├── final.mat
│   ├── global_cmvn.stats
│   ├── online_cmvn.conf
│   └── splice.conf
└── uuid
```

### 3. Model Initialization

On first launch, `VoskModelManager`:

1. Checks `assets/model-en-in/uuid`.
2. Extracts the model into internal application storage.
3. Initializes the model from `filesDir/model-en-in`.
4. Warms up the model when the voice service starts.

**Optional future expansion:** A Hindi Vosk model can be added under `app/src/main/assets/model-hi/`.

### Automatic On-Device Fallback

If the Vosk model has not been bundled, `MicrophoneVoskPipeline` can still use:

- Real 16 kHz AudioRecord input.
- Microphone source fallback.
- Live RMS microphone monitoring.
- Software audio gain.
- Wake-beep and vibration verification.
- Android's built-in SpeechRecognizer with `EXTRA_PREFER_OFFLINE = true`.

**Note:** Offline fallback recognition depends on the Android device's installed on-device speech recognition capabilities and language packs. `EXTRA_PREFER_OFFLINE` is a preference, not a universal guarantee of offline recognition.

---

## Audio Pipeline & Voice Processing

### 1. Persistent AudioRecord Pipeline

NEMI uses a single persistent AudioRecord pipeline configured with:

- Sample Rate: `16000 Hz`
- Channels: Mono
- Encoding: `PCM_16BIT`
- Buffer Size: At least `AudioRecord.getMinBufferSize(...) * 2`

**Audio Source Fallback Chain**

Automatically probes:

1. `VOICE_RECOGNITION`
2. `MIC`
3. `VOICE_COMMUNICATION`

Selects the first source that produces a non-silent signal.

**Software Gain**

Applies a `2.5x` clamped PCM gain boost before passing audio frames to Vosk through:

```kotlin
acceptWaveForm(buffer, readCount)
```

**Live RMS Monitoring**

- Exposes microphone RMS levels (`0..1`) in Diagnostics.
- Detects silent microphone input.
- Displays a warning when RMS remains near zero:

`Microphone is silent (another app or OS may be blocking audio)`

### 2. Wake Word Detection

NEMI supports selectable wake phrases:

- `Hey Computer`
- `Hey Phone`
- `OK Phone`

Wake phrase matching works with both partial and final recognition results using normalized fuzzy matching.

**Wake-up Feedback:**

- Dual-tone audio chime.
- Short vibration.
- Command mode activation.

**Audio Chime**

Uses:

- `880 Hz → 1320 Hz` dual-tone sequence.
- AudioTrack with `USAGE_ASSISTANCE_SONIFICATION`.
- Fallback to `USAGE_MEDIA` and ToneGenerator.

To prevent the chime from being transcribed, audio feeding to Vosk pauses for approximately `220 ms` around the beep.

Diagnostic event:

`BEEP_PLAYED`

### 3. Free Recognition & Hinglish Command Parser

NEMI supports free speech recognition for commands and application names.

Examples:

```text
Open WhatsApp
WhatsApp kholo
Mom ko call karo
Torch chalu karo
Torch band karo
Alarm lagao
```

The parser also removes common filler words:

- Please
- Can you
- The

---

## Supported Offline Commands

### 1. Open Installed Applications

Examples:

```text
Open WhatsApp
Open YouTube
Open Insta
Open You Tube
WhatsApp kholo
```

Implementation:

- Uses `MAIN` / `LAUNCHER` activity queries.
- Avoids `QUERY_ALL_PACKAGES`.
- Maintains an in-memory application cache.
- Automatically refreshes on `PACKAGE_ADDED`, `PACKAGE_REMOVED`, and `PACKAGE_REPLACED`.
- Supports editable aliases through `app/src/main/assets/app_aliases.txt`.
- Uses Double Metaphone phonetic keys.
- Uses Levenshtein edit distance.
- Considers application launch frequency.

**Smart Disambiguation**

When multiple applications receive similar matching scores:

- Displays a three-option disambiguation dialog.
- Learns the user's selection as a permanent alias.

### 2. Contact Calling

Examples:

```text
Call Jagdish
Mom ko call karo
```

Features:

- Contact name recognition.
- Smart call-history-based number selection.
- Prioritizes a clear winner from the last 30 days.
- If ambiguous, displays a minimal selection dialog.
- Shows the contact name and last two digits of the top two numbers.

Example:

```text
Jagdish

1. Number ending in 42
2. Number ending in 87

Say one or two.
```

### 3. Flashlight / Torch

```text
Torch on
Torch off
Torch chalu karo
Torch band karo
```

### 4. Alarm & Timer

```text
Set alarm 7 AM
Timer 5 minutes
```

### 5. Volume & Brightness

```text
Volume up
Volume down
Mute
Volume 50 percent
Brightness up
Brightness down
```

### 6. Time, Date & Battery

Spoken using on-device Text-to-Speech.

```text
What time is it?
What is today's date?
Battery level
```

### 7. Media Controls

```text
Play
Pause
Next song
Previous
```

### 8. System Settings Panels

```text
WiFi
Bluetooth
Do not disturb
```

---

## Testing & Diagnostics

NEMI includes an advanced diagnostics section to verify microphone input, wake-word detection, audio feedback, and command execution.

### Six-Step Test Checklist

**1. Test Beep & Microphone Level**

Navigate to:

`Settings → Advanced & Diagnostics`

- Tap Beep Test.
- Verify the chime and vibration.
- Check the `BEEP_PLAYED` event.
- Tap Mic Test or enable the Home switch.
- Speak and verify that the Live Microphone RMS Level bar moves.
- Check the active audio source.

**2. Test Wake Phrase Detection**

Choose a wake phrase in Settings:

- Hey Computer
- Hey Phone
- OK Phone

Speak the selected phrase and verify that the phone plays the chime, vibrates, and enters command mode.

Expected diagnostic status:

`Wake Word Detected: YES (Command Mode)`

**3. Test Opening Applications**

Test the following commands:

```text
Open WhatsApp
Open YouTube
Open Insta
Open You Tube
WhatsApp kholo
```

Verify successful application launches or the top-three disambiguation dialog.

**4. Test Multi-Number Contact Calling**

- Select a contact with two or more phone numbers.
- Say `Call Jagdish`.
- Verify smart call-history selection.
- If no clear winner exists, verify that the number-selection dialog appears.

**5. Test System Commands**

```text
Torch on
Torch off
Set alarm 7 AM
Timer 5 minutes
Volume up
What time is it?
```

**6. Test Airplane Mode**

Enable Airplane Mode and repeat Steps 2–5.

Verify that supported commands continue to work without network connectivity.

For complete offline verification, ensure that the Vosk model or a supported on-device speech recognition language pack is installed.

---

## Download NEMI

<p align="center">
  <strong>Get the latest version of NEMI for Android.</strong>
  <br>
  Download the APK directly from GitHub Releases.
</p>

<p align="center">
  <a href="https://github.com/YOUR_GITHUB_USERNAME/YOUR_REPOSITORY_NAME/releases/latest">
    <img src="https://img.shields.io/badge/Download%20NEMI-APK-brightgreen?style=for-the-badge&logo=android" alt="Download NEMI APK">
  </a>
</p>

**APK Release:** Coming soon.

All future Android APK releases will be published through GitHub Releases.

[View All Releases](https://github.com/YOUR_GITHUB_USERNAME/YOUR_REPOSITORY_NAME/releases)

---

## Project Status

NEMI is under active development.

The project focuses on improving:

- Speech recognition accuracy.
- Wake-word reliability.
- Command execution speed.
- Microphone stability.
- Application matching.
- Offline capabilities.
- User experience.

---

## Privacy

Privacy is a core design principle of NEMI.

- No cloud-based command processing.
- No API keys.
- No cloud API calls.
- No internet permission.
- Speech recognition and command processing are designed to run locally.

NEMI aims to keep voice interactions and everyday tasks on the user's device.

---

## License

License information will be added here.

---

<p align="center">
  <strong>NEMI — Fast. Private. Offline.</strong>
  <br>
  Built for a smarter, more responsive Android experience.
</p>