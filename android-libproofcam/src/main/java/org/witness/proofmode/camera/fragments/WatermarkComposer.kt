package org.witness.proofmode.camera.fragments

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

object WatermarkComposer {

    const val VERIFY_BASE_URL = "https://truesnap-production.up.railway.app/verify"

    /**
     * Strip layout (left → right):
     *   ┌──────────────────────────────────────────────────┬──────────┐
     *   │ TrueSnap  (green, bold)                          │          │
     *   │ TS-XXXXXX  (white, large monospace)              │  QR code │
     *   │ TrueSnap 진위확인 가능 사진                      │          │
     *   │ 앱·웹(truesnap.app)에서 코드로 직접 확인하세요   │          │
     *   └──────────────────────────────────────────────────┴──────────┘
     *
     * Strip height ≈ 13 % of total output image (= 15 % of original photo height).
     * Auth ID and guidance lines are sized so a person can read and type them by hand —
     * neither is made smaller than the QR's visual footprint permits.
     */
    fun compose(photo: Bitmap, authId: String): Bitmap {
        val W = photo.width
        val H = photo.height

        // Strip ≈ 13 % of total image height (stripH / (H + stripH) ≈ 0.13)
        val stripH = (H * 0.15f).toInt().coerceIn(180, 600)
        val pad    = (stripH * 0.07f).toInt().coerceAtLeast(8)

        // QR code: square, fills the strip minus top/bottom padding
        val qrSize  = stripH - 2 * pad
        val qrBmp   = generateQrBitmap("$VERIFY_BASE_URL/$authId", qrSize)
        val qrBgPad = maxOf(4, qrSize / 28)

        val result = Bitmap.createBitmap(W, H + stripH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)

        // ── Photo ─────────────────────────────────────────────────────────────
        canvas.drawBitmap(photo, 0f, 0f, null)

        // ── Strip background ──────────────────────────────────────────────────
        canvas.drawRect(
            0f, H.toFloat(), W.toFloat(), (H + stripH).toFloat(),
            Paint().apply { color = Color.parseColor("#0D0D0D") }
        )

        // ── QR code (right side) ──────────────────────────────────────────────
        val qrL = W - pad - qrBgPad - qrSize
        val qrT = H + pad
        canvas.drawRect(
            (qrL - qrBgPad).toFloat(), (qrT - qrBgPad).toFloat(),
            (qrL + qrSize + qrBgPad).toFloat(), (qrT + qrSize + qrBgPad).toFloat(),
            Paint().apply { color = Color.WHITE }
        )
        canvas.drawBitmap(qrBmp, qrL.toFloat(), qrT.toFloat(), null)

        // ── Left content (logo + authId + two guide lines) ────────────────────
        val contentL = pad.toFloat()
        val contentR = (qrL - qrBgPad - pad).toFloat()
        val contentW = contentR - contentL

        // Vertical space available for all text rows
        val availH = (stripH - 2 * pad).toFloat()

        // Row heights as proportions of availH:
        //   13.5 % label + 40.5 % authId + 19.5 % guide × 2 + gaps ≈ 97.8 %
        val labelSize  = availH * 0.135f
        val authIdSize = availH * 0.405f
        val guideSize  = availH * 0.195f
        val gap12 = availH * 0.018f   // between label and authId
        val gap23 = availH * 0.018f   // between authId and guide1
        val gap34 = availH * 0.012f   // between guide1 and guide2

        val topY           = H.toFloat() + pad
        val labelBaseline  = topY + labelSize
        val authIdBaseline = labelBaseline + gap12 + authIdSize
        val guide1Baseline = authIdBaseline + gap23 + guideSize
        val guide2Baseline = guide1Baseline + gap34 + guideSize

        // "TrueSnap" brand label
        canvas.drawText(
            "TrueSnap", contentL, labelBaseline,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color    = Color.parseColor("#4CAF82")
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                textSize = labelSize
            }
        )

        // Auth ID — dominant readable element; scale down only if it overflows
        val authIdPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color          = Color.WHITE
            typeface       = Typeface.MONOSPACE
            isFakeBoldText = true
            textSize       = authIdSize
        }
        val authIdMeasured = authIdPaint.measureText(authId)
        if (authIdMeasured > contentW) {
            authIdPaint.textSize = maxOf(authIdPaint.textSize * contentW / authIdMeasured, 24f)
        }
        canvas.drawText(authId, contentL, authIdBaseline, authIdPaint)

        // Guidance lines — scale both together so the longer one fits contentW
        val guide1 = "TrueSnap 진위확인 가능 사진"
        val guide2 = "앱·웹(truesnap.app)에서 코드로 직접 확인하세요"
        val guidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color    = Color.parseColor("#CCCCCC")
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            textSize = guideSize
        }
        val maxGuideW = maxOf(guidePaint.measureText(guide1), guidePaint.measureText(guide2))
        if (maxGuideW > contentW) {
            guidePaint.textSize = maxOf(guidePaint.textSize * contentW / maxGuideW, 18f)
        }
        canvas.drawText(guide1, contentL, guide1Baseline, guidePaint)
        canvas.drawText(guide2, contentL, guide2Baseline, guidePaint)

        return result
    }

    // ── QR generation ────────────────────────────────────────────────────────

    private fun generateQrBitmap(content: String, size: Int): Bitmap {
        val hints = mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to 1
        )
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size, hints)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        for (x in 0 until size) {
            for (y in 0 until size) {
                bmp.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
            }
        }
        return bmp
    }
}
