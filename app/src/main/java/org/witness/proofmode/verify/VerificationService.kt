package org.witness.proofmode.verify

import android.util.Base64
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.witness.proofmode.R
import org.witness.proofmode.camera.BuildConfig
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * Network calls for the buyer-facing verification flow.
 *
 * Stage 1 — registration lookup:
 *   GET [LOOKUP_URL]/{authId}
 *   Expected 200: JSON with capture_time_utc, nickname, sha256_hash, phash
 *   Expected 404: not registered
 *
 * Stage 2 — tamper comparison (4-3 API):
 *   POST [COMPARE_URL]
 *   Multipart: auth_id (text) + image (jpeg bytes)
 *   Expected 200: JSON with classification, sha256_match, phash_hamming
 */
class VerificationService(private val context: android.content.Context) {

    companion object {
        const val LOOKUP_URL  = "https://truesnap-production.up.railway.app/api/v1/certify"
        const val COMPARE_URL = "https://truesnap-production.up.railway.app/api/compare"
        private const val TIMEOUT_MS = 30_000L
    }

    data class LookupResult(
        val registered: Boolean,
        val captureTimeUtc: String?,
        val nickname: String?,
        val sha256Hash: String?,
        val pHash: String?,
        val rawJson: String
    )

    /**
     * @param classification  "identical" | "minor_edit" | "tampered" | "unknown"
     * @param pHashHamming    Hamming distance 0-64; null if not returned by server
     */
    data class CompareResult(
        val classification: String,
        val pHashHamming: Int?,
        val rawJson: String
    )

    private val apiKey: String get() {
        val salt = byteArrayOf(0x4b, 0x32, 0x9a.toByte(), 0x1c, 0x7f, 0xe3.toByte(), 0x55, 0x8d.toByte())
        val p2Xored = Base64.decode(BuildConfig.TRUESNAP_API_KEY_P2X, Base64.NO_WRAP)
        val p2 = String(p2Xored.mapIndexed { i, b -> (b.toInt() xor salt[i % salt.size].toInt()).and(0xFF).toChar() }.toCharArray())
        return BuildConfig.TRUESNAP_API_KEY_P1 + p2
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .build()

    /** GET /certify/{authId} — returns LookupResult(registered=false) on 404. */
    fun lookupRegistration(authId: String): Result<LookupResult> {
        val request = Request.Builder()
            .url("$LOOKUP_URL/$authId")
            .addHeader("Authorization", "Bearer $apiKey")
            .get()
            .build()
        return try {
            val response = client.newCall(request).execute()
            val body = response.body?.string().orEmpty()
            if (response.isSuccessful) {
                val j = runCatching { JSONObject(body) }.getOrNull()
                Result.success(LookupResult(
                    registered     = true,
                    captureTimeUtc = j?.optString("capture_time_utc")?.takeIf { it.isNotBlank() },
                    nickname       = j?.optString("nickname")?.takeIf { it.isNotBlank() },
                    sha256Hash     = j?.optString("sha256_hash")?.takeIf { it.isNotBlank() },
                    pHash          = j?.optString("phash")?.takeIf { it.isNotBlank() },
                    rawJson        = body
                ))
            } else if (response.code == 404) {
                Result.success(LookupResult(false, null, null, null, null, body))
            } else {
                Result.failure(Exception(httpErrorMessage(response.code)))
            }
        } catch (e: Exception) {
            Timber.e(e, "lookupRegistration failed: %s", authId)
            Result.failure(Exception(friendlyNetworkError(e)))
        }
    }

    /** POST /compare/:authId — multipart: jpeg image bytes. */
    fun compareImage(authId: String, imageBytes: ByteArray): Result<CompareResult> {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "image", "verify.jpg",
                imageBytes.toRequestBody("image/jpeg".toMediaType())
            )
            .build()
        val request = Request.Builder().url("$COMPARE_URL/$authId").post(body).build()
        return try {
            val response = client.newCall(request).execute()
            val bodyStr = response.body?.string().orEmpty()
            if (response.isSuccessful) {
                val j = runCatching { JSONObject(bodyStr) }.getOrNull()
                val verdict = j?.optString("verdict")?.lowercase() ?: "unknown"
                val classification = when (verdict) {
                    "identical"  -> "identical"
                    "minor_edit" -> "minor_edit"
                    "suspicious" -> "tampered"
                    else         -> verdict
                }
                val detail = j?.optJSONObject("detail")
                Result.success(CompareResult(
                    classification = classification,
                    pHashHamming   = detail?.let { if (it.has("hammingDistance")) it.optInt("hammingDistance") else null },
                    rawJson        = bodyStr
                ))
            } else {
                Result.failure(Exception(httpErrorMessage(response.code)))
            }
        } catch (e: Exception) {
            Timber.e(e, "compareImage failed: %s", authId)
            Result.failure(Exception(friendlyNetworkError(e)))
        }
    }

    // ── Error message helpers ─────────────────────────────────────────────────

    private fun friendlyNetworkError(e: Throwable): String = when (e) {
        is java.net.SocketTimeoutException          -> context.getString(R.string.error_net_timeout)
        is java.net.ConnectException                -> context.getString(R.string.error_net_connect)
        is java.net.UnknownHostException            -> context.getString(R.string.error_net_no_internet)
        is javax.net.ssl.SSLPeerUnverifiedException -> context.getString(R.string.error_net_ssl_peer)
        is javax.net.ssl.SSLException               -> context.getString(R.string.error_net_ssl)
        else                                        -> context.getString(R.string.error_net_generic)
    }

    private fun httpErrorMessage(code: Int): String = when {
        code == 429       -> context.getString(R.string.error_http_too_many)
        code == 413       -> context.getString(R.string.error_http_too_large)
        code == 503       -> context.getString(R.string.error_http_maintenance)
        code in 500..599  -> context.getString(R.string.error_http_server)
        else              -> context.getString(R.string.error_http_generic)
    }
}
