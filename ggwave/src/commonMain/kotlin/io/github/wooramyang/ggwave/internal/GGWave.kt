package io.github.wooramyang.ggwave.internal

import io.github.wooramyang.ggwave.GgwaveConfig

internal interface GGWave {
    fun init(config: GgwaveConfig)
    fun close()
    fun decode(shortData: ShortArray): ByteArray?
    fun decode(byteData: ByteArray): ByteArray?
    fun encode(message: String, volume: Int): ShortArray
}

internal expect fun createGGWave(): GGWave
