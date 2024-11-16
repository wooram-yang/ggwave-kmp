import com.android.build.gradle.internal.tasks.factory.dependsOn
import org.gradle.internal.os.OperatingSystem
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.jetbrainsCompose)
    alias(libs.plugins.compose.compiler)
}

kotlin {
    @OptIn(ExperimentalKotlinGradlePluginApi::class)
    compilerOptions {
        apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_0)
    }

    jvm("desktop")
    androidTarget()

    if(System.getProperty("os.name").equals("Mac OS X")) {
        listOf(
            iosX64(),
            iosArm64(),
            iosSimulatorArm64()
        ).forEach { iosTarget ->
            iosTarget.compilations {
                val main by getting {
                    cinterops {
                        val nativeLibrary by creating {
                            defFile(project.file("ggwave.def"))
                            compilerOpts("-Inative/ggwave/")

                        }
                    }
                }
            }

            iosTarget.binaries.framework {
                baseName = "ComposeApp"
                isStatic = true
            }
        }
    }

    sourceSets {
        val desktopMain by getting

        androidMain.dependencies {
            implementation(libs.compose.ui.tooling.preview)
            implementation(libs.androidx.activity.compose)
            implementation(libs.kotlinx.coroutines.android)
        }
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)

            implementation(compose.material3)
            implementation(compose.ui)
            implementation(compose.components.resources)

            implementation(libs.lifecycle.viewmodel.compose)
            implementation(libs.constraintlayout.compose.multiplatform)
            implementation(libs.material.kolor)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        desktopMain.dependencies {
            implementation(compose.desktop.currentOs)
            implementation(libs.kotlinx.coroutines.swing)
        }
    }
}

android {
    namespace = "com.example.ggwavekmp"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    sourceSets["main"].manifest.srcFile("src/androidMain/AndroidManifest.xml")
    sourceSets["main"].res.srcDirs("src/androidMain/res")
    sourceSets["main"].resources.srcDirs("src/commonMain/resources")

    defaultConfig {
        applicationId = "com.example.ggwavekmp"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            // target platforms
            abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86_64")
        }
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    dependencies {
        debugImplementation(libs.compose.ui.tooling)
    }
    externalNativeBuild {
        cmake {
            path = file("src/androidMain/CMakeLists.txt")
        }
    }
}

compose.desktop {
    application {
        mainClass = "MainKt"
        jvmArgs("-Djava.library.path=libs/jni")

        nativeDistributions {
            outputBaseDir.set(project.buildDir.resolve("testDistribution"))
            copy {
                from("$projectDir/libs/jni/libggwave.dll")
                into("${project.buildDir}/testDistribution/main/app/ggwaveKMP/libs/jni")
            }

            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "ggwaveKMP"
            packageVersion = "1.0.0"
            description = "Compose Example App"
            copyright = "© 2024 Wooram Yang. All rights reserved."
            vendor = "Wooram Yang"
            modules("java.base")
            modules("java.desktop")
        }
    }
}

tasks.register("createCmakeDirectoryIfNotExists") {
    println("Checking if there is a cmake directory...")

    val arch = System.getProperty("os.arch")
    val os = System.getProperty("os.name").split(' ')[0]
    val cmakeDirPath = "${projectDir}/cmake/$arch/$os"
    val cmakeDir = file(cmakeDirPath)
    if (cmakeDir.exists().not()) {
        cmakeDir.mkdirs()
        println("cmake Directory created: $cmakeDir")
    }
}

tasks.register("createJniLibraryDirectoryIfNotExists") {
    dependsOn("createCmakeDirectoryIfNotExists")

    println("Checking if there is a jni library directory...")

    val libraryDirPath = "${projectDir}/libs/jni"
    val libraryDir = file(libraryDirPath)
    if (libraryDir.exists().not()) {
        libraryDir.mkdirs()
        println("Library Directory created: $libraryDir")
    }
}

tasks.register("createStaticLibraryDirectoryIfNotExists") {
    dependsOn("createJniLibraryDirectoryIfNotExists")

    println("Checking if there is a static library directory...")

    val libraryDirPath = "${projectDir}/libs/static"
    val libraryDir = file(libraryDirPath)
    if (libraryDir.exists().not()) {
        libraryDir.mkdirs()
        println("Library Directory created: $libraryDir")
    }
}

tasks.register("createGGWaveLibraryForWindows") {
    dependsOn("moveGGwaveLibraryForWindows")
}

