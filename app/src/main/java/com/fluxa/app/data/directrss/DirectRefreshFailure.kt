package com.fluxa.app.data.directrss

import java.io.IOException

class DirectHttpException(val status: Int) : IOException("HTTP $status")
class DirectPayloadException(message: String) : IOException(message)
class DirectRefreshException(message: String, val retryable: Boolean) : IllegalStateException(message)

internal fun isRetryableDirectFailure(error: Throwable): Boolean = when (error) {
    is DirectRefreshException -> error.retryable
    is DirectHttpException -> error.status == 429 || error.status in 500..599
    is DirectPayloadException -> false
    is IOException -> true
    else -> false
}
