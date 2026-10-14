package io.github.moreflow.ggwave.internal

import io.github.moreflow.ggwave.GgwaveException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal object NativeLoader {
    @Volatile
    private var loaded = false
    private val lock = Any()

    fun load() {
        if (loaded) return
        synchronized(lock) {
            if (loaded) return

            val os = System.getProperty("os.name").orEmpty().lowercase()
            val arch = System.getProperty("os.arch").orEmpty().lowercase()
            val folder = when {
                os.contains("win") && arch.contains("64") -> "windows-x86_64"
                (os.contains("mac") || os.contains("darwin")) &&
                    (arch.contains("aarch64") || arch.contains("arm64")) -> "macos-aarch64"
                os.contains("mac") || os.contains("darwin") -> "macos-x86_64"
                (arch.contains("aarch64") || arch.contains("arm64")) -> "linux-aarch64"
                arch.contains("64") -> "linux-x86_64"
                else -> throw GgwaveException("Unsupported JVM platform: os=$os, arch=$arch")
            }
            val libraryName = when {
                os.contains("win") -> "libggwave.dll"
                os.contains("mac") || os.contains("darwin") -> "libggwave.dylib"
                else -> "libggwave.so"
            }
            val resourcePath = "/native/$folder/$libraryName"
            val stream = NativeLoader::class.java.getResourceAsStream(resourcePath)
                ?: throw GgwaveException("Native ggwave library is missing from $resourcePath")

            try {
                val directory = Files.createTempDirectory("ggwave-jni")
                directory.toFile().deleteOnExit()
                val target = directory.resolve(libraryName)
                stream.use { Files.copy(it, target, StandardCopyOption.REPLACE_EXISTING) }
                target.toFile().deleteOnExit()
                System.load(target.toAbsolutePath().toString())
                loaded = true
            } catch (error: Throwable) {
                throw GgwaveException("Failed to load native ggwave library from $resourcePath", error)
            }
        }
    }
}
