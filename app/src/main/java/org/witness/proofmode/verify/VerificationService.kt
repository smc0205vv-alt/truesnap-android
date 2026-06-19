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
        const val COMPARE_URL = "https://truesnap-production.up.railway.app/api/compare"
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
        is java.net.SocketTimeoutException          -> "서버 응답 시간이 초과됐습니다. 잠시 후 다시 시도해주세요."
        is java.net.ConnectException                -> "서버에 연결할 수 없습니다. 잠시 후 다시 시도해주세요."
        is java.net.UnknownHostException            -> "인터넷 연결을 확인해주세요."
        is javax.net.ssl.SSLPeerUnverifiedException -> "보안 인증서 검증에 실패했습니다. 앱을 최신 버전으로 업데이트해주세요."
        is javax.net.ssl.SSLException               -> "보안 연결 오류가 발생했습니다."
        else                                        -> "네트워크 오류가 발생했습니다."
    }

    private fun httpErrorMessage(code: Int): String = when {
        code == 429       -> "요청이 너무 많습니다. 잠시 후 다시 시도해주세요."
        code == 413       -> "파일이 너무 큽니다."
        code == 503       -> "서버 점검 중입니다. 잠시 후 다시 시도해주세요."
        code in 500..599  -> "서버 오류가 발생했습니다. 잠시 후 다시 시도해주세요."
        else              -> "오류가 발생했습니다. 잠시 후 다시 시도해주세요."
    }
}
