import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.File
import java.util.Properties

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.mavenPublish)
}

val hostOs = System.getProperty("os.name").orEmpty()
val isMacOs = hostOs.startsWith("Mac")
val isWindows = hostOs.startsWith("Windows")
val isLinux = hostOs.contains("Linux", ignoreCase = true)
val hostArch = System.getProperty("os.arch").orEmpty()
val hostOsFamily = hostOs.split(' ').first()
val cmakeBuildDir = layout.projectDirectory.dir("cmake/$hostArch/$hostOsFamily")
val jniDir = layout.projectDirectory.dir("libs/jni")
val nativeGgwaveDir = layout.projectDirectory.dir("native/ggwave")
val generatedJniResources = layout.buildDirectory.dir("generated/jniResources")
val nativeToolPath = (
    listOf("/opt/homebrew/bin", "/usr/local/bin")
        .filter { isMacOs && file(it).isDirectory } +
        System.getenv("PATH").orEmpty().split(File.pathSeparator).filter(String::isNotBlank)
    ).distinct().joinToString(File.pathSeparator)
fun findNativeTool(name: String): String = if (isMacOs) {
    listOf("/opt/homebrew/bin/$name", "/usr/local/bin/$name")
        .firstOrNull { file(it).isFile }
        ?: name
} else {
    name
}
val cmakeExecutable = findNativeTool("cmake")
val ninjaExecutable = findNativeTool("ninja")
val jniResourceFolder = when {
    isWindows -> "windows-x86_64"
    isMacOs && (hostArch == "aarch64" || hostArch == "arm64") -> "macos-aarch64"
    isMacOs -> "macos-x86_64"
    hostArch.contains("aarch64") || hostArch.contains("arm") -> "linux-aarch64"
    else -> "linux-x86_64"
}

kotlin {
    jvmToolchain(21)
    jvm()

    android {
        namespace = "io.github.moreflow.ggwave.core"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        compilerOptions.jvmTarget.set(JvmTarget.JVM_21)
        optimization {
            consumerKeepRules.file("consumer-rules.pro")
            consumerKeepRules.publish = true
        }
    }

    if (isMacOs) {
        val iosTargets = listOf(
            Triple(iosArm64(), "iphoneos", "arm64"),
            Triple(iosSimulatorArm64(), "iphonesimulator", "arm64"),
            Triple(iosX64(), "iphonesimulator", "x86_64"),
        )

        iosTargets.forEach { (target, sdk, architecture) ->
            val targetName = target.name.replaceFirstChar { it.uppercase() }
            val targetBuildDir = layout.buildDirectory.dir("native/ios/${target.name}")
            val compileResampler = tasks.register<Exec>("compileGgwaveResampler$targetName") {
                inputs.file(nativeGgwaveDir.file("resampler.cpp"))
                outputs.file(targetBuildDir.map { it.file("resampler.o") })
                doFirst { targetBuildDir.get().asFile.mkdirs() }
                commandLine(
                    "xcrun", "--sdk", sdk, "clang++",
                    "-arch", architecture,
                    "-std=c++11", "-stdlib=libc++", "-c",
                    nativeGgwaveDir.file("resampler.cpp").asFile.path,
                    "-o", targetBuildDir.get().file("resampler.o").asFile.path,
                )
            }
            val compileGgwave = tasks.register<Exec>("compileGgwaveCpp$targetName") {
                inputs.file(nativeGgwaveDir.file("ggwave.cpp"))
                outputs.file(targetBuildDir.map { it.file("ggwave.o") })
                doFirst { targetBuildDir.get().asFile.mkdirs() }
                commandLine(
                    "xcrun", "--sdk", sdk, "clang++",
                    "-arch", architecture,
                    "-std=c++11", "-stdlib=libc++", "-c",
                    nativeGgwaveDir.file("ggwave.cpp").asFile.path,
                    "-o", targetBuildDir.get().file("ggwave.o").asFile.path,
                )
            }
            val archive = tasks.register<Exec>("archiveGgwave$targetName") {
                dependsOn(compileResampler, compileGgwave)
                inputs.files(
                    targetBuildDir.map { it.file("resampler.o") },
                    targetBuildDir.map { it.file("ggwave.o") },
                )
                outputs.file(targetBuildDir.map { it.file("libggwave.a") })
                commandLine(
                    "xcrun", "--sdk", sdk, "libtool", "-static", "-o",
                    targetBuildDir.get().file("libggwave.a").asFile.path,
                    targetBuildDir.get().file("ggwave.o").asFile.path,
                    targetBuildDir.get().file("resampler.o").asFile.path,
                )
            }

            val interop = target.compilations.getByName("main").cinterops.create("ggwave") {
                definitionFile.set(file("ggwave.def"))
                includeDirs("native/ggwave")
                extraOpts(
                    "-libraryPath", targetBuildDir.get().asFile.absolutePath,
                    "-staticLibrary", "libggwave.a",
                )
            }
            tasks.named(interop.interopProcessingTaskName).configure {
                dependsOn(archive)
            }
        }
    }

    sourceSets {
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
        named("jvmMain") {
            resources.srcDir(generatedJniResources)
            resources.srcDir(layout.projectDirectory.dir("prebuilt/jvm"))
        }
    }
}

