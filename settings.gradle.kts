import org.gradle.internal.os.OperatingSystem

rootProject.name = "ggwave_multiplatform"
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
    repositories {
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
        google()
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    }
}

gradle.beforeProject {
    tasks.register("createCmakeDirectoryIfNotExists") {
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
        val libraryDirPath = "${projectDir}/libs/jni"
        val libraryDir = file(libraryDirPath)
        if (libraryDir.exists().not()) {
            libraryDir.mkdirs()
            println("Library Directory created: $libraryDir")
        }
    }

    tasks.register("createStaticLibraryDirectoryIfNotExists") {
        val libraryDirPath = "${projectDir}/libs/static"
        val libraryDir = file(libraryDirPath)
        if (libraryDir.exists().not()) {
            libraryDir.mkdirs()
            println("Library Directory created: $libraryDir")
        }
    }

    tasks.register("createDirectoryIfNotExists") {
        dependsOn("createCmakeDirectoryIfNotExists")
        dependsOn("createJniLibraryDirectoryIfNotExists")
        dependsOn("createStaticLibraryDirectoryIfNotExists")
    }

    tasks.register("createGGWaveLibrary") {
        dependsOn("createDirectoryIfNotExists")

        val libName = "libggwave"
        val arch = System.getProperty("os.arch")
        val os = System.getProperty("os.name").split(' ')[0]
        val buildPath = "${projectDir}/cmake/$arch/$os"

        if (OperatingSystem.current().isWindows) {
            exec {
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
            exec {
                setWorkingDir(buildPath)
                commandLine("cmake", "--build", ".")
            }
            copy {
                from("$buildPath/$libName.dll")
                into("$projectDir/libs/jni")
            }
            delete {
                delete("$buildPath/$libName.dll")
            }
        } else if (OperatingSystem.current().isMacOsX) {
            exec {
                setWorkingDir("${projectDir}/src/desktopMain")
                commandLine(
                    "cmake",
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
            exec {
                setWorkingDir(buildPath)
                commandLine("cmake", "--build", ".")
            }
            copy {
                from("$buildPath/$libName.dylib")
                into("$projectDir/libs/jni")
            }
            delete {
                delete("$buildPath/$libName.dylib")
            }

            val nativePath = "${projectDir}/native/ggwave"
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
            exec {
                commandLine(
                    "libtool",
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

    gradle.taskGraph.whenReady {
        if (gradle.taskGraph.hasTask("createGGWaveLibrary")) {
            tasks["createGGWaveLibrary"].actions.forEach { action ->
                action.execute(tasks["createGGWaveLibrary"])
            }
        }
    }
}

include(":composeApp")