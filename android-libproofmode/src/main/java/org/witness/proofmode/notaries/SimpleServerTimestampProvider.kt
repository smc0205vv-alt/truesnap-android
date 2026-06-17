package org.witness.proofmode.notaries

import android.content.Context
import org.json.JSONObject
import org.witness.proofmode.notarization.NotarizationListener
import org.witness.proofmode.notarization.NotarizationProvider
import timber.log.Timber
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors

/**
 * MVP replacement for OpenTimestamps.
 *
 * Calls WorldTimeAPI to obtain a trusted server timestamp, then stores a
 * JSON record containing the media hash + authoritative UTC time.
 * The record is saved as `{hash}.server_timestamp.json` alongside the proof.
 *
 * TODO: upgrade to RFC 3161 TSA (e.g. freetsa.org) for cryptographically
 *       verifiable timestamps before production release.
 */
class SimpleServerTimestampProvider(private val context: Context) : NotarizationProvider {

    companion object {
        const val FILE_EXTENSION = ".server_timestamp.json"
        private const val TIME_API_URL = "https://worldtimeapi.org/api/timezone/UTC"
        private const val CONNECT_TIMEOUT_MS = 8_000
        private const val READ_TIMEOUT_MS = 8_000
    }

    override fun getNotarizationFileExtension(): String = FILE_EXTENSION

    override fun notarize(
        hash: String?,
        mimeType: String?,
        inputStream: InputStream?,
        listener: NotarizationListener
    ) {
        Executors.newSingleThreadExecutor().execute {
            try {
                val serverJson = fetchServerTime()
                val record = buildRecord(hash, serverJson)
                listener.notarizationSuccessful(hash, record)
                Timber.d("SimpleServerTimestamp: success for hash %s", hash)
            } catch (e: Exception) {
                Timber.e(e, "SimpleServerTimestamp: failed for hash %s", hash)
                // Fallback: use device time only so the proof file is always written.
                try {
                    val fallback = buildFallbackRecord(hash)
                    listener.notarizationSuccessful(hash, fallback)
                } catch (fe: Exception) {
                    listener.notarizationFailed(0, e.message)
                }
            } finally {
                try { inputStream?.close() } catch (_: IOException) {}
            }
        }
    }

    override fun getProof(hash: String?): String? = null

    // --- private helpers ---

    private fun fetchServerTime(): JSONObject {
        val conn = URL(TIME_API_URL).openConnection() as HttpURLConnection
        conn.connectTimeout = CONNECT_TIMEOUT_MS
        conn.readTimeout = READ_TIMEOUT_MS
        conn.requestMethod = "GET"
        conn.setRequestProperty("Accept", "application/json")
        try {
            val code = conn.responseCode
            if (code != 200) throw IOException("HTTP $code from $TIME_API_URL")
            val body = conn.inputStream.bufferedReader().readText()
            return JSONObject(body)
        } finally {
            conn.disconnect()
        }
    }

    private fun buildRecord(hash: String?, serverResponse: JSONObject): String {
        val isoNow = isoNow()
        return JSONObject().apply {
            put("hash", hash ?: "")
            put("method", "WorldTimeAPI")
            put("endpoint", TIME_API_URL)
            put("device_time_utc", isoNow)
            put("server_datetime", serverResponse.optString("datetime", isoNow))
            put("server_unixtime", serverResponse.optLong("unixtime", System.currentTimeMillis() / 1000))
            put("server_response", serverResponse)
        }.toString(2)
    }

    private fun buildFallbackRecord(hash: String?): String {
        val isoNow = isoNow()
        return JSONObject().apply {
            put("hash", hash ?: "")
            put("method", "device_clock_fallback")
            put("device_time_utc", isoNow)
            put("note", "Server unreachable; device clock used as fallback")
        }.toString(2)
    }

    private fun isoNow(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        return sdf.format(Date())
    }
}
