import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.CInteropProcess
import java.util.Properties

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
}

val hostOs = System.getProperty("os.name").orEmpty()
val isMacOs = hostOs.startsWith("Mac")
val isWindows = hostOs.startsWith("Windows")
val hostArch = System.getProperty("os.arch").orEmpty()
val hostOsFamily = hostOs.split(' ').first()
val cmakeBuildDir = layout.projectDirectory.dir("cmake/$hostArch/$hostOsFamily")
val jniDir = layout.projectDirectory.dir("libs/jni")
val staticLibDir = layout.projectDirectory.dir("libs/static")
val nativeGgwaveDir = layout.projectDirectory.dir("native/ggwave")
val generatedJniResources = layout.buildDirectory.dir("generated/jniResources")
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
        namespace = "io.github.wooramyang.ggwave"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
        }

        androidResources {
            enable = true
        }
    }

    if (isMacOs) {
        listOf(
            iosArm64(),
            iosSimulatorArm64(),
        ).forEach { iosTarget ->
            iosTarget.compilations.getByName("main").cinterops.create("nativeLibrary") {
                definitionFile.set(file("ggwave.def"))
                includeDirs("native/ggwave")
            }
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
        androidMain.dependencies {
            implementation(libs.kotlinx.coroutines.android)
        }
        named("jvmMain") {
            resources.srcDir(generatedJniResources)
        }
    }
}

fun Exec.withoutXcodeSdkEnvironment() {
    val cleaned = HashMap(System.getenv()).apply {
        listOf(
            "SDKROOT",
            "IPHONEOS_DEPLOYMENT_TARGET",
            "EFFECTIVE_PLATFORM_NAME",
            "PLATFORM_NAME",
            "CONFIGURATION_BUILD_DIR",
            "BUILT_PRODUCTS_DIR",
            "ARCHS",
            "VALID_ARCHS",
            "NATIVE_ARCH",
            "NATIVE_ARCH_ACTUAL",
            "LLVM_TARGET_TRIPLE_OS_VERSION",
            "DEPLOYMENT_TARGET_CLANG_ENV_NAME",
            "DEPLOYMENT_TARGET_CLANG_FLAG_NAME",
            "DEPLOYMENT_TARGET_CLANG_FLAG_PREFIX",
            "APPLE_SDK_PLATFORM",
            "APPLE_SDK_VERSION_OVERRIDE",
        ).forEach(::remove)
    }
    environment = cleaned
}

fun isXcodeIosBuild(): Boolean {
    val platform = System.getenv("PLATFORM_NAME").orEmpty()
    val sdkRoot = System.getenv("SDKROOT").orEmpty()
    return platform.contains("iphone", ignoreCase = true) ||
        sdkRoot.contains("iPhone", ignoreCase = true)
}

fun Task.isGgwaveNativeProducer(): Boolean =
    name.startsWith("buildGGWave") ||
        name.startsWith("configureGgwave") ||
        name.startsWith("buildGgwave") ||
        name.startsWith("copyGgwave") ||
        name.startsWith("compileGgwave") ||
        name.startsWith("archiveGgwave") ||
        name.startsWith("cleanGgwave")

fun Task.needsJvmGgwave(): Boolean {
    if (isGgwaveNativeProducer()) return false
    return name.contains("Jvm", ignoreCase = true) ||
        name.contains("jvm", ignoreCase = false)
}

fun Task.needsIosGgwave(): Boolean {
    if (isGgwaveNativeProducer()) return false
    return this is CInteropProcess ||
        name.contains("Ios", ignoreCase = false) ||
        name.contains("iosArm64", ignoreCase = true) ||
        name.contains("iosSimulator", ignoreCase = true)
}

fun resolvedIosSdk(): String {
    val platform = System.getenv("PLATFORM_NAME").orEmpty()
    val sdkRoot = System.getenv("SDKROOT").orEmpty()
    return when {
        platform == "iphoneos" || (sdkRoot.contains("iPhoneOS") && !sdkRoot.contains("Simulator")) ->
            "iphoneos"
        else -> "iphonesimulator"
    }
}

fun findAndroidNdk(): java.io.File? {
    val env = System.getenv("ANDROID_NDK_HOME") ?: System.getenv("ANDROID_NDK_ROOT") ?: System.getenv("ANDROID_NDK")
    if (!env.isNullOrBlank()) {
        return file(env).takeIf { it.isDirectory }
    }
    val local = rootProject.file("local.properties")
    if (!local.exists()) return null
    val props = Properties().apply { local.inputStream().use { load(it) } }
    props.getProperty("ndk.dir")?.let { path ->
        return file(path).takeIf { it.isDirectory }
    }
    val sdk = props.getProperty("sdk.dir") ?: return null
    val ndkRoot = file(sdk).resolve("ndk")
    return ndkRoot.listFiles()?.filter { it.isDirectory }?.maxByOrNull { it.name }
}

