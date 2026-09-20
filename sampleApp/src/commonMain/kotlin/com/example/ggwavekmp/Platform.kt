package com.example.ggwavekmp

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform
