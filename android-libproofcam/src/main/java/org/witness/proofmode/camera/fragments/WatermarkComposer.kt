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

    const val VERIFY_BASE_URL = "https://YOUR_SERVER_URL.com/verify"

    /**
     * Composites [photo] with a branded certification strip at the bottom.
     *
     * Strip layout (left → right):
     *   ┌──────────────────────────────────┬──────────┐
     *   │ TrueSnap                         │          │
     *   │ TS-XXXXXX  ← large mono text     │ QR code  │
     *   └──────────────────────────────────┴──────────┘
     *
     * Auth ID text is sized to be at least as tall as the QR code so it
     * can be read and typed by hand — the primary verification path.
     */
    fun compose(photo: Bitmap, authId: String): Bitmap {
        val W = photo.width
        val H = photo.height

        // Strip height: 25 % of image width, clamped so it stays reasonable
        val stripH = (W * 0.25f).toInt().coerceIn(220, 480)
        val pad    = (stripH * 0.07f).toInt()

        // QR code (square, fits inside the strip with padding on all sides)
        val qrSize = stripH - 2 * pad
        val qrBmp  = generateQrBitmap("$VERIFY_BASE_URL/$authId", qrSize)

        // Result canvas
        val result = Bitmap.createBitmap(W, H + stripH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)

        // ── Photo ──────────────────────────────────────────────────────────
        canvas.drawBitmap(photo, 0f, 0f, null)

        // ── Strip background ───────────────────────────────────────────────
        canvas.drawRect(
            0f, H.toFloat(), W.toFloat(), (H + stripH).toFloat(),
            Paint().apply { color = Color.parseColor("#0D0D0D") }
        )

        // ── QR code (right side) ────────────────────────────────────────────
        val qrL = W - pad - qrSize
        val qrT = H + pad

        // White backing so dark-module QR is always scannable
        val qrBgPad = maxOf(4, qrSize / 28)
        canvas.drawRect(
            (qrL - qrBgPad).toFloat(), (qrT - qrBgPad).toFloat(),
            (qrL + qrSize + qrBgPad).toFloat(), (qrT + qrSize + qrBgPad).toFloat(),
            Paint().apply { color = Color.WHITE }
        )
        canvas.drawBitmap(qrBmp, qrL.toFloat(), qrT.toFloat(), null)

        // ── Left content area ───────────────────────────────────────────────
        val contentL = pad.toFloat()
        val contentW = (qrL - 2 * pad).toFloat()   // gap on both sides of QR

        // "TrueSnap" label (small, AccentGreen)
        val labelSize = stripH * 0.15f
        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#4CAF82")
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textSize = labelSize
        }
        // Baseline: top-of-strip + padding + text ascent
        val labelBaseline = H + pad + labelSize
        canvas.drawText("TrueSnap", contentL, labelBaseline, labelPaint)

        // Auth ID — maximise size so it fills the remaining strip height,
        // then scale down until it fits the content width.
        val authIdAvailH = stripH - pad - labelSize - pad.toFloat() * 0.3f
        val authIdPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            typeface = Typeface.MONOSPACE
            isFakeBoldText = true
            textSize = authIdAvailH * 0.88f   // start large
        }
        while (authIdPaint.measureText(authId) > contentW && authIdPaint.textSize > 24f) {
            authIdPaint.textSize -= 2f
        }
        // Baseline at bottom of strip minus padding
        val authIdBaseline = (H + stripH - pad).toFloat()
        canvas.drawText(authId, contentL, authIdBaseline, authIdPaint)

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
