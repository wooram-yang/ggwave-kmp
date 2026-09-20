Drop prebuilt JNI libraries here so a single JVM publication can include every OS:

- windows-x86_64/libggwave.dll
- macos-aarch64/libggwave.dylib
- macos-x86_64/libggwave.dylib
- linux-x86_64/libggwave.so
- linux-aarch64/libggwave.so

The Gradle native build also copies the host library into generated resources. See PUBLISHING.md.