fun findAndroidNdk(): java.io.File? {
    val environmentPath = System.getenv("ANDROID_NDK_HOME")
        ?: System.getenv("ANDROID_NDK_ROOT")
        ?: System.getenv("ANDROID_NDK")
    if (!environmentPath.isNullOrBlank()) {
        return file(environmentPath).takeIf { it.isDirectory }
    }
    val localProperties = rootProject.file("local.properties")
    if (!localProperties.exists()) return null
    val properties = Properties().apply { localProperties.inputStream().use(::load) }
    properties.getProperty("ndk.dir")?.let { path ->
        return file(path).takeIf { it.isDirectory }
    }
    val sdk = properties.getProperty("sdk.dir") ?: return null
    return file(sdk).resolve("ndk").listFiles()
        ?.filter { it.isDirectory }
        ?.maxByOrNull { it.name }
}

if (isWindows || isMacOs || isLinux) {
    val configureGgwaveCmake by tasks.registering(Exec::class) {
        workingDir = file("src/desktopMain")
        environment("PATH", nativeToolPath)
        val arguments = mutableListOf(
            cmakeExecutable, "-G", "Ninja",
            "-DCMAKE_MAKE_PROGRAM=$ninjaExecutable",
            "-DCMAKE_BUILD_TYPE=Release",
            "-B", cmakeBuildDir.asFile.absolutePath,
            "-S", ".",
        )
        if (isWindows) {
            arguments.addAll(
                3,
                listOf(
                    "-DCMAKE_C_COMPILER=gcc",
                    "-DCMAKE_CXX_COMPILER=g++",
                    "-DCMAKE_C_COMPILER_TARGET=x86_64-window-gnu",
                    "-DCMAKE_CXX_COMPILER_TARGET=x86_64-window-gnu",
                ),
            )
        } else if (isMacOs) {
            arguments.addAll(3, listOf("-DCMAKE_C_COMPILER=clang", "-DCMAKE_CXX_COMPILER=clang++"))
        }
        commandLine(arguments)
    }
    val buildGgwaveCmake by tasks.registering(Exec::class) {
        dependsOn(configureGgwaveCmake)
        workingDir = cmakeBuildDir.asFile
        environment("PATH", nativeToolPath)
        commandLine(cmakeExecutable, "--build", ".")
    }
    val copyGgwaveJvmLibrary by tasks.registering(Copy::class) {
        dependsOn(buildGgwaveCmake)
        from(cmakeBuildDir)
        include("libggwave.dll", "libggwave.dylib", "libggwave.so")
        into(jniDir)
    }
    val copyJniIntoResources by tasks.registering(Copy::class) {
        dependsOn(copyGgwaveJvmLibrary)
        from(jniDir)
        include("*.dll", "*.dylib", "*.so")
        into(generatedJniResources.map { it.dir("native/$jniResourceFolder") })
    }
    tasks.register("buildGGWaveJvmLibrary") {
        group = "build"
        dependsOn(copyJniIntoResources)
    }
    tasks.configureEach {
        if (
            name.contains("Jvm", ignoreCase = true) &&
            !name.startsWith("buildGGWave") &&
            !name.startsWith("copyGgwave")
        ) {
            dependsOn(copyJniIntoResources)
        }
    }
}

