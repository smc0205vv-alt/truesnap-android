package org.witness.proofmode.verify

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import org.witness.proofmode.R
import com.google.zxing.BarcodeFormat
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber

data class VerifyUiState(
    val imageUri: Uri?                          = null,
    // QR decode
    val qrDecoding: Boolean                     = false,
    val authId: String?                         = null,
    val qrError: String?                        = null,
    // Manual auth ID input (shown when QR decode fails)
    val manualAuthIdInput: String               = "",
    // Stage 1 — registration lookup
    val lookupLoading: Boolean                  = false,
    val lookupResult: VerificationService.LookupResult? = null,
    val lookupError: String?                    = null,
    // Stage 2 — tamper comparison
    val compareLoading: Boolean                 = false,
    val compareResult: VerificationService.CompareResult? = null,
    val compareError: String?                   = null
)

// ═══════════════════════════════════════════════════════════════════════════════
// SECURITY BOUNDARY — VERIFICATION PIPELINE ONLY
//
// This ViewModel is exclusively for the buyer-facing verification flow.
// It NEVER references CameraViewModel, CertificationService, or any
// certification-pipeline state (lastCapturedMedia, _certificationState, etc.).
//
// Data flow:  external image (gallery / ACTION_SEND intent)
//               → VerifyUiState.imageUri          (state isolated here)
//               → VerificationService.lookupRegistration()  (GET — read-only lookup)
//               → VerificationService.compareImage()        (POST — comparison only)
//
// CertificationService.uploadMetadata() is NEVER called from this class.
// There is no code path from an externally-shared image to the certification
// registration endpoint.
// ═══════════════════════════════════════════════════════════════════════════════
class VerifyViewModel(application: android.app.Application) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(VerifyUiState())
    val state: StateFlow<VerifyUiState> = _state

    // Entry point for both gallery-pick and ACTION_SEND share.
    // Feeds processImage() → VerificationService only; never touches CameraViewModel.
    fun onImageSelected(context: Context, uri: Uri) {
        _state.value = VerifyUiState(imageUri = uri, qrDecoding = true)
        viewModelScope.launch(Dispatchers.IO) {
            processImage(context, uri)
        }
    }

    fun onManualAuthIdChanged(text: String) {
        _state.update { it.copy(manualAuthIdInput = text) }
    }

    fun onManualAuthIdSubmit(context: Context) {
        val authId = _state.value.manualAuthIdInput.trim().uppercase()
        val uri = _state.value.imageUri ?: return
        if (authId.isBlank()) return

        _state.update { it.copy(
            authId        = authId,
            lookupLoading = true,
            compareLoading = true
        )}

        viewModelScope.launch(Dispatchers.IO) {
            val imageBytes: ByteArray? = try {
                context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            } catch (e: Exception) {
                Timber.e(e, "Failed to read image bytes for manual compare")
                null
            }
            coroutineScope {
                launch { runLookup(authId) }
                launch { runCompare(authId, imageBytes) }
            }
        }
    }

    private suspend fun processImage(context: Context, uri: Uri) {
        // ── QR decode ──────────────────────────────────────────────────────
        val bmp: Bitmap? = try {
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
        } catch (e: Exception) {
            Timber.e(e, "Failed to open image for QR decode")
            null
        }
        if (bmp == null) {
            _state.update { it.copy(qrDecoding = false, qrError = getApplication<android.app.Application>().getString(R.string.verify_error_cannot_open)) }
            return
        }
        val authId = decodeQrFromBitmap(bmp)
        bmp.recycle()

        if (authId == null) {
            _state.update { it.copy(qrDecoding = false, qrError = getApplication<android.app.Application>().getString(R.string.verify_error_qr_not_found)) }
            return
        }

        // ── Launch Stage 1 + Stage 2 in parallel ───────────────────────────
        _state.update { it.copy(qrDecoding = false, authId = authId, lookupLoading = true, compareLoading = true) }

        val imageBytes: ByteArray? = try {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        } catch (e: Exception) {
            Timber.e(e, "Failed to read image bytes for compare")
            null
        }

        coroutineScope {
            launch { runLookup(authId) }
            launch { runCompare(authId, imageBytes) }
        }
    }

    private suspend fun runLookup(authId: String) {
        val result = VerificationService(getApplication()).lookupRegistration(authId)
        _state.update { it.copy(
            lookupLoading = false,
            lookupResult  = result.getOrNull(),
            lookupError   = result.exceptionOrNull()?.message?.take(120)
        )}
    }

    private suspend fun runCompare(authId: String, imageBytes: ByteArray?) {
        if (imageBytes == null) {
            _state.update { it.copy(compareLoading = false, compareError = getApplication<android.app.Application>().getString(R.string.verify_error_cannot_read)) }
            return
        }
        val result = VerificationService(getApplication()).compareImage(authId, imageBytes)
        _state.update { it.copy(
            compareLoading = false,
            compareResult  = result.getOrNull(),
            compareError   = result.exceptionOrNull()?.message?.take(120)
        )}
    }

    // ── QR decoding ─────────────────────────────────────────────────────────

    private val decodeHints = mapOf(
        DecodeHintType.TRY_HARDER        to true,
        DecodeHintType.POSSIBLE_FORMATS  to listOf(BarcodeFormat.QR_CODE)
    )

    /**
     * Tries to decode a QR code from [original] at multiple scales so that
     * both thumbnail-sized screenshots and full-resolution images work.
     * Returns the extracted auth ID ("TS-XXXXXX"), or null if no QR found.
     */
    private fun decodeQrFromBitmap(original: Bitmap): String? {
        for (scale in listOf(1.0f, 0.5f, 0.25f)) {
            val bmp = if (scale == 1.0f) original else
                Bitmap.createScaledBitmap(
                    original,
                    (original.width  * scale).toInt().coerceAtLeast(1),
                    (original.height * scale).toInt().coerceAtLeast(1),
                    true
                )
            try {
                val pixels = IntArray(bmp.width * bmp.height)
                bmp.getPixels(pixels, 0, bmp.width, 0, 0, bmp.width, bmp.height)
                val source = RGBLuminanceSource(bmp.width, bmp.height, pixels)
                val binary = com.google.zxing.BinaryBitmap(HybridBinarizer(source))
                val text   = MultiFormatReader().decode(binary, decodeHints).text
                if (scale != 1.0f) bmp.recycle()
                return extractAuthId(text)
            } catch (_: NotFoundException) {
                if (scale != 1.0f) bmp.recycle()
            } catch (e: Exception) {
                Timber.w(e, "QR decode error at scale %.1f", scale)
                if (scale != 1.0f) bmp.recycle()
            }
        }
        return null
    }

    /** Extracts "TS-XXXXXX" from a full verify URL or from a bare auth-ID string. */
    private fun extractAuthId(qrText: String): String? {
        val text = qrText.trim().trimEnd('/')
        if (text.startsWith("TS-")) return text
        val segment = text.substringAfterLast('/')
        return if (segment.startsWith("TS-") && segment.length >= 9) segment else null
    }
}
