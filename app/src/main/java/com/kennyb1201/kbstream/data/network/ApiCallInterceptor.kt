package com.kennyb1201.kbstream.data.network

import com.kennyb1201.kbstream.data.reporting.ApiCallMeter
import okhttp3.Interceptor

/**
 * The one place a request is counted (F7, spec 2026-11-20).
 *
 * Kept apart from [ApiCallMeter] so the meter itself imports nothing from
 * OkHttp: the counters, the host map and the printed line are then plain JVM
 * code and can be pinned by a unit test without an HTTP stack.
 */
internal fun apiCallMeterInterceptor(): Interceptor = Interceptor { chain ->
    val request = chain.request()
    ApiCallMeter.record(ApiCallMeter.labelFor(request.url.host))
    chain.proceed(request)
}
