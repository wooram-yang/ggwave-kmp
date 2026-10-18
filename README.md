# ggwave-kmp

![Supported Platforms](https://img.shields.io/badge/platform-Android-green.svg)
![Supported Platforms](https://img.shields.io/badge/platform-iOS-blue.svg)
![Supported Platforms](https://img.shields.io/badge/platform-JVM-red.svg)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.wooram-yang/ggwave-kmp.svg)](https://central.sonatype.com/artifact/io.github.wooram-yang/ggwave-kmp)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)


![screen_video_2024-11-16-21 52 58_720p](https://github.com/user-attachments/assets/efd5053a-4076-494d-aeea-7cbe3e5510c6)

## Overview

ggwave-kmp sends and receives short messages through sound on Android, iOS and the JVM (Windows, macOS, Linux). It is a Kotlin Multiplatform wrapper around [ggwave](https://github.com/ggerganov/ggwave), a C++ library that encodes data as audio using an FSK-based protocol ([technical details](https://github.com/ggerganov/ggwave?tab=readme-ov-file#technical-details)).

| Module | Artifact | Use it when |
| --- | --- | --- |
| `:ggwave` | `io.github.wooram-yang:ggwave-kmp` | You want the library to handle the microphone and speaker. |
| `:ggwave-core` | `io.github.wooram-yang:ggwave-core` | Your app already owns audio input and output and only needs encoding and decoding. |

`ggwave-kmp` depends on `ggwave-core`, so add only one of them. `:sampleApp` is a Compose Multiplatform chat app built on `:ggwave`.

## Installation

Both artifacts are published to Maven Central.

```kotlin
repositories {
    mavenCentral()
}

kotlin {
    sourceSets.commonMain.dependencies {
        implementation("io.github.wooram-yang:ggwave-kmp:0.4.0")
        // or, for encoding and decoding only:
        // implementation("io.github.wooram-yang:ggwave-core:0.4.0")
    }
}
```

## Usage

### Sending and receiving with the microphone and speaker

`GgwaveSession` owns the audio devices. Incoming messages and state are exposed as flows.

```kotlin
import io.github.moreflow.ggwave.GgwaveSession

val session = GgwaveSession.create()

// Receive: collect first, then start the microphone.
scope.launch {
    session.receivedMessages.collect { message -> println("received: $message") }
}
scope.launch {
    session.captureErrors.collect { error -> println("capture failed: ${error.message}") }
}
session.startCapture()

// Send: suspends until playback finishes.
scope.launch {
    session.send("hello")
}

// When done.
session.stopCapture()
session.close()
```

- `send()` rethrows the error if encoding or playback fails, and throws `GgwaveException` if playback does not finish within 60 seconds. Calls are serialized, one message at a time.
- `isCapturing`, `isPlaying` and `captureLevel` (input peak, 0 to 1) are `StateFlow`s for UI state.
- Only one `GgwaveSession` can be active per process. `create()` throws until the previous session is closed.

`GgwaveSession.create()` accepts a `GgwaveConfig`:

| Property | Default | Meaning |
| --- | --- | --- |
| `sampleRate` | `48_000` | Capture and playback sample rate, 8,000 to 96,000 Hz. |
| `captureBufferSize` | `4096` | Capture buffer size in bytes; must be a positive even number. |
| `volume` | `50` | Playback volume passed to ggwave, 0 to 100. |
| `protocol` | `AudibleFast` | Protocol used by `send()`. Receiving detects every protocol automatically. |

### Encoding and decoding only

Use `ggwave-core` when you already have a PCM pipeline (16-bit mono samples):

```kotlin
import io.github.moreflow.ggwave.GgwaveCodec
import io.github.moreflow.ggwave.GgwaveProtocol

val encoder = GgwaveCodec.create()
val pcm: ShortArray = encoder.encode(
    "hello".encodeToByteArray(),
    protocol = GgwaveProtocol.AudibleFast,
    volume = 50,
)
encoder.close()

val decoder = GgwaveCodec.create()
val payload: ByteArray? = decoder.decode(capturedPcm) // null until a full message is decoded
decoder.close()
```

- Payloads can be up to 140 bytes (`GgwaveCodec.MAX_PAYLOAD_SIZE`).
- Each codec owns its own native instance and keeps decoder state between calls. Use separate instances for encoding and decoding, and don't call one instance from several threads at once.
- `GgwaveProtocol` offers audible, ultrasound and dual-tone variants, each in normal, fast and fastest speeds.
- `GgwaveCodecConfig` sets the input and output sample rates (default 48,000 Hz).

### Platform setup

- **Android**: the `ggwave-kmp` manifest declares `RECORD_AUDIO`, but your app must request it at runtime before `startCapture()`. The library shows no permission dialog. The sample does this in `androidApp`.
- **iOS**: add `NSMicrophoneUsageDescription` to your `Info.plist`. The library asks for microphone permission when capture starts. A denial arrives through `captureErrors`.
- **JVM**: the native library is bundled in the artifact for Windows x64, Linux x64/arm64 and macOS x64/arm64, and loaded automatically. No `-Djava.library.path` is needed. Other platforms fail with a `GgwaveException`.

## Sample app

1. Press the receive button to listen through the microphone.
<br/><br/> <img src="https://github.com/user-attachments/assets/aceee896-e837-4eab-abbb-e094c607d76f" width="370" /> <br/><br/>

2. On another device, type a message and press the send button.
<br/><br/> <img src="https://github.com/user-attachments/assets/146c5c0f-0c7e-4147-9e01-9ff72f5a8472" width="800"/> <br/><br/>

3. The message appears on the receiving device.
<br/><br/> <img src="https://github.com/user-attachments/assets/de14ad22-b6cf-449c-ad83-8c3956d96bdf" width="800"/> <br/><br/>

### Screenshots

<img src="https://github.com/user-attachments/assets/6ccb66c3-8697-4b94-98e7-38c09a19d177" width="1000" /> <br/><br/>

<video src="https://github.com/user-attachments/assets/f043ec36-d3e9-4e36-bf2e-2e1496815b59" width="1000" /> <br/><br/>

## Building from source

### Prerequisites

| Needed for | Requirement |
| --- | --- |
| Everything | JDK 21 (Gradle can provision it), CMake 3.20 or newer, Ninja |
| Android | Android SDK and NDK `29.0.14206865` (`GGWAVE_ANDROID_NDK_VERSION` in `gradle.properties`, or set `ANDROID_NDK_HOME`) |
| iOS | macOS with Xcode |
| Windows JVM | MinGW-w64 (`gcc`, `g++`) |
| macOS JVM | Xcode Command Line Tools (`clang`, `clang++`) |
| Linux JVM | `gcc`, `g++` and JDK headers |

Tested with Android Studio Panda 4.

### Running the sample

| Platform | How |
| --- | --- |
| Desktop (JVM) | `./gradlew :sampleApp:run` |
| Android | `./gradlew :androidApp:installDebug`, or run `androidApp` from Android Studio |
| iOS | Set `TEAM_ID` in `iosApp/Configuration/Config.xcconfig`, then open `iosApp/iosApp.xcodeproj` in Xcode and run |

### Tests and local publishing

```shell
./gradlew :ggwave-core:jvmTest :ggwave:jvmTest
./gradlew :ggwave-core:publishToMavenLocal :ggwave:publishToMavenLocal
```

Add `mavenLocal()` to your repositories to use a locally published build. Release publishing is described in [PUBLISHING.md](PUBLISHING.md).

## License

ggwave-kmp is released under the [MIT License](LICENSE). It includes a vendored copy of ggwave and other third-party code; see [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
