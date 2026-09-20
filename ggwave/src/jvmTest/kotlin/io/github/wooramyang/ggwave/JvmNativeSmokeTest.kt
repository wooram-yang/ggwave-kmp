package io.github.wooramyang.ggwave

import io.github.wooramyang.ggwave.internal.createGGWave
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class JvmNativeSmokeTest {
    @Test
    fun initAndEncodeWhenLibraryAvailable() {
        val resource = javaClass.getResource("/native/windows-x86_64/libggwave.dll")
            ?: javaClass.getResource("/native/macos-aarch64/libggwave.dylib")
            ?: javaClass.getResource("/native/macos-x86_64/libggwave.dylib")
            ?: javaClass.getResource("/native/linux-x86_64/libggwave.so")
        if (resource == null) return

        val ggWave = createGGWave()
        ggWave.init(GgwaveConfig())
        val encoded = ggWave.encode("hi", volume = 10)
        ggWave.close()
        assertNotNull(encoded)
        assertTrue(encoded.isNotEmpty())
    }
}
