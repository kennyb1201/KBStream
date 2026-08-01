package com.kennyb1201.kbstream.data.simkl

object SimklConfig {
    const val BASE_URL = "https://api.simkl.com/"
    const val APP_NAME = "kbstream"
    const val APP_VERSION = "0.1.0"

    fun userAgent(): String = "$APP_NAME/$APP_VERSION"
}