tasks.register("buildGGWaveLibraryForWindows") {
    val arch = System.getProperty("os.arch")
    val os = System.getProperty("os.name").split(' ')[0]
    val buildPath = "${projectDir}/cmake/$arch/$os"

    exec {
        println("Executing cmake command...")
        setWorkingDir("${projectDir}/src/desktopMain")
        commandLine(
            "cmake",
            "-G",
            "Ninja",
            "-DCMAKE_BUILD_TYPE=Release",
            "-DCMAKE_C_COMPILER=gcc",
            "-DCMAKE_CXX_COMPILER=g++",
            "-DCMAKE_C_COMPILER_TARGET=x86_64-window-gnu",
            "-DCMAKE_CXX_COMPILER_TARGET=x86_64-window-gnu",
            "-B",
            buildPath,
            "-S",
            "."
        )
    }
    println("Executing cmake build command...")
    exec {
        setWorkingDir(buildPath)
        commandLine("cmake", "--build", ".")
    }
}

tasks.register("moveGGwaveLibraryForWindows") {
    dependsOn("buildGGWaveLibraryForWindows")

    val libName = "libggwave"
    val arch = System.getProperty("os.arch")
    val os = System.getProperty("os.name").split(' ')[0]
    val buildPath = "${projectDir}/cmake/$arch/$os"

    copy {
        from("$buildPath/$libName.dll")
        into("$projectDir/libs/jni")
    }
    delete {
        delete("$buildPath/$libName.dll")
    }
}

tasks.register("createGGWaveLibraryForMacOS") {
    dependsOn("createGGWaveLibraryForiOS")
}

tasks.register("buildGGWaveLibraryForMacOS") {
    val arch = System.getProperty("os.arch")
    val os = System.getProperty("os.name").split(' ')[0]
    val buildPath = "${projectDir}/cmake/$arch/$os"
    val cmakePath = "/opt/homebrew/bin/"

    doFirst {
        println("Executing cmake command...")
        exec {
            setWorkingDir("${projectDir}/src/desktopMain")
            commandLine(
                "${cmakePath}cmake",
                "-G",
                "Ninja",
                "-DCMAKE_BUILD_TYPE=Release",
                "-DCMAKE_C_COMPILER=clang",
                "-DCMAKE_CXX_COMPILER=clang++",
                "-DCMAKE_APPLE_SILICON_PROCESSOR=arm64",
                "-B",
                buildPath,
                "-S",
                "."
            )
        }
    }
    doLast {
        println("Executing cmake build command...")
        exec {
            setWorkingDir(buildPath)
            commandLine("${cmakePath}cmake", "--build", ".")
        }
    }
}

tasks.register("clearGGWaveLibraryForMacOS") {
    dependsOn("buildGGWaveLibraryForMacOS")

    val libName = "libggwave"
    val arch = System.getProperty("os.arch")
    val os = System.getProperty("os.name").split(' ')[0]
    val buildPath = "${projectDir}/cmake/$arch/$os"

    doFirst {
        println("Moving dynamic library file...")
        copy {
            from("$buildPath/$libName.dylib")
            into("$projectDir/libs/jni")
        }
    }
    doLast {
        delete {
            delete("$buildPath/$libName.dylib")
        }
    }
}

tasks.register("createGGWaveLibraryForiOS") {
    dependsOn("clearGGWaveLibraryForMacOS")

    val libName = "libggwave"
    val nativePath = "${projectDir}/native/ggwave"
    val libtoolPath = "/usr/bin/"

    doFirst {
        println("Building static library for iOS...")
        exec {
            commandLine(
                "xcrun",
                "--sdk",
                "iphonesimulator",
                "clang++",
                "-std=c++11",
                "-stdlib=libc++",
                "-c",
                "${nativePath}/resampler.cpp",
                "-o",
                "${nativePath}/resampler.o"
            )
        }
        exec {
            commandLine(
                "xcrun",
                "--sdk",
                "iphonesimulator",
                "clang++",
                "-std=c++11",
                "-stdlib=libc++",
                "-c",
                "${nativePath}/ggwave.cpp",
                "-o",
                "${nativePath}/ggwave.o"
            )
        }
    }
    doLast {
        exec {
            commandLine(
                "${libtoolPath}libtool",
                "-static",
                "-o",
                "$projectDir/libs/static/$libName.a",
                "${nativePath}/ggwave.o",
                "${nativePath}/resampler.o"
            )
        }
        delete {
            delete(
                "${nativePath}/ggwave.o",
                "${nativePath}/resampler.o")
        }
    }
}

tasks.register("createGGWaveLibrary") {
    dependsOn("createStaticLibraryDirectoryIfNotExists")

    if (OperatingSystem.current().isWindows) {
        dependsOn("createGGWaveLibraryForWindows")
    } else if (OperatingSystem.current().isMacOsX) {
        dependsOn("createGGWaveLibraryForMacOS")
    }
}
