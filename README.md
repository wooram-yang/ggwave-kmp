# ggwave-kmp

![Supported Platforms](https://img.shields.io/badge/platform-Android-green.svg)
![Supported Platforms](https://img.shields.io/badge/platform-iOS-blue.svg)
![Supported Platforms](https://img.shields.io/badge/platform-JVM-red.svg)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)


![screen_video_2024-11-16-21 52 58_720p](https://github.com/user-attachments/assets/efd5053a-4076-494d-aeea-7cbe3e5510c6)

## Overview

ggwave-kmp is a Kotlin Multiplatform Project (KMP) designed to send and receive messages across Android, iOS, and desktop platforms like Windows and macOS through sound waves. This project is written in Kotlin and uses Compose Multiplatform, while the core library, [ggwave](https://github.com/ggerganov/ggwave), is written in C/C++. The fundamental technology is based on an FSK-based transmission protocol. For more details, you can read the specification [here](https://github.com/ggerganov/ggwave?tab=readme-ov-file#technical-details).

It consists of a common UI codebase using Compose Multiplatform and three platform-specific components for capturing and playing audio data.


## How to use
1. Press the receive button to get messages through the microphone
<br/><br/> <img src="https://github.com/user-attachments/assets/aceee896-e837-4eab-abbb-e094c607d76f" width="370" /> <br/><br/>


2. On another device, type a message and press the send button
<br/><br/> <img src="https://github.com/user-attachments/assets/146c5c0f-0c7e-4147-9e01-9ff72f5a8472" width="800"/> <br/><br/>


3. Done
<br/><br/> <img src="https://github.com/user-attachments/assets/de14ad22-b6cf-449c-ad83-8c3956d96bdf" width="800"/> <br/><br/>


## Prerequisites
### On Windows
- MinGW-w64 (gcc, g++)
- cmake version above 3.20.0 
  - ninja
 
### On macOS
- Xcode Command Line Tools (clang, clang++)
- cmake version above 3.20.0 
  - ninja


## Building
Ensure that you have the necessary configurations to run an Android or iOS app. This was tested in Android Studio Ladybug 2024.2.1 Patch 2.

### JVM (Windows, macOS)
```
./gradlew :composeApp:run
```


## Screenshot

<img src="https://github.com/user-attachments/assets/6ccb66c3-8697-4b94-98e7-38c09a19d177" width="1000" /> <br/><br/>

<video src="https://github.com/user-attachments/assets/f043ec36-d3e9-4e36-bf2e-2e1496815b59" width="1000" /> <br/><br/>

## License

```
MIT License

Copyright (c) 2024 Wooram Yang

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```