val androidAbis = listOf("armeabi-v7a", "arm64-v8a", "x86_64")
val androidJniLibsDir = layout.projectDirectory.dir("src/androidMain/jniLibs")

tasks.register("buildGGWaveAndroidLibrary") {
    group = "build"
    inputs.dir(layout.projectDirectory.dir("native"))
    inputs.file(layout.projectDirectory.file("src/androidMain/CMakeLists.txt"))
    outputs.dir(androidJniLibsDir)
    doLast {
        val ndk = findAndroidNdk()
            ?: throw GradleException("Android NDK is required to build ggwave-core Android artifacts")
        fun run(vararg arguments: String) {
            val process = ProcessBuilder(*arguments)
            process.environment()["PATH"] = nativeToolPath
            val result = process.inheritIO().start().waitFor()
            if (result != 0) throw GradleException("Command failed ($result): ${arguments.joinToString(" ")}")
        }
        androidAbis.forEach { abi ->
            val buildDirectory = layout.projectDirectory.dir("cmake/android/$abi").asFile
            buildDirectory.mkdirs()
            run(
                cmakeExecutable,
                "-G", "Ninja",
                "-DCMAKE_MAKE_PROGRAM=$ninjaExecutable",
                "-DCMAKE_TOOLCHAIN_FILE=${ndk.absolutePath}/build/cmake/android.toolchain.cmake",
                "-DANDROID_ABI=$abi",
                "-DANDROID_PLATFORM=android-24",
                "-DCMAKE_BUILD_TYPE=Release",
                "-B", buildDirectory.absolutePath,
                "-S", file("src/androidMain").absolutePath,
            )
            run(cmakeExecutable, "--build", buildDirectory.absolutePath)
            val library = listOf(
                file("$buildDirectory/libggwave.so"),
                file("$buildDirectory/lib/libggwave.so"),
            ).firstOrNull { it.exists() }
                ?: throw GradleException("libggwave.so was not produced for ABI $abi")
            val destination = androidJniLibsDir.dir(abi).asFile
            destination.mkdirs()
            library.copyTo(destination.resolve(library.name), overwrite = true)
        }
    }
}

tasks.configureEach {
    if (
        name.contains("Android", ignoreCase = true) &&
        name != "buildGGWaveAndroidLibrary" &&
        (
            name.contains("Jni", ignoreCase = true) ||
                name.contains("Aar", ignoreCase = true) ||
                name.startsWith("merge") ||
                name.startsWith("package") ||
                name.startsWith("publishAndroid")
            )
    ) {
        dependsOn("buildGGWaveAndroidLibrary")
    }
}

mavenPublishing {
    coordinates(
        providers.gradleProperty("LIBRARY_GROUP").get(),
        "ggwave-core",
        providers.gradleProperty("LIBRARY_VERSION").get(),
    )
    publishToMavenCentral()
    if (providers.gradleProperty("signingInMemoryKey").orNull != null) {
        signAllPublications()
    }
    pom {
        name.set("ggwave-core")
        description.set("Kotlin Multiplatform bindings for the ggwave audio data codec.")
        inceptionYear.set("2024")
        url.set("https://github.com/wooram-yang/ggwave-kmp")
        licenses {
            license {
                name.set("MIT License")
                url.set("https://opensource.org/licenses/MIT")
                distribution.set("repo")
            }
        }
        developers {
            developer {
                id.set("wooram-yang")
                name.set("Wooram Yang")
                url.set("https://github.com/wooram-yang")
            }
        }
        scm {
            url.set("https://github.com/wooram-yang/ggwave-kmp")
            connection.set("scm:git:git://github.com/wooram-yang/ggwave-kmp.git")
            developerConnection.set("scm:git:ssh://git@github.com/wooram-yang/ggwave-kmp.git")
        }
    }
}

publishing.repositories.maven {
    name = "GitHubPackages"
    url = uri("https://maven.pkg.github.com/wooram-yang/ggwave-kmp")
    credentials {
        username = providers.environmentVariable("GITHUB_ACTOR")
            .orElse(providers.gradleProperty("gpr.user"))
            .orElse("wooram-yang")
            .get()
        password = providers.environmentVariable("GITHUB_TOKEN")
            .orElse(providers.gradleProperty("gpr.key"))
            .orElse("")
            .get()
    }
}