if (isWindows) {
    val configureGgwaveCmake by tasks.registering(Exec::class) {
        workingDir = file("src/desktopMain")
        commandLine(
            "cmake", "-G", "Ninja",
            "-DCMAKE_BUILD_TYPE=Release",
            "-DCMAKE_C_COMPILER=gcc",
            "-DCMAKE_CXX_COMPILER=g++",
            "-DCMAKE_C_COMPILER_TARGET=x86_64-window-gnu",
            "-DCMAKE_CXX_COMPILER_TARGET=x86_64-window-gnu",
            "-B", cmakeBuildDir.asFile.absolutePath,
            "-S", ".",
        )
    }

    val buildGgwaveCmake by tasks.registering(Exec::class) {
        dependsOn(configureGgwaveCmake)
        workingDir = cmakeBuildDir.asFile
        commandLine("cmake", "--build", ".")
    }

    val copyGgwaveJvmLibrary by tasks.registering(Copy::class) {
        dependsOn(buildGgwaveCmake)
        from(cmakeBuildDir.file("libggwave.dll"))
        into(jniDir)
    }

    tasks.register("buildGGWaveJvmLibrary") {
        group = "build"
        description = "Build ggwave JNI library for desktop/JVM"
        dependsOn(copyGgwaveJvmLibrary)
    }
} else if (isMacOs) {
    val cmake = "/opt/homebrew/bin/cmake"
    val iosSdk = resolvedIosSdk()

    val configureGgwaveCmake by tasks.registering(Exec::class) {
        workingDir = file("src/desktopMain")
        withoutXcodeSdkEnvironment()
        commandLine(
            cmake, "-G", "Ninja",
            "-DCMAKE_BUILD_TYPE=Release",
            "-DCMAKE_C_COMPILER=clang",
            "-DCMAKE_CXX_COMPILER=clang++",
            "-DCMAKE_OSX_SYSROOT=macosx",
            "-DCMAKE_APPLE_SILICON_PROCESSOR=arm64",
            "-B", cmakeBuildDir.asFile.absolutePath,
            "-S", ".",
        )
    }

    val buildGgwaveCmake by tasks.registering(Exec::class) {
        dependsOn(configureGgwaveCmake)
        workingDir = cmakeBuildDir.asFile
        withoutXcodeSdkEnvironment()
        commandLine(cmake, "--build", ".")
    }

    val copyGgwaveJvmLibrary by tasks.registering(Copy::class) {
        dependsOn(buildGgwaveCmake)
        from(cmakeBuildDir.file("libggwave.dylib"))
        into(jniDir)
    }

    tasks.register("buildGGWaveJvmLibrary") {
        group = "build"
        description = "Build ggwave JNI dylib for desktop/JVM (macOS)"
        dependsOn(copyGgwaveJvmLibrary)
    }

    val compileGgwaveResampler by tasks.registering(Exec::class) {
        inputs.file(nativeGgwaveDir.file("resampler.cpp"))
        outputs.file(nativeGgwaveDir.file("resampler.o"))
        commandLine(
            "xcrun", "--sdk", iosSdk, "clang++",
            "-std=c++11", "-stdlib=libc++", "-c",
            nativeGgwaveDir.file("resampler.cpp").asFile.path,
            "-o", nativeGgwaveDir.file("resampler.o").asFile.path,
        )
    }

    val compileGgwaveCpp by tasks.registering(Exec::class) {
        dependsOn(compileGgwaveResampler)
        inputs.file(nativeGgwaveDir.file("ggwave.cpp"))
        outputs.file(nativeGgwaveDir.file("ggwave.o"))
        commandLine(
            "xcrun", "--sdk", iosSdk, "clang++",
            "-std=c++11", "-stdlib=libc++", "-c",
            nativeGgwaveDir.file("ggwave.cpp").asFile.path,
            "-o", nativeGgwaveDir.file("ggwave.o").asFile.path,
        )
    }

    val archiveGgwaveIos by tasks.registering(Exec::class) {
        dependsOn(compileGgwaveCpp)
        inputs.files(
            nativeGgwaveDir.file("ggwave.o"),
            nativeGgwaveDir.file("resampler.o"),
        )
        outputs.file(staticLibDir.file("libggwave.a"))
        doFirst { staticLibDir.asFile.mkdirs() }
        commandLine(
            "/usr/bin/libtool", "-static", "-o",
            staticLibDir.file("libggwave.a").asFile.path,
            nativeGgwaveDir.file("ggwave.o").asFile.path,
            nativeGgwaveDir.file("resampler.o").asFile.path,
        )
    }

    val cleanGgwaveIosObjects by tasks.registering(Delete::class) {
        dependsOn(archiveGgwaveIos)
        delete(
            nativeGgwaveDir.file("ggwave.o"),
            nativeGgwaveDir.file("resampler.o"),
        )
    }

    tasks.register("buildGGWaveIosLibrary") {
        group = "build"
        description = "Build ggwave static library for iOS cinterop (sdk=$iosSdk)"
        dependsOn(cleanGgwaveIosObjects)
    }
}

