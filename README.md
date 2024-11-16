# ggwave-kmp

![Supported Platforms](https://img.shields.io/badge/platform-Android-green.svg)
![Supported Platforms](https://img.shields.io/badge/platform-iOS-blue.svg)
![Supported Platforms](https://img.shields.io/badge/platform-JVM-red.svg)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)

## Overview

ggwave-kmp is a Kotlin Multiplatform Project (KMP) designed to send and receive messages across Android, iOS, and desktop platforms like Windows and macOS through sound waves. This project is written in Kotlin and uses Compose Multiplatform, while the core library, ggwave, is written in C/C++. The fundamental technology is based on an FSK-based transmission protocol. For more details, you can read the specification [here](https://github.com/ggerganov/ggwave).


## How to use
1. Press the receive button to get messages through the microphone
<br/><br/> <img src="https://github.com/user-attachments/assets/aceee896-e837-4eab-abbb-e094c607d76f" width="370" /> <br/><br/>


2. On another device, type a message and press the send button
<br/><br/> <img src="https://github.com/user-attachments/assets/146c5c0f-0c7e-4147-9e01-9ff72f5a8472" width="800"/> <br/><br/>


3. Done
<br/><br/> <img src="https://github.com/user-attachments/assets/de14ad22-b6cf-449c-ad83-8c3956d96bdf" width="800"/> <br/><br/>


## Prerequisites
- cmake version above 3.20.0 
  - ninja
- Xcode Command Line Tools


## Building
Ensure that you have the necessary configurations to run an Android or iOS app. This was tested in Android Studio Ladybug 2024.2.1 Patch 2.

### JVM (Windows, macOS)
```
./gradlew :composeApp:run
```


## Screenshot

<img src="https://github.com/user-attachments/assets/9a9cb95f-1669-4b49-b260-56f283d93370" width=1000 />
<br/><br/>

https://github.com/user-attachments/assets/0a26af7c-f587-4b80-9923-1c4029f0f1e6


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
