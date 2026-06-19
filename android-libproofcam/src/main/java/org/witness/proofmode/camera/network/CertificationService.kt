package org.witness.proofmode.camera.network

import android.graphics.Bitmap
import android.util.Base64
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import ru.avicorp.phashcalc.pHashCalc
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * Handles SHA-256 hashing, perceptual hashing (pHash), auth-ID generation,
 * and server upload for post-capture certification.
 *
 * ## Dual-hash design
 *
 * Each certified image carries two hashes:
 *
 * | Hash       | Algorithm              | Purpose                              |
 * |------------|------------------------|--------------------------------------|
 * | sha256_hash| SHA-256 (cryptographic)| Exact-match integrity verification   |
 * | phash      | DCT perceptual hash    | Visual-similarity comparison         |
 *
 * ## Server-side classification (implement on backend)
 *
 *   SHA-256 same  →  동일 파일 (bitwise identical, unmodified)
 *   SHA-256 diff + pHash similar (Hamming ≤ 10)  →  단순 보정됨 (minor colour/brightness/crop)
 *   SHA-256 diff + pHash different (Hamming > 10) →  구조 변경 의심 (content substantially altered)
 *
 * The upload payload includes [phash_hamming_note] as a human-readable hint so the server
 * can log the classification even without a reference image on first ingest:
 * on subsequent comparisons against the same auth_id the backend applies the Hamming rule.
 *
 * ## Endpoint
 * [CERTIFICATION_API_URL] — replace before production.
 */
class CertificationService {

    companion object {
        const val CERTIFICATION_API_URL =
            "https://truesnap-production.up.railway.app/api/v1/certify"

        /** Metadata-only endpoint — receives JSON, no image file. */
        const val METADATA_API_URL = "https://truesnap-production.up.railway.app/api/v1/certify"

        private val AUTH_ID_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        private const val AUTH_ID_LENGTH = 6
        private const val CONNECT_TIMEOUT_MS = 30_000L
        private const val READ_TIMEOUT_MS   = 60_000L

        /** Temp file prefix used during pHash calculation. */
        private const val PHASH_TEMP_PREFIX = "truesnap_phash_"
    }

    // ---------------------------------------------------------------------------
    // Public data types — image upload (legacy, kept for future use)
    // ---------------------------------------------------------------------------

    data class CertificationRequest(
        val imageBytes: ByteArray,
        val sha256Hash: String,
        val pHash: String?,
        val authId: String,
        val captureTimestampMs: Long
    )

    sealed class CertificationResult {
        data class Success(
            val authId: String,
            val sha256Hash: String,
            val pHash: String?,
            val serverResponse: String
        ) : CertificationResult()

        data class Failure(
            val authId: String,
            val sha256Hash: String,
            val pHash: String?,
            val error: String
        ) : CertificationResult()
    }

    // ---------------------------------------------------------------------------
    // Public data types — metadata-only upload (current pipeline)
    // ---------------------------------------------------------------------------

    /**
     * Payload sent to [METADATA_API_URL]. No image bytes — only hashes + metadata.
     *
     * JSON body fields:
     *   auth_id              — "TS-XXXXXX" client-generated identifier
     *   sha256_hash          — lowercase hex SHA-256 of the final edited JPEG
     *   phash                — DCT perceptual hash string (null → omitted)
     *   capture_timestamp_ms — epoch milliseconds at capture time
     *   capture_time_utc     — ISO-8601 UTC string of the above
     *   nickname             — counterparty name entered by the user (not ownership-verified)
     *   lofi_thumbnail       — base64 PNG of an 8×8 downscale; non-reversible composition hint
     */
    data class MetadataUploadRequest(
        val authId: String,
        val sha256Hash: String,
        val pHash: String?,
        val captureTimestampMs: Long,
        val nickname: String,
        val lofiThumbnailBase64: String? = null
    )

    sealed class MetadataUploadResult {
        data class Success(val authId: String) : MetadataUploadResult()
        data class Failure(val authId: String, val error: String) : MetadataUploadResult()
    }

    // ---------------------------------------------------------------------------
    // Core utilities
    // ---------------------------------------------------------------------------

    /**
     * Downscales [bitmap] to 8×8 pixels (no interpolation) and returns it as a
     * base64-encoded PNG string (~256 bytes). The result is a non-reversible
     * composition hint — it cannot be used to reconstruct the original image.
     */
    fun generateLofiThumbnail(bitmap: Bitmap): String? {
        return try {
            val tiny = Bitmap.createScaledBitmap(bitmap, 8, 8, false)
            val out = ByteArrayOutputStream()
            tiny.compress(Bitmap.CompressFormat.PNG, 100, out)
            tiny.recycle()
            Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        } catch (e: Exception) {
            Timber.e(e, "Lofi thumbnail generation failed")
            null
        }
    }

    /** Generates a cryptographically random "TS-XXXXXX" identifier. */
    fun generateAuthId(): String {
        val rng = SecureRandom()
        val suffix = (1..AUTH_ID_LENGTH)
            .map { AUTH_ID_ALPHABET[rng.nextInt(AUTH_ID_ALPHABET.length)] }
            .joinToString("")
        return "TS-$suffix"
    }

