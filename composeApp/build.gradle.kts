import org.jetbrains.compose.ExperimentalComposeLibrary
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.gradle.internal.os.OperatingSystem

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.jetbrainsCompose)
}

kotlin {
    androidTarget {
        compilations.all {
            kotlinOptions {
                jvmTarget = "1.8"
            }
        }
    }

    jvm("desktop")
    androidTarget()
    
    listOf(
        iosX64(),
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "ComposeApp"
            isStatic = true
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
            implementation(compose.material)
            implementation(compose.ui)
            @OptIn(ExperimentalComposeLibrary::class)
            implementation(compose.components.resources)

            implementation(libs.lifecycle.viewmodel.compose)
            implementation(libs.constraintlayout.compose.multiplatform)
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
    namespace = "com.example.ggwave_multiplatform"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    sourceSets["main"].manifest.srcFile("src/androidMain/AndroidManifest.xml")
    sourceSets["main"].res.srcDirs("src/androidMain/res")
    sourceSets["main"].resources.srcDirs("src/commonMain/resources")

    defaultConfig {
        applicationId = "com.example.ggwave_multiplatform"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            // target platforms
            abiFilters += listOf("armeabi-v7a", "arm64-v8a")
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
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
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
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "com.example.ggwave_multiplatform"
            packageVersion = "1.0.0"
        }
    }
}

tasks.register("compileCPP") {
    val libName = System.mapLibraryName("libggwave")
    outputs.file("$projectDir/libs/jni/$libName")
    val arch = System.getProperty("os.arch")
    val os = System.getProperty("os.name")
    val buildPath = "${projectDir}/cmake/$arch/$os"

    if (OperatingSystem.current().isWindows) {
//        exec {
//            setWorkingDir("${projectDir}/src/desktopMain")
//            commandLine(
//                "cmake",
//                "-G \"Ninja\"",
//                "-DCMAKE_BUILD_TYPE=Release",
//                "-DCMAKE_C_COMPILER=gcc",
//                "-DCMAKE_CXX_COMPILER=g++",
//                "-DCMAKE_C_COMPILER_TARGET=x86_64-window-gnu",
//                "-DCMAKE_CXX_COMPILER_TARGET=x86_64-window-gnu",
//                "-B",
//                buildPath,
//                "-S",
//                "."
//            )
//
//            setWorkingDir(buildPath)
//            commandLine("cmake", "--build", ".")
//        }
    } else if (OperatingSystem.current().isMacOsX) {
        val nativePath = "${projectDir}/native/ggwave"
        exec {
            commandLine(
                "xcrun",
                "--sdk",
                "iphonesimulator",
                "clang++",
                "-std=c++11",
                "-c",
                "${nativePath}/ggwave.cpp",
                "-o",
                "${buildPath}/ggwave.o"
            )
        }
        exec {
            commandLine(
                "libtool",
                "-static",
                "-o",
                "${buildPath}/libggwave.a",
                "${buildPath}/ggwave.o"
            )
        }
    }

    copy {
        from("$buildPath/$libName")
        into("$projectDir/libs/jni")
    }
}
tasks.getByPath("desktopProcessResources").dependsOn("compileCPP")