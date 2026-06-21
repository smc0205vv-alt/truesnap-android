package org.witness.proofmode.camera.network

import org.witness.proofmode.camera.BuildConfig
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import timber.log.Timber
import java.util.concurrent.TimeUnit
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Handles SHA-256 hashing, auth-ID generation, edge fingerprint computation,
 * and server upload for post-capture certification.
 *
 * Verdict pipeline (server-side):
 *   edge density (primary) → HOG downgrade-only → stddev texture check
 */
class CertificationService {

    companion object {
        const val CERTIFICATION_API_URL =
            "https://truesnap-production.up.railway.app/api/v1/certify"

        const val METADATA_API_URL = "https://truesnap-production.up.railway.app/api/v1/certify"

        private val API_KEY: String get() = BuildConfig.TRUESNAP_API_KEY

        private val AUTH_ID_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        private const val AUTH_ID_LENGTH = 6
        private const val CONNECT_TIMEOUT_MS = 30_000L
        private const val READ_TIMEOUT_MS   = 60_000L

        private const val THUMBNAIL_SIZE = 32

        // Edge density constants — must match server src/lib/phash.js
        private const val EDGE_MAX_DIM      = 1024
        private const val EDGE_REL_FACTOR   = 0.25f
        private const val EDGE_MIN_GRADIENT = 10f
        private const val EDGE_GRID_COLS    = 32
        private const val EDGE_GRID_ROWS    = 32

        // Gaussian pre-blur constants — must match server src/lib/phash.js
        private const val GAUSSIAN_KERNEL_SIZE = 5
        private const val GAUSSIAN_SIGMA       = 1.0f
    }

    // ---------------------------------------------------------------------------
    // Public data types — image upload (legacy, kept for future use)
    // ---------------------------------------------------------------------------

    data class CertificationRequest(
        val imageBytes: ByteArray,
        val sha256Hash: String,
        val authId: String,
        val captureTimestampMs: Long
    )

    sealed class CertificationResult {
        data class Success(
            val authId: String,
            val sha256Hash: String,
            val serverResponse: String
        ) : CertificationResult()

        data class Failure(
            val authId: String,
            val sha256Hash: String,
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
     *   capture_timestamp_ms — epoch milliseconds at capture time
     *   capture_time_utc     — ISO-8601 UTC string of the above
     *   nickname             — counterparty name entered by the user
     *   lofi_thumbnail       — base64 PNG of the watermarked image downscaled to 32×32
     */
    data class MetadataUploadRequest(
        val authId: String,
        val sha256Hash: String,
        val captureTimestampMs: Long,
        val nickname: String,
        val lofiThumbnailBase64: String? = null,
        val edgeDensities: FloatArray? = null,
        val edgeHog: FloatArray? = null,
        val edgeStdDev: FloatArray? = null
    )

    sealed class MetadataUploadResult {
        data class Success(val authId: String, val expiresAtMs: Long) : MetadataUploadResult()
        data class Failure(val authId: String, val error: String) : MetadataUploadResult()
    }

    // ---------------------------------------------------------------------------
    // Core utilities
    // ---------------------------------------------------------------------------

    fun generateThumbnail32(bitmap: Bitmap): String? {
        return try {
            val thumb = Bitmap.createScaledBitmap(bitmap, THUMBNAIL_SIZE, THUMBNAIL_SIZE, true)
            val out = ByteArrayOutputStream()
            thumb.compress(Bitmap.CompressFormat.JPEG, 60, out)
            thumb.recycle()
            Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        } catch (e: Exception) {
            Timber.e(e, "Thumbnail generation failed")
            null
        }
    }

    /** @deprecated Use [generateThumbnail32] instead. */
    fun generateLofiThumbnail(bitmap: Bitmap): String? = generateThumbnail32(bitmap)

    fun generateAuthId(): String {
        val rng = SecureRandom()
        val suffix = (1..AUTH_ID_LENGTH)
            .map { AUTH_ID_ALPHABET[rng.nextInt(AUTH_ID_ALPHABET.length)] }
            .joinToString("")
        return "TS-$suffix"
    }

    fun calculateSha256(bytes: ByteArray): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
    }

