package org.witness.proofmode.verify

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
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
class VerificationService {

    companion object {
        const val LOOKUP_URL  = "https://truesnap-production.up.railway.app/api/v1/certify"
        const val COMPARE_URL = "https://truesnap-production.up.railway.app/api/v1/compare"
        private const val TIMEOUT_MS = 30_000L
    }

    data class LookupResult(
        val registered: Boolean,
        val captureTimeUtc: String?,
        val nickname: String?,
        val sha256Hash: String?,
        val pHash: String?,
        val lofiThumbnailBase64: String?,
        val rawJson: String
    )

    /**
     * @param classification  "identical" | "minor_edit" | "tampered" | "unknown"
     * @param pHashHamming    Hamming distance 0-64; null if not returned by server
     */
    data class CompareResult(
        val classification: String,
        val sha256Match: Boolean?,
        val pHashHamming: Int?,
        val rawJson: String
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .build()

    /** GET /certify/{authId} — returns LookupResult(registered=false) on 404. */
    fun lookupRegistration(authId: String): Result<LookupResult> {
        val request = Request.Builder().url("$LOOKUP_URL/$authId").get().build()
        return try {
            val response = client.newCall(request).execute()
            val body = response.body?.string().orEmpty()
            if (response.isSuccessful) {
                val j = runCatching { JSONObject(body) }.getOrNull()
                Result.success(LookupResult(
                    registered          = true,
                    captureTimeUtc      = j?.optString("capture_time_utc")?.takeIf { it.isNotBlank() },
                    nickname            = j?.optString("nickname")?.takeIf { it.isNotBlank() },
                    sha256Hash          = j?.optString("sha256_hash")?.takeIf { it.isNotBlank() },
                    pHash               = j?.optString("phash")?.takeIf { it.isNotBlank() },
                    lofiThumbnailBase64 = j?.optString("lofi_thumbnail")?.takeIf { it.isNotBlank() },
                    rawJson             = body
                ))
            } else if (response.code == 404) {
                Result.success(LookupResult(false, null, null, null, null, null, body))
            } else {
                Result.failure(Exception("HTTP ${response.code}"))
            }
        } catch (e: Exception) {
            Timber.e(e, "lookupRegistration failed: %s", authId)
            Result.failure(e)
        }
    }

    /** POST /compare — multipart: auth_id + jpeg image bytes. */
    fun compareImage(authId: String, imageBytes: ByteArray): Result<CompareResult> {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("auth_id", authId)
            .addFormDataPart(
                "image", "verify.jpg",
                imageBytes.toRequestBody("image/jpeg".toMediaType())
            )
            .build()
        val request = Request.Builder().url(COMPARE_URL).post(body).build()
        return try {
            val response = client.newCall(request).execute()
            val bodyStr = response.body?.string().orEmpty()
            if (response.isSuccessful) {
                val j = runCatching { JSONObject(bodyStr) }.getOrNull()
                Result.success(CompareResult(
                    classification = j?.optString("classification") ?: "unknown",
                    sha256Match    = if (j?.has("sha256_match") == true) j.optBoolean("sha256_match") else null,
                    pHashHamming   = if (j?.has("phash_hamming") == true) j.optInt("phash_hamming") else null,
                    rawJson        = bodyStr
                ))
            } else {
                Result.failure(Exception("HTTP ${response.code}"))
            }
        } catch (e: Exception) {
            Timber.e(e, "compareImage failed: %s", authId)
            Result.failure(e)
        }
    }
}
