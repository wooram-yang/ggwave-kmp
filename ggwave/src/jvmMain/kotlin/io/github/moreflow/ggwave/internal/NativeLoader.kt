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
                os.contains("win") -> "windows-x86_64"
                os.contains("mac") || os.contains("darwin") ->
                    if (arch.contains("aarch64") || arch.contains("arm")) "macos-aarch64" else "macos-x86_64"
                else ->
                    if (arch.contains("aarch64") || arch.contains("arm")) "linux-aarch64" else "linux-x86_64"
            }
            val libFileName = when {
                os.contains("win") -> "libggwave.dll"
                os.contains("mac") || os.contains("darwin") -> "libggwave.dylib"
                else -> "libggwave.so"
            }
            val resourcePath = "/native/$folder/$libFileName"
            val stream = NativeLoader::class.java.getResourceAsStream(resourcePath)
            if (stream == null) {
                try {
                    if (os.contains("win")) {
                        System.loadLibrary("libggwave")
                    } else {
                        System.loadLibrary("ggwave")
                    }
                    loaded = true
                    return
                } catch (error: UnsatisfiedLinkError) {
                    throw GgwaveException("Failed to load ggwave native library from $resourcePath", error)
                }
            }

            val dir = Files.createTempDirectory("ggwave-jni")
            dir.toFile().deleteOnExit()
            val target = dir.resolve(libFileName)
            stream.use { Files.copy(it, target, StandardCopyOption.REPLACE_EXISTING) }
            target.toFile().deleteOnExit()
            System.load(target.toAbsolutePath().toString())
            loaded = true
        }
    }
}