    // ---------------------------------------------------------------------------
    // Metadata-only upload (current pipeline)
    // ---------------------------------------------------------------------------

    fun uploadMetadata(request: MetadataUploadRequest): MetadataUploadResult {
        val captureTimeUtc = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date(request.captureTimestampMs))

        val jsonBody = org.json.JSONObject().run {
            put("auth_id",              request.authId)
            put("sha256_hash",          request.sha256Hash)
            put("capture_timestamp_ms", request.captureTimestampMs)
            put("capture_time_utc",     captureTimeUtc)
            put("nickname",             request.nickname)
            if (request.lofiThumbnailBase64 != null) put("lofi_thumbnail", request.lofiThumbnailBase64)
            if (request.edgeDensities != null && request.edgeDensities.isNotEmpty()) {
                val arr = org.json.JSONArray()
                request.edgeDensities.forEach { arr.put(it.toDouble()) }
                put("edge_densities", arr)
            }
            if (request.edgeHog != null && request.edgeHog.isNotEmpty()) {
                val arr = org.json.JSONArray()
                request.edgeHog.forEach { arr.put(it.toDouble()) }
                put("edge_hog", arr)
            }
            if (request.edgeStdDev != null && request.edgeStdDev.isNotEmpty()) {
                val arr = org.json.JSONArray()
                request.edgeStdDev.forEach { arr.put(it.toDouble()) }
                put("edge_stddev", arr)
            }
            toString()
        }

        val client = OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .build()

        val httpRequest = Request.Builder()
            .url(METADATA_API_URL)
            .addHeader("Authorization", "Bearer $API_KEY")
            .post(jsonBody.toRequestBody("application/json".toMediaType()))
            .build()

        return try {
            val response = client.newCall(httpRequest).execute()
            val body = response.body?.string().orEmpty()
            if (response.isSuccessful) {
                val expiresAtMs = try {
                    val json = org.json.JSONObject(body)
                    val iso = json.optString("expiresAt")
                    if (iso.isNotBlank()) java.time.Instant.parse(iso).toEpochMilli() else 0L
                } catch (e: Exception) { 0L }
                Timber.d("Metadata upload success: authId=%s expiresAtMs=%d", request.authId, expiresAtMs)
                MetadataUploadResult.Success(request.authId, expiresAtMs)
            } else {
                Timber.w("Metadata upload failed: HTTP %d: %s", response.code, body)
                MetadataUploadResult.Failure(request.authId, uploadHttpErrorMessage(response.code))
            }
        } catch (e: Exception) {
            Timber.e(e, "Metadata upload exception")
            MetadataUploadResult.Failure(request.authId, uploadNetworkError(e))
        }
    }

    // ---------------------------------------------------------------------------
    // Gaussian blur helper — shared by all three edge feature computations
    // ---------------------------------------------------------------------------

    private fun gaussianBlur(gray: FloatArray, W: Int, H: Int): FloatArray {
        val half = GAUSSIAN_KERNEL_SIZE / 2
        val kernel = FloatArray(GAUSSIAN_KERNEL_SIZE) { i ->
            val d = (i - half).toFloat()
            kotlin.math.exp(-(d * d / (2f * GAUSSIAN_SIGMA * GAUSSIAN_SIGMA)).toDouble()).toFloat()
        }
        val kernelSum = kernel.sum()
        for (i in kernel.indices) kernel[i] /= kernelSum

        val tmp = FloatArray(W * H)
        val out = FloatArray(W * H)

        for (y in 0 until H) {
            for (x in 0 until W) {
                var s = 0f
                for (k in 0 until GAUSSIAN_KERNEL_SIZE) {
                    val sx = (x + k - half).coerceIn(0, W - 1)
                    s += kernel[k] * gray[y * W + sx]
                }
                tmp[y * W + x] = s
            }
        }

        for (y in 0 until H) {
            for (x in 0 until W) {
                var s = 0f
                for (k in 0 until GAUSSIAN_KERNEL_SIZE) {
                    val sy = (y + k - half).coerceIn(0, H - 1)
                    s += kernel[k] * tmp[sy * W + x]
                }
                out[y * W + x] = s
            }
        }

        return out
    }

    // ---------------------------------------------------------------------------
    // Edge fingerprint computation
    // ---------------------------------------------------------------------------

