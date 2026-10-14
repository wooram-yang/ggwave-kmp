# Publishing ggwave-kmp

Published modules:

```text
io.github.wooram-yang:ggwave-core:0.2.0
io.github.wooram-yang:ggwave-kmp:0.2.0
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

Before the first release:

1. Sign in to the [Central Portal](https://central.sonatype.com/) with GitHub.
2. Verify that the `io.github.wooram-yang` namespace is available.
3. Generate a publishing token from the
   [user token page](https://central.sonatype.com/usertoken).
4. Create a GPG key, publish its public key to a supported key server, and
   export the ASCII-armored private key for CI signing.

Configure the Central Portal token and signing properties outside the
repository:

```properties
mavenCentralUsername=<token-username>
mavenCentralPassword=<token-password>
signingInMemoryKey=<armored-private-key>
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
- `SIGNING_IN_MEMORY_KEY_PASSWORD`

The Maven Central step is skipped when `MAVEN_CENTRAL_USERNAME` is absent.

For a Maven Central retry after GitHub Packages has already succeeded, run the
`Publish` workflow manually with `central_only` enabled. This avoids attempting
to overwrite the immutable GitHub Packages version.

`publishToMavenCentral` uploads and validates a deployment but deliberately
does not release it automatically. After the workflow succeeds, open
[Central Portal deployments](https://central.sonatype.com/publishing/deployments),
review both modules, and click **Publish**. Published versions are immutable,
so any correction must use a new version.
