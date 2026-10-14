package io.github.moreflow.ggwave

import kotlin.test.Test
import kotlin.test.assertFailsWith

class GgwaveCodecConfigTest {
    @Test
    fun rejectsUnsupportedSampleRates() {
        assertFailsWith<IllegalArgumentException> {
            GgwaveCodecConfig(inputSampleRate = 4_000)
        }
        assertFailsWith<IllegalArgumentException> {
            GgwaveCodecConfig(outputSampleRate = 192_000)
        }
    }
}
