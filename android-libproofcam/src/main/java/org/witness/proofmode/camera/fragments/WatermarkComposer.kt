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
     * 3-column strip (left → right):
     *   LEFT  (22 %): "TrueSnap" — white, bold, large
     *   CENTER(50 %): two guide lines — white, small
     *   RIGHT (28 %): auth-ID text (top 22 %) + QR code (below)
     *
     * Background #0A0E1A (deep navy) · strip ≈ 13 % of total height (15 % of photo height)
     *
     *   ┌──────────────────┬────────────────────────────────────┬──────────────────────────┐
     *   │                  │  TrueSnap 진위확인 가능 사진        │  TS-XXXXXX               │
     *   │   TrueSnap       │  truesnap.app 에서 코드로 직접      │  ┌────────────────────┐  │
     *   │  (white, bold)   │  확인하세요  (white, small)         │  │      QR code       │  │
     *   │                  │                                    │  └────────────────────┘  │
     *   └──────────────────┴────────────────────────────────────┴──────────────────────────┘
     */
    fun compose(photo: Bitmap, authId: String): Bitmap {
        val W = photo.width
        val H = photo.height

        val stripH = (H * 0.15f).toInt().coerceIn(180, 600)
        val pad    = (stripH * 0.07f).toInt().coerceAtLeast(8)

        // Column widths
        val leftColW  = (W * 0.22f).toInt()
        val rightColW = (W * 0.28f).toInt()

        // Right column: authId text occupies top 22 %, QR fills the rest
        val authIdRowH = (stripH * 0.22f).toInt()
        val qrAvailH   = stripH - authIdRowH - 2 * pad
        val qrSize     = minOf(qrAvailH, rightColW - 2 * pad).coerceAtLeast(32)
        val qrBgPad    = maxOf(3, qrSize / 32)
        val qrBmp      = generateQrBitmap("$VERIFY_BASE_URL/$authId", qrSize)

        val result = Bitmap.createBitmap(W, H + stripH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)

        canvas.drawBitmap(photo, 0f, 0f, null)

        // Strip background — deep navy
        canvas.drawRect(0f, H.toFloat(), W.toFloat(), (H + stripH).toFloat(),
            Paint().apply { color = Color.parseColor("#0A0E1A") })

        val stripTop     = H.toFloat()
        val stripCenterY = stripTop + stripH * 0.5f

        // ── LEFT: TrueSnap brand (white, bold, large) ───────────────────────────
        val brandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color    = Color.WHITE
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textSize = (stripH * 0.32f).coerceIn(24f, 120f)
        }
        val brandMaxW = (leftColW - 2 * pad).toFloat()
        val brandMeasured = brandPaint.measureText("TrueSnap")
        if (brandMeasured > brandMaxW) {
            brandPaint.textSize = brandPaint.textSize * brandMaxW / brandMeasured
        }
        canvas.drawText("TrueSnap",
            pad.toFloat(),
            stripCenterY + brandPaint.textSize * 0.35f,
            brandPaint)

        // ── CENTER: guide lines (white, small) ──────────────────────────────────
        val guide1 = "TrueSnap 진위확인 가능 사진"
        val guide2 = "truesnap.app 에서 코드로 직접 확인하세요"
        val guidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color    = Color.WHITE
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            textSize = (stripH * 0.15f).coerceIn(16f, 56f)
        }
        val centerAvailW = (W - leftColW - rightColW - 3 * pad).toFloat()
        val maxGuideW = maxOf(guidePaint.measureText(guide1), guidePaint.measureText(guide2))
        if (maxGuideW > centerAvailW) {
            guidePaint.textSize = guidePaint.textSize * centerAvailW / maxGuideW
        }
        val lineStep = guidePaint.textSize * 1.45f
        val g1Y = stripCenterY - lineStep * 0.3f
        val g2Y = g1Y + lineStep
        val centerX = (leftColW + pad).toFloat()
        canvas.drawText(guide1, centerX, g1Y, guidePaint)
        canvas.drawText(guide2, centerX, g2Y, guidePaint)

        // ── RIGHT: auth-ID text (medium, white monospace) ───────────────────────
        val rightColL = (W - rightColW).toFloat()
        val authIdPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color          = Color.WHITE
            typeface       = Typeface.MONOSPACE
            isFakeBoldText = true
            textSize       = (authIdRowH * 0.68f).coerceIn(12f, 60f)
        }
        val authIdMaxW = (rightColW - 2 * pad).toFloat()
        val authIdMeasured = authIdPaint.measureText(authId)
        if (authIdMeasured > authIdMaxW) {
            authIdPaint.textSize = maxOf(authIdPaint.textSize * authIdMaxW / authIdMeasured, 12f)
        }
        canvas.drawText(authId,
            rightColL + pad,
            stripTop + pad + authIdPaint.textSize,
            authIdPaint)

        // ── RIGHT: QR code (centered in remaining space) ────────────────────────
        val qrLeft = (rightColL + (rightColW - qrSize) / 2f).toInt()
        val qrTop  = H + authIdRowH + ((stripH - authIdRowH - qrSize) / 2).coerceAtLeast(pad / 2)
        canvas.drawRect(
            (qrLeft - qrBgPad).toFloat(), (qrTop - qrBgPad).toFloat(),
            (qrLeft + qrSize + qrBgPad).toFloat(), (qrTop + qrSize + qrBgPad).toFloat(),
            Paint().apply { color = Color.WHITE })
        canvas.drawBitmap(qrBmp, qrLeft.toFloat(), qrTop.toFloat(), null)

        return result
    }

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
