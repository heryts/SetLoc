package com.heryts.setloc.utils

import kotlinx.coroutines.delay
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class HttpResult(val code: Int, val text: String) {
    val ok: Boolean get() = code in 200..299
}

object Http {
    suspend fun call(
        url: String,
        body: ByteArray? = null,
        headers: Map<String, String> = emptyMap(),
        connectMs: Int = 8_000,
        readMs: Int = 12_000,
        retries: Int = 1
    ): HttpResult {
        var attempt = 0
        while (true) {
            val last = attempt >= retries
            try {
                val conn = URL(url).openConnection() as HttpURLConnection
                try {
                    conn.connectTimeout = connectMs
                    conn.readTimeout = readMs
                    for ((k, v) in headers) conn.setRequestProperty(k, v)
                    if (body != null) {
                        conn.requestMethod = "POST"
                        conn.doOutput = true
                        conn.outputStream.use { it.write(body) }
                    }
                    val code = conn.responseCode
                    val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                    val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                    if (code < 500 || last) return HttpResult(code, text)
                } finally {
                    conn.disconnect()
                }
            } catch (e: IOException) {
                if (last) throw e
            }
            delay(300L shl attempt)
            attempt++
        }
    }
}