    /** Returns lowercase hex SHA-256 of [bytes]. */
    fun calculateSha256(bytes: ByteArray): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
    }

    /**
     * Calculates a DCT-based perceptual hash using [pHashCalc].
     *
     * Steps:
     *   1. Write [imageBytes] to a temporary JPEG file in [cacheDir]
     *   2. Load the file with [pHashCalc] (both slots point to the same file)
     *   3. Extract [pHashCalc.getHashOne] as the perceptual hash string
     *   4. Delete the temp file
     *
     * @return Perceptual hash string, or null if the library fails to process the image.
     */
    fun calculatePHash(imageBytes: ByteArray, cacheDir: File): String? {
        val tempFile = File(cacheDir, "$PHASH_TEMP_PREFIX${System.currentTimeMillis()}.jpg")
        return try {
            tempFile.writeBytes(imageBytes)
            val calc = pHashCalc()
            val loaded = calc.loadSourceFile(tempFile.absolutePath, tempFile.absolutePath)
            if (loaded && calc.checkCondition()) {
                calc.getHashOne().toString().also { hash ->
                    Timber.d("pHash calculated: %s", hash)
                }
            } else {
                Timber.w("pHashCalc: loadSourceFile=%b checkCondition=%b", loaded, calc.checkCondition())
                null
            }
        } catch (e: Exception) {
            Timber.e(e, "pHash calculation failed")
            null
        } finally {
            tempFile.delete()
        }
    }

    // ---------------------------------------------------------------------------
    // Metadata-only upload (current pipeline)
    // ---------------------------------------------------------------------------

    /**
     * POSTs certification metadata as JSON to [METADATA_API_URL]. No image bytes are sent.
     *
     * This is a **blocking** call — run it on an IO thread.
     *
     * Example request body:
     * ```json
     * {
     *   "auth_id":              "TS-AB12CD",
     *   "sha256_hash":          "a3f2…",
     *   "phash":                "123456789abcdef0",
     *   "capture_timestamp_ms": 1750000000000,
     *   "capture_time_utc":     "2026-06-18T12:34:56Z",
     *   "nickname":             "홍길동"
     * }
     * ```
     */
    fun uploadMetadata(request: MetadataUploadRequest): MetadataUploadResult {
        val captureTimeUtc = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date(request.captureTimestampMs))

        val jsonBody = org.json.JSONObject().run {
            put("auth_id",              request.authId)
            put("sha256_hash",          request.sha256Hash)
            if (request.pHash != null) put("phash", request.pHash)
            put("capture_timestamp_ms", request.captureTimestampMs)
            put("capture_time_utc",     captureTimeUtc)
            put("nickname",             request.nickname)
            if (request.lofiThumbnailBase64 != null) put("lofi_thumbnail", request.lofiThumbnailBase64)
            toString()
        }

        val client = OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .build()

        val httpRequest = Request.Builder()
            .url(METADATA_API_URL)
            .post(jsonBody.toRequestBody("application/json".toMediaType()))
            .build()

        return try {
            val response = client.newCall(httpRequest).execute()
            val body = response.body?.string().orEmpty()
            if (response.isSuccessful) {
                Timber.d("Metadata upload success: authId=%s", request.authId)
                MetadataUploadResult.Success(request.authId)
            } else {
                val msg = "HTTP ${response.code}: $body"
                Timber.w("Metadata upload failed: %s", msg)
                MetadataUploadResult.Failure(request.authId, msg)
            }
        } catch (e: Exception) {
            Timber.e(e, "Metadata upload exception")
            MetadataUploadResult.Failure(request.authId, e.message ?: "Network error")
        }
    }

    // ---------------------------------------------------------------------------
    // Image upload (kept for future use — not called in current pipeline)
    // ---------------------------------------------------------------------------

    /**
     * Sends [request] to [CERTIFICATION_API_URL] as a multipart POST.
     *
     * Multipart fields:
     *   image                — JPEG bytes of the final edited photo
     *   sha256_hash          — lowercase hex SHA-256 of those bytes
     *   phash                — DCT perceptual hash string (empty string if unavailable)
     *   phash_algorithm      — fixed tag "ru.avicorp.phashcalc-dct-v1.0.1"
     *   auth_id              — "TS-XXXXXX" client-generated identifier
     *   capture_timestamp_ms — epoch ms at capture time
     *   capture_time_utc     — ISO-8601 UTC string of the above
     *
     * This is a **blocking** call — run it on an IO thread.
     */
    fun upload(request: CertificationRequest): CertificationResult {
        val captureTimeUtc = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date(request.captureTimestampMs))

        val client = OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .build()

        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "image", "capture.jpg",
                request.imageBytes.toRequestBody("image/jpeg".toMediaType())
            )
            .addFormDataPart("sha256_hash",          request.sha256Hash)
            .addFormDataPart("phash",                request.pHash ?: "")
            .addFormDataPart("phash_algorithm",      "ru.avicorp.phashcalc-dct-v1.0.1")
            .addFormDataPart("auth_id",              request.authId)
            .addFormDataPart("capture_timestamp_ms", request.captureTimestampMs.toString())
            .addFormDataPart("capture_time_utc",     captureTimeUtc)
            .build()

        val httpRequest = Request.Builder()
            .url(CERTIFICATION_API_URL)
            .post(body)
            .build()

        return try {
            val response = client.newCall(httpRequest).execute()
            val responseBody = response.body?.string().orEmpty()
            if (response.isSuccessful) {
                Timber.d("Certification upload success: authId=%s sha256=%s phash=%s",
                    request.authId, request.sha256Hash, request.pHash)
                CertificationResult.Success(request.authId, request.sha256Hash,
                    request.pHash, responseBody)
            } else {
                val msg = "HTTP ${response.code}: $responseBody"
                Timber.w("Certification upload failed: %s", msg)
                CertificationResult.Failure(request.authId, request.sha256Hash,
                    request.pHash, msg)
            }
        } catch (e: Exception) {
            Timber.e(e, "Certification upload exception")
            CertificationResult.Failure(request.authId, request.sha256Hash,
                request.pHash, e.message ?: "Network error")
        }
    }
}
