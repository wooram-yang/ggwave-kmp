package io.github.moreflow.ggwave.internal

import io.github.moreflow.ggwave.GgwaveCodec

internal fun requireValidEncodeArguments(payload: ByteArray, volume: Int) {
    require(payload.isNotEmpty()) { "payload must not be empty" }
    require(payload.size <= GgwaveCodec.MAX_PAYLOAD_SIZE) {
        "payload must not exceed ${GgwaveCodec.MAX_PAYLOAD_SIZE} bytes"
    }
    require(volume in GgwaveCodec.MIN_VOLUME..GgwaveCodec.MAX_VOLUME) {
        "volume must be between ${GgwaveCodec.MIN_VOLUME} and ${GgwaveCodec.MAX_VOLUME}"
    }
}