if (isWindows || isMacOs) {
    val copyJniIntoResources by tasks.registering(Copy::class) {
        dependsOn("buildGGWaveJvmLibrary")
        from(jniDir)
        include("*.dll", "*.dylib", "*.so")
        into(generatedJniResources.map { it.dir("native/$jniResourceFolder") })
    }

    tasks.register("buildGGWaveLibrary") {
        group = "build"
        description = "Build ggwave native libs for the current context (JVM and/or iOS)"
        when {
            isXcodeIosBuild() && isMacOs -> dependsOn("buildGGWaveIosLibrary")
            else -> {
                dependsOn("buildGGWaveJvmLibrary")
                if (isMacOs && project.findProperty("ggwave.ios") == "true") {
                    dependsOn("buildGGWaveIosLibrary")
                }
            }
        }
    }

    tasks.configureEach {
        if (needsJvmGgwave()) {
            dependsOn("buildGGWaveJvmLibrary", copyJniIntoResources)
        }
        if (isMacOs && needsIosGgwave()) {
            dependsOn("buildGGWaveIosLibrary")
        }
    }
}

val androidAbis = listOf("armeabi-v7a", "arm64-v8a", "x86_64")
val androidJniLibsDir = layout.projectDirectory.dir("src/androidMain/jniLibs")

tasks.register("buildGGWaveAndroidLibrary") {
    group = "build"
    description = "Build ggwave shared libraries for Android ABIs"
    val ndk = findAndroidNdk()
    onlyIf { ndk != null }
    inputs.dir(layout.projectDirectory.dir("native"))
    inputs.file(layout.projectDirectory.file("src/androidMain/CMakeLists.txt"))
    outputs.dir(androidJniLibsDir)
    doLast {
        val ndkDir = findAndroidNdk() ?: return@doLast
        val cmake = when {
            file("/opt/homebrew/bin/cmake").exists() -> "/opt/homebrew/bin/cmake"
            else -> "cmake"
        }
        fun run(vararg args: String) {
            val result = ProcessBuilder(*args)
                .inheritIO()
                .start()
                .waitFor()
            if (result != 0) {
                throw GradleException("Command failed (${result}): ${args.joinToString(" ")}")
            }
        }
        androidAbis.forEach { abi ->
            val buildDir = layout.projectDirectory.dir("cmake/android/$abi").asFile
            buildDir.mkdirs()
            run(
                cmake,
                "-G", "Ninja",
                "-DCMAKE_TOOLCHAIN_FILE=${ndkDir.absolutePath}/build/cmake/android.toolchain.cmake",
                "-DANDROID_ABI=$abi",
                "-DANDROID_PLATFORM=android-24",
                "-DCMAKE_BUILD_TYPE=Release",
                "-B", buildDir.absolutePath,
                "-S", file("src/androidMain").absolutePath,
            )
            run(cmake, "--build", buildDir.absolutePath)
            val soFile = listOf(
                file("$buildDir/libggwave.so"),
                file("$buildDir/lib/libggwave.so"),
            ).firstOrNull { it.exists() }
                ?: throw GradleException("libggwave.so was not produced for ABI $abi")
            val dest = androidJniLibsDir.dir(abi).asFile
            dest.mkdirs()
            soFile.copyTo(dest.resolve(soFile.name), overwrite = true)
        }
    }
}

tasks.configureEach {
    val n = name
    if (
        n.contains("Android", ignoreCase = true) &&
        !isGgwaveNativeProducer() &&
        (
            n.contains("Jni", ignoreCase = true) ||
                n.contains("jniLibs", ignoreCase = true) ||
                n.contains("Aar", ignoreCase = true) ||
                n.contains("bundleLibCompile", ignoreCase = false) ||
                n.startsWith("merge") ||
                n.startsWith("package")
            )
    ) {
        dependsOn("buildGGWaveAndroidLibrary")
    }
}
