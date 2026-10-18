import com.android.build.api.variant.KotlinMultiplatformAndroidComponentsExtension
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.Sync
import java.io.File
import java.util.Properties

abstract class AndroidJniLibsSync : Sync() {
    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty
}

plugins {
    id("ggwave.kmp-library")
    id("ggwave.publishing")
}

val hostOs = System.getProperty("os.name").orEmpty()
val hostArch = System.getProperty("os.arch").orEmpty().lowercase()
val isMacOs = hostOs.startsWith("Mac")
val isWindows = hostOs.startsWith("Windows")
val isLinux = hostOs.contains("Linux", ignoreCase = true)
val isArm64Host = hostArch == "aarch64" || hostArch == "arm64"
val isX64Host = hostArch == "x86_64" || hostArch == "amd64"

// Must match the folders NativeLoader looks up at runtime. Null on hosts without a JVM native build.
val jniResourceFolder: String? = when {
    isMacOs && isArm64Host -> "macos-aarch64"
    isMacOs && isX64Host -> "macos-x86_64"
    isWindows && isX64Host -> "windows-x86_64"
    isLinux && isArm64Host -> "linux-aarch64"
    isLinux && isX64Host -> "linux-x86_64"
    else -> null
}
val jvmNativeLibraryName = when {
    isWindows -> "libggwave.dll"
    isMacOs -> "libggwave.dylib"
    else -> "libggwave.so"
}

val nativeDir = layout.projectDirectory.dir("native")
val nativeGgwaveDir = nativeDir.dir("ggwave")
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

kotlin {
    android {
        namespace = "io.github.moreflow.ggwave.core"
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
        // Source file name to compile task name segment.
        val iosNativeSources = mapOf("ggwave" to "Cpp", "resampler" to "Resampler")

        iosTargets.forEach { (target, sdk, architecture) ->
            val targetName = target.name.replaceFirstChar { it.uppercase() }
            val targetBuildDir = layout.buildDirectory.dir("native/ios/${target.name}")
            val objectFiles = iosNativeSources.keys.map { source -> targetBuildDir.map { it.file("$source.o") } }
            val compileTasks = iosNativeSources.map { (source, taskSegment) ->
                tasks.register<Exec>("compileGgwave$taskSegment$targetName") {
                    inputs.file(nativeGgwaveDir.file("$source.cpp"))
                    outputs.file(targetBuildDir.map { it.file("$source.o") })
                    doFirst { targetBuildDir.get().asFile.mkdirs() }
                    commandLine(
                        "xcrun", "--sdk", sdk, "clang++",
                        "-arch", architecture,
                        "-std=c++11", "-stdlib=libc++", "-c",
                        nativeGgwaveDir.file("$source.cpp").asFile.path,
                        "-o", targetBuildDir.get().file("$source.o").asFile.path,
                    )
                }
            }
            val archive = tasks.register<Exec>("archiveGgwave$targetName") {
                dependsOn(compileTasks)
                inputs.files(objectFiles)
                outputs.file(targetBuildDir.map { it.file("libggwave.a") })
                commandLine(
                    listOf(
                        "xcrun", "--sdk", sdk, "libtool", "-static", "-o",
                        targetBuildDir.get().file("libggwave.a").asFile.path,
                    ) + objectFiles.map { it.get().asFile.path },
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

if (jniResourceFolder != null) {
    val cmakeBuildDir = layout.buildDirectory.dir("native/desktop/$jniResourceFolder")
    val configureGgwaveCmake by tasks.registering(Exec::class) {
        inputs.dir(nativeDir)
        outputs.file(cmakeBuildDir.map { it.file("CMakeCache.txt") })
        workingDir = nativeDir.asFile
        environment("PATH", nativeToolPath)
        val compilerArguments = when {
            isWindows -> listOf("-DCMAKE_C_COMPILER=gcc", "-DCMAKE_CXX_COMPILER=g++")
            isMacOs -> listOf("-DCMAKE_C_COMPILER=clang", "-DCMAKE_CXX_COMPILER=clang++")
            else -> emptyList()
        }
        commandLine(
            listOf(cmakeExecutable, "-G", "Ninja") + compilerArguments + listOf(
                "-DCMAKE_MAKE_PROGRAM=$ninjaExecutable",
                "-DCMAKE_BUILD_TYPE=Release",
                "-B", cmakeBuildDir.get().asFile.absolutePath,
                "-S", ".",
            ),
        )
    }
    val buildGgwaveCmake by tasks.registering(Exec::class) {
        dependsOn(configureGgwaveCmake)
        inputs.dir(nativeDir)
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
val androidMinSdk = libs.versions.android.minSdk.get()
val androidNdk = findAndroidNdk()
val androidNdkPath = androidNdk?.absolutePath
    ?: "ANDROID_NDK_${providers.gradleProperty("GGWAVE_ANDROID_NDK_VERSION").orNull.orEmpty()}_NOT_FOUND"
val androidNativeBuilds = androidAbis.associateWith { abi ->
    val taskSuffix = abi.split('-', '_').joinToString("") { segment ->
        segment.replaceFirstChar { it.uppercase() }
    }
    val buildDirectory = layout.buildDirectory.dir("native/android/$abi")
    val configure = tasks.register<Exec>("configureGgwaveAndroid$taskSuffix") {
        inputs.dir(nativeDir)
        outputs.file(buildDirectory.map { it.file("CMakeCache.txt") })
        environment("PATH", nativeToolPath)
        commandLine(
            cmakeExecutable,
            "-G", "Ninja",
            "-DCMAKE_MAKE_PROGRAM=$ninjaExecutable",
            "-DCMAKE_TOOLCHAIN_FILE=$androidNdkPath/build/cmake/android.toolchain.cmake",
            "-DANDROID_ABI=$abi",
            "-DANDROID_PLATFORM=android-$androidMinSdk",
            "-DCMAKE_BUILD_TYPE=Release",
            "-B", buildDirectory.get().asFile.absolutePath,
            "-S", nativeDir.asFile.absolutePath,
        )
    }
    tasks.register<Exec>("buildGgwaveAndroid$taskSuffix") {
        dependsOn(configure)
        inputs.dir(nativeDir)
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
