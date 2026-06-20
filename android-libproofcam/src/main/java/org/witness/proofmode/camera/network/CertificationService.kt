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

        /** Crop-hash registration endpoint — receives multipart image, server recomputes hashes. */
        const val REGISTER_CROP_BASE_URL = "https://truesnap-production.up.railway.app/api/register-crop"

        /** Shared secret injected at compile time from local.properties via BuildConfig. */
        private val API_KEY: String get() = BuildConfig.TRUESNAP_API_KEY

        private val AUTH_ID_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        private const val AUTH_ID_LENGTH = 6
        private const val CONNECT_TIMEOUT_MS = 30_000L
        private const val READ_TIMEOUT_MS   = 60_000L

        private const val PHASH_SIZE = 8
        private const val PHASH_IMG_SIZE = PHASH_SIZE * 4  // 32

        /** Pixel dimensions for the registration thumbnail (32×32 PNG, base64-encoded). */
        private const val THUMBNAIL_SIZE = 32

        /** Fixed block grid for crop-region hashes. Must match server hash_image.py. */
        const val BLOCK_GRID_COLS = 16
        const val BLOCK_GRID_ROWS = 16

        // Edge density constants — must match server src/lib/phash.js
        private const val EDGE_MAX_DIM      = 1024     // downsample to this max dimension
        private const val EDGE_REL_FACTOR   = 0.25f    // threshold = blockMax × EDGE_REL_FACTOR
        private const val EDGE_MIN_GRADIENT = 10f      // absolute floor for uniform blocks
        private const val EDGE_GRID_COLS    = 32       // independent of pHash block grid (16×16)
        private const val EDGE_GRID_ROWS    = 32

        // Gaussian pre-blur constants — must match server src/lib/phash.js
        private const val GAUSSIAN_KERNEL_SIZE = 5     // 5×5 separable kernel
        private const val GAUSSIAN_SIGMA       = 1.0f  // σ in pixels
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
     *   lofi_thumbnail       — base64 PNG of the watermarked image downscaled to 32×32 (for verify display)
     */
    data class MetadataUploadRequest(
        val authId: String,
        val sha256Hash: String,
        val pHash: String?,
        val captureTimestampMs: Long,
        val nickname: String,
        val lofiThumbnailBase64: String? = null,
        val cropHashes: List<String>? = null,
        val edgeDensities: FloatArray? = null,
        val edgeHog: FloatArray? = null,
        val edgeStdDev: FloatArray? = null
    )

    sealed class MetadataUploadResult {
        data class Success(val authId: String) : MetadataUploadResult()
        data class Failure(val authId: String, val error: String) : MetadataUploadResult()
    }

    // ---------------------------------------------------------------------------
    // Core utilities
    // ---------------------------------------------------------------------------

    /**
     * Downscales [bitmap] to [THUMBNAIL_SIZE]×[THUMBNAIL_SIZE] pixels (bilinear) and returns it
     * as a base64-encoded JPEG (quality=60) string. Called on the watermarked final image immediately
     * after watermark composition — the result is stored server-side for the verify display.
     */
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

    /** @deprecated Use [generateThumbnail32] instead — kept for call sites not yet migrated. */
    fun generateLofiThumbnail(bitmap: Bitmap): String? = generateThumbnail32(bitmap)

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
     * Computes a DCT perceptual hash compatible with Python imagehash.phash().
     *
     * Algorithm (matches imagehash defaults: hash_size=8, highfreq_factor=4):
     *   1. Decode JPEG → grayscale 32×32 (ITU-R 601-2 luma)
     *   2. 2D DCT Type II (unnormalized, same as scipy.fftpack.dct default)
     *   3. Top-left 8×8 low-frequency block
     *   4. Median threshold → 64-bit binary hash
     *   5. Return as 16-char hex string (same format as imagehash str())
     *
     * @return 16-char hex string, or null on decode/compute error.
     */
    fun calculatePHash(imageBytes: ByteArray): String? {
        return try {
            val bmp = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size) ?: return null
            val n = PHASH_IMG_SIZE  // 32
            val scaled = Bitmap.createScaledBitmap(bmp, n, n, true)
            bmp.recycle()

            // Grayscale pixel values as doubles
            val pixels = DoubleArray(n * n)
            for (y in 0 until n) {
                for (x in 0 until n) {
                    val c = scaled.getPixel(x, y)
                    val r = (c shr 16) and 0xFF
                    val g = (c shr 8) and 0xFF
                    val b = c and 0xFF
                    pixels[y * n + x] = 0.299 * r + 0.587 * g + 0.114 * b
                }
            }
            scaled.recycle()

            val dct = dct2d(pixels, n)

            // Extract top-left PHASH_SIZE × PHASH_SIZE
            val h = PHASH_SIZE
            val lowFreq = DoubleArray(h * h) { dct[(it / h) * n + (it % h)] }

            // Median threshold
            val sorted = lowFreq.copyOf().also { it.sort() }
            val med = (sorted[h * h / 2 - 1] + sorted[h * h / 2]) / 2.0

            // Build 64-bit value MSB-first (row-major), then hex
            var bits = 0L
            for (v in lowFreq) {
                bits = bits shl 1
                if (v > med) bits = bits or 1L
            }
            "%016x".format(bits).also { Timber.d("pHash calculated: %s", it) }
        } catch (e: Exception) {
            Timber.e(e, "pHash calculation failed")
            null
        }
    }

    private fun dct2d(m: DoubleArray, n: Int): DoubleArray {
        val tmp = DoubleArray(n * n)
        for (row in 0 until n) {
            val r = dct1d(DoubleArray(n) { m[row * n + it] })
            for (col in 0 until n) tmp[row * n + col] = r[col]
        }
        val out = DoubleArray(n * n)
        for (col in 0 until n) {
            val c = dct1d(DoubleArray(n) { tmp[it * n + col] })
            for (row in 0 until n) out[row * n + col] = c[row]
        }
        return out
    }

    private fun dct1d(x: DoubleArray): DoubleArray {
        val n = x.size
        return DoubleArray(n) { k ->
            var s = 0.0
            for (i in 0 until n) s += x[i] * Math.cos(Math.PI * k * (2 * i + 1) / (2.0 * n))
            2.0 * s
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
            if (!request.cropHashes.isNullOrEmpty()) {
                put("crop_hashes", org.json.JSONArray(request.cropHashes))
            }
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
                Timber.d("Metadata upload success: authId=%s", request.authId)
                MetadataUploadResult.Success(request.authId)
            } else {
                Timber.w("Metadata upload failed: HTTP %d: %s", response.code, body)
                MetadataUploadResult.Failure(request.authId, uploadHttpErrorMessage(response.code))
            }
        } catch (e: Exception) {
            Timber.e(e, "Metadata upload exception")
            MetadataUploadResult.Failure(request.authId, uploadNetworkError(e))
        }
    }

    /**
     * POSTs the watermarked JPEG to /api/register-crop/:authId so the server recomputes
     * block hashes and pHash via Python (same algorithm used during verification).
     * This overwrites the Android-computed hashes stored during metadata upload,
     * eliminating the Kotlin/Python DCT mismatch that causes false positives.
     *
     * Call this after [uploadMetadata] succeeds. Failure is non-fatal — the Android-computed
     * hashes remain in the DB and verification still works (with slightly higher noise floor).
     */
    fun registerCropHashes(authId: String, imageBytes: ByteArray): Boolean {
        val url = "$REGISTER_CROP_BASE_URL/$authId"
        val client = OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .build()
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("image", "watermarked.jpg",
                imageBytes.toRequestBody("image/jpeg".toMediaType()))
            .build()
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $API_KEY")
            .post(body)
            .build()
        return try {
            val response = client.newCall(request).execute()
            val ok = response.isSuccessful
            if (ok) Timber.d("registerCropHashes success: authId=%s", authId)
            else Timber.w("registerCropHashes failed: HTTP %d authId=%s", response.code, authId)
            ok
        } catch (e: Exception) {
            Timber.e(e, "registerCropHashes exception: authId=%s", authId)
            false
        }
    }

    /**
     * Divides [imageBytes] into a [BLOCK_GRID_COLS]×[BLOCK_GRID_ROWS] grid and computes
     * pHash for each block. Uses the same DCT algorithm and bilinear resize as [calculatePHash],
     * mirrored in Python (hash_image.py) so stored hashes are directly comparable to
     * hashes computed server-side during verification.
     *
     * @return List of [BLOCK_GRID_COLS * BLOCK_GRID_ROWS] 16-char hex strings, or null on error.
     */
    fun calculateBlockHashes(imageBytes: ByteArray): List<String>? {
        return try {
            val bmp = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size) ?: return null
            val W = bmp.width
            val H = bmp.height
            val blockW = W / BLOCK_GRID_COLS
            val blockH = H / BLOCK_GRID_ROWS
            if (blockW == 0 || blockH == 0) {
                bmp.recycle()
                return null
            }

            val n = PHASH_IMG_SIZE  // 32
            val h = PHASH_SIZE      // 8
            val hashes = ArrayList<String>(BLOCK_GRID_COLS * BLOCK_GRID_ROWS)

            for (row in 0 until BLOCK_GRID_ROWS) {
                for (col in 0 until BLOCK_GRID_COLS) {
                    val block = Bitmap.createBitmap(bmp, col * blockW, row * blockH, blockW, blockH)
                    val scaled = Bitmap.createScaledBitmap(block, n, n, true) // bilinear
                    block.recycle()

                    val pixels = DoubleArray(n * n)
                    for (py in 0 until n) {
                        for (px in 0 until n) {
                            val c = scaled.getPixel(px, py)
                            pixels[py * n + px] =
                                0.299 * ((c shr 16) and 0xFF) +
                                0.587 * ((c shr 8)  and 0xFF) +
                                0.114 * (c and 0xFF)
                        }
                    }
                    scaled.recycle()

                    val dct = dct2d(pixels, n)
                    val lowFreq = DoubleArray(h * h) { dct[(it / h) * n + (it % h)] }
                    val sorted = lowFreq.copyOf().also { it.sort() }
                    val med = (sorted[h * h / 2 - 1] + sorted[h * h / 2]) / 2.0

                    var bits = 0L
                    for (v in lowFreq) {
                        bits = bits shl 1
                        if (v > med) bits = bits or 1L
                    }
                    hashes.add("%016x".format(bits))
                }
            }

            bmp.recycle()
            hashes
        } catch (e: Exception) {
            Timber.e(e, "Block hash calculation failed")
            null
        }
    }

    /**
     * Separable Gaussian blur (GAUSSIAN_KERNEL_SIZE × GAUSSIAN_KERNEL_SIZE, σ = GAUSSIAN_SIGMA).
     * Border handling: clamp-to-edge (matches Node.js Math.max/Math.min logic).
     * Must match server src/lib/phash.js _gaussianBlur().
     */
    private fun gaussianBlur(gray: FloatArray, W: Int, H: Int): FloatArray {
        val half = GAUSSIAN_KERNEL_SIZE / 2
        // Build and normalize 1D kernel
        val kernel = FloatArray(GAUSSIAN_KERNEL_SIZE) { i ->
            val d = (i - half).toFloat()
            kotlin.math.exp(-(d * d / (2f * GAUSSIAN_SIGMA * GAUSSIAN_SIGMA)).toDouble()).toFloat()
        }
        val kernelSum = kernel.sum()
        for (i in kernel.indices) kernel[i] /= kernelSum

        val tmp = FloatArray(W * H)
        val out = FloatArray(W * H)

        // Horizontal pass: gray → tmp
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

        // Vertical pass: tmp → out
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

    /**
     * Computes Sobel edge density per block in a 32×32 grid.
     *
     * Algorithm (must match server src/lib/phash.js _computeEdgeFeatures):
     *   1. Downsample image to ≤ EDGE_MAX_DIM px (bilinear, same max-dim logic as Node.js)
     *   2. Convert to grayscale: 0.299·R + 0.587·G + 0.114·B
     *   3. Gaussian pre-blur 5×5, σ=1.0 (separable, clamp-to-edge)
     *   4. Sobel 3×3 gradient magnitude per pixel: sqrt(Gx²+Gy²)
     *   5. Per 32×32 block:
     *      - threshold = max(blockMaxGradient × 0.25, EDGE_MIN_GRADIENT)
     *      - density = fraction of pixels above threshold
     *
     * @return FloatArray of 1024 density values (0.0–1.0), or null on error.
     */
    fun calculateEdgeDensities(imageBytes: ByteArray): FloatArray? {
        return try {
            var bmp = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size) ?: return null

            // Downsample to ≤ EDGE_MAX_DIM (matches Node.js fit:'inside' logic)
            val maxDim = maxOf(bmp.width, bmp.height)
            if (maxDim > EDGE_MAX_DIM) {
                val scale = EDGE_MAX_DIM.toFloat() / maxDim
                val newW = (bmp.width * scale).toInt().coerceAtLeast(1)
                val newH = (bmp.height * scale).toInt().coerceAtLeast(1)
                val small = Bitmap.createScaledBitmap(bmp, newW, newH, true) // bilinear
                bmp.recycle()
                bmp = small
            }

            val W = bmp.width
            val H = bmp.height

            // Grayscale float array (ITU-R 601-2 luma, same coefficients as pHash)
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

            // Gaussian pre-blur (5×5, σ=GAUSSIAN_SIGMA) — reduces noise before Sobel
            val gray = gaussianBlur(grayRaw, W, H)

            // Sobel 3×3 gradient magnitude (borders remain 0)
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

            // Per-block edge density (32×32 grid, integer division)
            val blockW = W / EDGE_GRID_COLS
            val blockH = H / EDGE_GRID_ROWS
            if (blockW == 0 || blockH == 0) return null

            FloatArray(EDGE_GRID_COLS * EDGE_GRID_ROWS) { idx ->
                val row = idx / EDGE_GRID_COLS
                val col = idx % EDGE_GRID_COLS
                val bx = col * blockW
                val by = row * blockH

                // Local max for relative threshold (robust to global contrast changes)
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
     *
     * Algorithm (must match server src/lib/phash.js _computeEdgeHOG):
     *   1. Downsample to ≤ EDGE_MAX_DIM (bilinear)
     *   2. Integer grayscale: floor(0.299·R + 0.587·G + 0.114·B)
     *   3. Gaussian pre-blur 5×5, σ=1.0
     *   4. Sobel 3×3: Gx and Gy separately
     *   5. Per 32×32 block: 8-bin direction histogram for pixels with mag ≥ EDGE_MIN_GRADIENT=10
     *      bin = floor(((atan2(Gy,Gx)×180/π + 360) % 360) / 45), L1-normalized, rounded to 3dp
     *
     * @return FloatArray of 8192 values (1024 blocks × 8 bins), or null on error.
     */
    /**
     * Computes per-block pixel standard deviation for texture tamper detection.
     *
     * Algorithm (must match server src/lib/phash.js _computeEdgeStdDev):
     *   1. Downsample to ≤ EDGE_MAX_DIM (bilinear)
     *   2. Float grayscale: 0.299·R + 0.587·G + 0.114·B  (no floor — matches density)
     *   3. Gaussian pre-blur 5×5, σ=1.0
     *   4. Per 32×32 block: stddev = sqrt(Σ(x−mean)² / n), rounded to 3dp
     *
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

            // Float grayscale (same as density — no integer floor)
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

            // Sobel: Gx and Gy stored separately for direction computation
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
