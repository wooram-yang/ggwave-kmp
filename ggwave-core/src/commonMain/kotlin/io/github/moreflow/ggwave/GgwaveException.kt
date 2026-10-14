package io.github.moreflow.ggwave

class GgwaveException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
