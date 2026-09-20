package io.github.wooramyang.ggwave

class GgwaveException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