    /**
     * Computes Sobel edge density per block in a 32×32 grid.
     * Must match server src/lib/phash.js _computeEdgeFeatures.
     * @return FloatArray of 1024 density values (0.0–1.0), or null on error.
     */
    fun calculateEdgeDensities(imageBytes: ByteArray): FloatArray? {
        return try {
            var bmp = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size) ?: return null

            val maxDim = maxOf(bmp.width, bmp.height)
            if (maxDim > EDGE_MAX_DIM) {
                val scale = EDGE_MAX_DIM.toFloat() / maxDim
                val newW = (bmp.width * scale).toInt().coerceAtLeast(1)
                val newH = (bmp.height * scale).toInt().coerceAtLeast(1)
                val small = Bitmap.createScaledBitmap(bmp, newW, newH, true)
                bmp.recycle()
                bmp = small
            }

            val W = bmp.width
            val H = bmp.height

            val grayRaw = FloatArray(W * H)
            for (y in 0 until H) {
                for (x in 0 until W) {
                    val c = bmp.getPixel(x, y)
                    grayRaw[y * W + x] =
                        0.299f * ((c shr 16) and 0xFF) +
                        0.587f * ((c shr 8)  and 0xFF) +
                        0.114f * (c and 0xFF)
                }
            }
            bmp.recycle()

            val gray = gaussianBlur(grayRaw, W, H)

            val mag = FloatArray(W * H)
            for (y in 1 until H - 1) {
                for (x in 1 until W - 1) {
                    val tl = gray[(y-1)*W+(x-1)]; val tc = gray[(y-1)*W+x]; val tr = gray[(y-1)*W+(x+1)]
                    val ml = gray[y*W+(x-1)];                                 val mr = gray[y*W+(x+1)]
                    val bl = gray[(y+1)*W+(x-1)]; val bc = gray[(y+1)*W+x]; val br = gray[(y+1)*W+(x+1)]
                    val gx = -tl + tr - 2f*ml + 2f*mr - bl + br
                    val gy = -tl - 2f*tc - tr + bl + 2f*bc + br
                    mag[y * W + x] = kotlin.math.sqrt((gx*gx + gy*gy).toDouble()).toFloat()
                }
            }

            val blockW = W / EDGE_GRID_COLS
            val blockH = H / EDGE_GRID_ROWS
            if (blockW == 0 || blockH == 0) return null

            FloatArray(EDGE_GRID_COLS * EDGE_GRID_ROWS) { idx ->
                val row = idx / EDGE_GRID_COLS
                val col = idx % EDGE_GRID_COLS
                val bx = col * blockW
                val by = row * blockH

                var maxMag = EDGE_MIN_GRADIENT
                for (py in by until by + blockH) {
                    for (px in bx until bx + blockW) {
                        val m = mag[py * W + px]
                        if (m > maxMag) maxMag = m
                    }
                }
                val threshold = maxMag * EDGE_REL_FACTOR

                var edgeCount = 0
                val total = blockW * blockH
                for (py in by until by + blockH) {
                    for (px in bx until bx + blockW) {
                        if (mag[py * W + px] > threshold) edgeCount++
                    }
                }
                edgeCount.toFloat() / total
            }
        } catch (e: Exception) {
            Timber.e(e, "Edge density calculation failed")
            null
        }
    }

    /**
     * Computes HOG (Histogram of Oriented Gradients) per block in a 32×32 grid.
     * Must match server src/lib/phash.js _computeEdgeHOG.
     * @return FloatArray of 8192 values (1024 blocks × 8 bins), or null on error.
     */
    fun calculateEdgeHOG(imageBytes: ByteArray): FloatArray? {
        return try {
            var bmp = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size) ?: return null

            val maxDim = maxOf(bmp.width, bmp.height)
            if (maxDim > EDGE_MAX_DIM) {
                val scale = EDGE_MAX_DIM.toFloat() / maxDim
                val newW = (bmp.width * scale).toInt().coerceAtLeast(1)
                val newH = (bmp.height * scale).toInt().coerceAtLeast(1)
                val small = Bitmap.createScaledBitmap(bmp, newW, newH, true)
                bmp.recycle()
                bmp = small
            }

            val W = bmp.width
            val H = bmp.height

            // Integer grayscale: floor(0.299·R + 0.587·G + 0.114·B)
            val grayRaw = FloatArray(W * H)
            for (y in 0 until H) {
                for (x in 0 until W) {
                    val c = bmp.getPixel(x, y)
                    grayRaw[y * W + x] = (
                        0.299f * ((c shr 16) and 0xFF) +
                        0.587f * ((c shr 8)  and 0xFF) +
                        0.114f * (c and 0xFF)
                    ).toInt().toFloat()
                }
            }
            bmp.recycle()

            val gray = gaussianBlur(grayRaw, W, H)

            val gxArr = FloatArray(W * H)
            val gyArr = FloatArray(W * H)
            for (y in 1 until H - 1) {
                for (x in 1 until W - 1) {
                    val tl = gray[(y-1)*W+(x-1)]; val tc = gray[(y-1)*W+x]; val tr = gray[(y-1)*W+(x+1)]
                    val ml = gray[y*W+(x-1)];                                 val mr = gray[y*W+(x+1)]
                    val bl = gray[(y+1)*W+(x-1)]; val bc = gray[(y+1)*W+x]; val br = gray[(y+1)*W+(x+1)]
                    gxArr[y * W + x] = -tl + tr - 2f*ml + 2f*mr - bl + br
                    gyArr[y * W + x] = -tl - 2f*tc - tr + bl + 2f*bc + br
                }
            }

            val blockW = W / EDGE_GRID_COLS
            val blockH = H / EDGE_GRID_ROWS
            if (blockW == 0 || blockH == 0) return null

            val result = FloatArray(EDGE_GRID_COLS * EDGE_GRID_ROWS * 8)
            val RAD_TO_DEG = 180.0 / Math.PI

            for (idx in 0 until EDGE_GRID_COLS * EDGE_GRID_ROWS) {
                val row = idx / EDGE_GRID_COLS
                val col = idx % EDGE_GRID_COLS
                val bx = col * blockW
                val by = row * blockH

                val hist = FloatArray(8)
                var total = 0

                for (py in by until by + blockH) {
                    for (px in bx until bx + blockW) {
                        val gx = gxArr[py * W + px]
                        val gy = gyArr[py * W + px]
                        val mag = kotlin.math.sqrt((gx*gx + gy*gy).toDouble()).toFloat()
                        if (mag >= EDGE_MIN_GRADIENT) {
                            val dir = kotlin.math.atan2(gy.toDouble(), gx.toDouble()) * RAD_TO_DEG
                            val bin = ((dir + 360.0) % 360.0 / 45.0).toInt().coerceIn(0, 7)
                            hist[bin]++
                            total++
                        }
                    }
                }

                val base = idx * 8
                if (total > 0) {
                    for (b in 0 until 8) {
                        result[base + b] = Math.round(hist[b] / total * 1000f) / 1000f
                    }
                }
            }

            result
        } catch (e: Exception) {
            Timber.e(e, "Edge HOG calculation failed")
            null
        }
    }

    /**
     * Computes per-block pixel standard deviation for texture tamper detection.
     * Must match server src/lib/phash.js _computeEdgeStdDev.
     * @return FloatArray of 1024 values (32×32 grid), or null on error.
     */
    fun calculateEdgeStdDev(imageBytes: ByteArray): FloatArray? {
        return try {
            var bmp = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size) ?: return null

            val maxDim = maxOf(bmp.width, bmp.height)
            if (maxDim > EDGE_MAX_DIM) {
                val scale = EDGE_MAX_DIM.toFloat() / maxDim
                val newW = (bmp.width * scale).toInt().coerceAtLeast(1)
                val newH = (bmp.height * scale).toInt().coerceAtLeast(1)
                val small = Bitmap.createScaledBitmap(bmp, newW, newH, true)
                bmp.recycle()
                bmp = small
            }

            val W = bmp.width
            val H = bmp.height

            val grayRaw = FloatArray(W * H)
            for (y in 0 until H) {
                for (x in 0 until W) {
                    val c = bmp.getPixel(x, y)
                    grayRaw[y * W + x] =
                        0.299f * ((c shr 16) and 0xFF) +
                        0.587f * ((c shr 8)  and 0xFF) +
                        0.114f * (c and 0xFF)
                }
            }
            bmp.recycle()

            val gray = gaussianBlur(grayRaw, W, H)

            val blockW = W / EDGE_GRID_COLS
            val blockH = H / EDGE_GRID_ROWS
            if (blockW == 0 || blockH == 0) return null

            FloatArray(EDGE_GRID_COLS * EDGE_GRID_ROWS) { idx ->
                val row = idx / EDGE_GRID_COLS
                val col = idx % EDGE_GRID_COLS
                val bx = col * blockW
                val by = row * blockH
                val n = blockW * blockH

                var sum = 0f
                for (py in by until by + blockH)
                    for (px in bx until bx + blockW)
                        sum += gray[py * W + px]
                val mean = sum / n

                var variance = 0f
                for (py in by until by + blockH)
                    for (px in bx until bx + blockW) {
                        val diff = gray[py * W + px] - mean
                        variance += diff * diff
                    }
                val stddev = kotlin.math.sqrt((variance / n).toDouble()).toFloat()
                Math.round(stddev * 1000f) / 1000f
            }
        } catch (e: Exception) {
            Timber.e(e, "Edge stddev calculation failed")
            null
        }
    }

    // ---------------------------------------------------------------------------
    // Free extend (beta — no payment)
    // ---------------------------------------------------------------------------

    fun freeExtend(authId: String): Long? {
        val url = "https://truesnap-production.up.railway.app/api/extend/free/$authId"
        val client = OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .build()
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $API_KEY")
            .post(ByteArray(0).toRequestBody("application/json".toMediaType()))
            .build()
        return try {
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                Timber.w("freeExtend HTTP %d for authId=%s", response.code, authId)
                return null
            }
            val body = response.body?.string() ?: return null
            val json = org.json.JSONObject(body)
            if (!json.optBoolean("success", false)) return null
            val expiresAt = json.optString("expiresAt").takeIf { it.isNotBlank() } ?: return null
            java.time.Instant.parse(expiresAt).toEpochMilli()
        } catch (e: Exception) {
            Timber.e(e, "freeExtend failed for authId=%s", authId)
            null
        }
    }

    private fun uploadNetworkError(e: Throwable): String = when (e) {
        is java.net.SocketTimeoutException          -> "서버 응답 시간이 초과됐습니다. 재시도해주세요."
        is java.net.ConnectException                -> "서버에 연결할 수 없습니다. 인터넷 연결을 확인해주세요."
        is java.net.UnknownHostException            -> "인터넷 연결을 확인해주세요."
        is javax.net.ssl.SSLPeerUnverifiedException -> "보안 인증서 검증에 실패했습니다. 앱을 최신 버전으로 업데이트해주세요."
        is javax.net.ssl.SSLException               -> "보안 연결 오류가 발생했습니다."
        else                                        -> "네트워크 오류가 발생했습니다."
    }

    private fun uploadHttpErrorMessage(code: Int): String = when {
        code == 401       -> "인증 오류가 발생했습니다. 앱을 최신 버전으로 업데이트해주세요."
        code == 409       -> "이미 등록된 인증 ID입니다."
        code == 429       -> "요청이 너무 많습니다. 잠시 후 다시 시도해주세요."
        code in 500..599  -> "서버 오류가 발생했습니다. 잠시 후 다시 시도해주세요."
        else              -> "전송에 실패했습니다. 잠시 후 다시 시도해주세요."
    }

    // ---------------------------------------------------------------------------
    // Image upload (kept for future use — not called in current pipeline)
    // ---------------------------------------------------------------------------

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
                Timber.d("Certification upload success: authId=%s sha256=%s",
                    request.authId, request.sha256Hash)
                CertificationResult.Success(request.authId, request.sha256Hash, responseBody)
            } else {
                val msg = "HTTP ${response.code}: $responseBody"
                Timber.w("Certification upload failed: %s", msg)
                CertificationResult.Failure(request.authId, request.sha256Hash, msg)
            }
        } catch (e: Exception) {
            Timber.e(e, "Certification upload exception")
            CertificationResult.Failure(request.authId, request.sha256Hash,
                e.message ?: "Network error")
        }
    }
}
