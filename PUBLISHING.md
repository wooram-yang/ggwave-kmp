# Publishing ggwave-kmp

Published modules:

```text
io.github.wooram-yang:ggwave-core:0.1.0
io.github.wooram-yang:ggwave-kmp:0.1.0
```

`ggwave-core` contains the codec API and native bindings. `ggwave-kmp` contains
the microphone/speaker session API and depends on `ggwave-core`.

## Local verification

Build and test the host JVM artifacts:

```shell
./gradlew :ggwave-core:jvmTest :ggwave:jvmTest
```

With an Android NDK installed, assemble the Android publications:

```shell
./gradlew :ggwave-core:assemble :ggwave:assemble
```

On macOS, the build additionally produces separate `libggwave.a` archives for
`iosArm64`, `iosSimulatorArm64`, and `iosX64`.

Publish both modules to Maven Local:

```shell
./gradlew :ggwave-core:publishToMavenLocal :ggwave:publishToMavenLocal
```

## Complete JVM artifact

A complete `ggwave-core` JVM JAR contains these resources:

```text
native/windows-x86_64/libggwave.dll
native/linux-x86_64/libggwave.so
native/linux-aarch64/libggwave.so
native/macos-x86_64/libggwave.dylib
native/macos-aarch64/libggwave.dylib
```

A local single-host build contains only the current host library. The publish
workflow builds all five variants in separate jobs and downloads them under
`ggwave-core/prebuilt/jvm` before publication.

## GitHub Packages

Set `GITHUB_ACTOR` and `GITHUB_TOKEN`, then publish both modules:

```shell
./gradlew \
  :ggwave-core:publishAllPublicationsToGitHubPackagesRepository \
  :ggwave:publishAllPublicationsToGitHubPackagesRepository
```

Consumers must add the authenticated GitHub Packages Maven repository.

## Maven Central

Configure the Central Portal and signing properties outside the repository:

```properties
mavenCentralUsername=<token-username>
mavenCentralPassword=<token-password>
signingInMemoryKey=<armored-private-key>
signingInMemoryKeyId=<key-id>
signingInMemoryKeyPassword=<key-password>
```

Then publish both modules:

```shell
./gradlew \
  :ggwave-core:publishToMavenCentral \
  :ggwave:publishToMavenCentral
```

The GitHub Actions workflow expects the equivalent repository secrets:

- `MAVEN_CENTRAL_USERNAME`
- `MAVEN_CENTRAL_PASSWORD`
- `SIGNING_IN_MEMORY_KEY`
- `SIGNING_IN_MEMORY_KEY_ID`
- `SIGNING_IN_MEMORY_KEY_PASSWORD`

The Maven Central step is skipped when `MAVEN_CENTRAL_USERNAME` is absent.
