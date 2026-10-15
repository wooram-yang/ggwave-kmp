import com.android.build.api.variant.KotlinMultiplatformAndroidComponentsExtension
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.Sync
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.File
import java.util.Properties

abstract class AndroidJniLibsSync : Sync() {
    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty
}

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    id("ggwave.publishing")
}

val hostOs = System.getProperty("os.name").orEmpty()
val isMacOs = hostOs.startsWith("Mac")
val isWindows = hostOs.startsWith("Windows")
val isLinux = hostOs.contains("Linux", ignoreCase = true)
val hostArch = System.getProperty("os.arch").orEmpty()
val hostOsFamily = hostOs.split(' ').first()
val cmakeBuildDir = layout.buildDirectory.dir("native/desktop/$hostArch/$hostOsFamily")
val nativeGgwaveDir = layout.projectDirectory.dir("native/ggwave")
val generatedJniResources = layout.buildDirectory.dir("generated/jniResources")
val generatedAndroidJniLibs = layout.buildDirectory.dir("generated/androidJniLibs")
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
fun compareVersionNames(left: String, right: String): Int {
    val leftParts = left.split('.').map { it.toIntOrNull() ?: 0 }
    val rightParts = right.split('.').map { it.toIntOrNull() ?: 0 }
    repeat(maxOf(leftParts.size, rightParts.size)) { index ->
        val comparison = (leftParts.getOrNull(index) ?: 0).compareTo(rightParts.getOrNull(index) ?: 0)
        if (comparison != 0) return comparison
    }
    return 0
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
val jvmNativeLibraryName = when {
    isWindows -> "libggwave.dll"
    isMacOs -> "libggwave.dylib"
    else -> "libggwave.so"
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
    val ndkRoot = file(sdk).resolve("ndk")
    val preferredVersion = providers.gradleProperty("GGWAVE_ANDROID_NDK_VERSION").orNull
    if (preferredVersion != null) {
        return ndkRoot.resolve(preferredVersion).takeIf { it.isDirectory }
    }
    return ndkRoot.listFiles()
        ?.filter(File::isDirectory)
        ?.maxWithOrNull { left, right -> compareVersionNames(left.name, right.name) }
}

if (isWindows || isMacOs || isLinux) {
    val configureGgwaveCmake by tasks.registering(Exec::class) {
        inputs.dir(nativeGgwaveDir)
        inputs.file(layout.projectDirectory.file("src/desktopMain/CMakeLists.txt"))
        outputs.file(cmakeBuildDir.map { it.file("CMakeCache.txt") })
        workingDir = file("src/desktopMain")
        environment("PATH", nativeToolPath)
        val arguments = mutableListOf(
            cmakeExecutable, "-G", "Ninja",
            "-DCMAKE_MAKE_PROGRAM=$ninjaExecutable",
            "-DCMAKE_BUILD_TYPE=Release",
            "-B", cmakeBuildDir.get().asFile.absolutePath,
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
        inputs.dir(nativeGgwaveDir)
        inputs.file(cmakeBuildDir.map { it.file("CMakeCache.txt") })
        outputs.file(cmakeBuildDir.map { it.file(jvmNativeLibraryName) })
        workingDir = cmakeBuildDir.get().asFile
        environment("PATH", nativeToolPath)
        commandLine(cmakeExecutable, "--build", ".")
    }
    val copyJniIntoResources by tasks.registering(Copy::class) {
        dependsOn(buildGgwaveCmake)
        from(cmakeBuildDir)
        include("*.dll", "*.dylib", "*.so")
        into(generatedJniResources.map { it.dir("native/$jniResourceFolder") })
    }
    tasks.register("buildGGWaveJvmLibrary") {
        group = "build"
        dependsOn(copyJniIntoResources)
    }
    tasks.named("jvmProcessResources") {
        dependsOn(copyJniIntoResources)
    }
}

val androidAbis = listOf("armeabi-v7a", "arm64-v8a", "x86_64")
val androidNdk = findAndroidNdk()
val androidNdkPath = androidNdk?.absolutePath
    ?: "ANDROID_NDK_${providers.gradleProperty("GGWAVE_ANDROID_NDK_VERSION").orNull.orEmpty()}_NOT_FOUND"
val androidNativeBuilds = androidAbis.associateWith { abi ->
    val taskSuffix = abi.split('-', '_').joinToString("") { segment ->
        segment.replaceFirstChar { it.uppercase() }
    }
    val buildDirectory = layout.buildDirectory.dir("native/android/$abi")
    val configure = tasks.register<Exec>("configureGgwaveAndroid$taskSuffix") {
        inputs.dir(layout.projectDirectory.dir("native"))
        inputs.file(layout.projectDirectory.file("src/androidMain/CMakeLists.txt"))
        outputs.file(buildDirectory.map { it.file("CMakeCache.txt") })
        environment("PATH", nativeToolPath)
        commandLine(
            cmakeExecutable,
            "-G", "Ninja",
            "-DCMAKE_MAKE_PROGRAM=$ninjaExecutable",
            "-DCMAKE_TOOLCHAIN_FILE=$androidNdkPath/build/cmake/android.toolchain.cmake",
            "-DANDROID_ABI=$abi",
            "-DANDROID_PLATFORM=android-24",
            "-DCMAKE_BUILD_TYPE=Release",
            "-B", buildDirectory.get().asFile.absolutePath,
            "-S", file("src/androidMain").absolutePath,
        )
    }
    tasks.register<Exec>("buildGgwaveAndroid$taskSuffix") {
        dependsOn(configure)
        inputs.dir(layout.projectDirectory.dir("native"))
        inputs.file(buildDirectory.map { it.file("CMakeCache.txt") })
        outputs.file(buildDirectory.map { it.file("libggwave.so") })
        workingDir(buildDirectory)
        environment("PATH", nativeToolPath)
        commandLine(cmakeExecutable, "--build", ".")
    } to buildDirectory
}

val syncGgwaveAndroidJniLibs = tasks.register<AndroidJniLibsSync>("syncGgwaveAndroidJniLibs") {
    outputDirectory.set(generatedAndroidJniLibs)
    into(outputDirectory)
    androidNativeBuilds.forEach { (abi, build) ->
        val (buildTask, buildDirectory) = build
        dependsOn(buildTask)
        from(buildDirectory.map { it.file("libggwave.so") }) {
            into(abi)
        }
    }
}

tasks.register("buildGGWaveAndroidLibrary") {
    group = "build"
    dependsOn(syncGgwaveAndroidJniLibs)
}

extensions.configure<KotlinMultiplatformAndroidComponentsExtension>("androidComponents") {
    onVariants(selector().all()) { variant ->
        variant.sources.jniLibs?.addGeneratedSourceDirectory(
            syncGgwaveAndroidJniLibs,
            AndroidJniLibsSync::outputDirectory,
        )
    }
}
