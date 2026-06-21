package org.witness.proofmode.camera.fragments

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

object WatermarkComposer {

    const val VERIFY_BASE_URL = "https://truesnap-production.up.railway.app/verify"

    private val BG_COLOR   = Color.parseColor("#10141b")
    private val MINT       = Color.parseColor("#3ccfc2")
    private val DARK_INNER = Color.parseColor("#1a3b3a")
    private val DARK_RING  = Color.parseColor("#2b3744")
    private val DARK_CHECK = Color.parseColor("#0c1117")

    /**
     * Watermark strip layout (2 sections, both vertically centered at strip midpoint):
     *
     *   ┌───────────────────────────────────────────────────┬───────────────────────────┐
     *   │  [ICON]  True(white)Snap(mint)                    │   TS-XXXXXX  (mint)       │
     *   │  TrueSnap 실제 촬영 인증 사진                      │  ┌─────────────────────┐  │
     *   │  직접 찍은 사진인지, 수정됐는지                    │  │       QR code       │  │
     *   │  https://truesnap.app에서 확인하세요               │  └─────────────────────┘  │
     *   └───────────────────────────────────────────────────┴───────────────────────────┘
     *   Background: #10141b · "Snap" and authId in #3ccfc2 (mint)
     */
    fun compose(photo: Bitmap, authId: String): Bitmap {
        val W = photo.width
        val H = photo.height

        val stripH = (H * 0.15f).toInt().coerceIn(180, 600)
        val pad    = (stripH * 0.07f).toInt().coerceAtLeast(8)

        val rightColW = (W * 0.28f).toInt()

        val result = Bitmap.createBitmap(W, H + stripH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)

        canvas.drawBitmap(photo, 0f, 0f, null)

        // Strip background
        canvas.drawRect(0f, H.toFloat(), W.toFloat(), (H + stripH).toFloat(),
            Paint().apply { color = BG_COLOR })

        val stripMidY = H.toFloat() + stripH * 0.5f

        // ── Compute base sizes ───────────────────────────────────────────────
        val wordmarkSz = (stripH * 0.30f).coerceIn(20f, 100f)
        val guideSz    = (wordmarkSz * 0.44f).coerceIn(12f, 50f)
        val lineGap    = guideSz * 0.35f

        // ── Paints ───────────────────────────────────────────────────────────
        val boldTyp = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        val truePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color    = Color.WHITE
            typeface = boldTyp
            textSize = wordmarkSz
        }
        val snapPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color    = MINT
            typeface = boldTyp
            textSize = wordmarkSz
        }
        val guidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color    = Color.WHITE
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            textSize = guideSz
        }

        val guide1 = "TrueSnap 실제 촬영 인증 사진"
        val guide2 = "직접 찍은 사진인지, 수정됐는지 https://truesnap.app에서 확인하세요"

        // Available width for left area content
        val guideAvailW = (W - rightColW - 2 * pad).toFloat()

        // Scale guide text if it overflows
        val maxGW = maxOf(guidePaint.measureText(guide1), guidePaint.measureText(guide2))
        if (maxGW > guideAvailW) {
            guidePaint.textSize = guidePaint.textSize * guideAvailW / maxGW
        }

        // Scale wordmark (icon + True + Snap) if it overflows
        val iconR0     = wordmarkSz * 0.48f
        val iconGap0   = wordmarkSz * 0.14f
        val wLineW = iconR0 * 2 + iconGap0 + truePaint.measureText("True") + snapPaint.measureText("Snap")
        if (wLineW > guideAvailW) {
            val sc = guideAvailW / wLineW
            truePaint.textSize *= sc
            snapPaint.textSize *= sc
        }

        val actualWSz  = truePaint.textSize
        val actualIconR = actualWSz * 0.48f
        val actualIconGap = actualWSz * 0.14f

        // ── LEFT BLOCK vertical centering ────────────────────────────────────
        val totalBlockH = actualWSz + lineGap + guidePaint.textSize * 1.35f + guidePaint.textSize
        val blockTopY   = stripMidY - totalBlockH * 0.5f

        // Draw logo icon
        val iconCX = pad.toFloat() + actualIconR
        val iconCY = blockTopY + actualWSz * 0.5f
        drawLogoIcon(canvas, iconCX, iconCY, actualIconR)

        // Draw wordmark: True (white) + Snap (mint)
        val wordmarkX    = iconCX + actualIconR + actualIconGap
        val wordBaseline = blockTopY + actualWSz * 0.82f
        val trueW = truePaint.measureText("True")
        canvas.drawText("True", wordmarkX, wordBaseline, truePaint)
        canvas.drawText("Snap", wordmarkX + trueW, wordBaseline, snapPaint)

        // Draw guide lines
        val g1Y = blockTopY + actualWSz + lineGap + guidePaint.textSize
        val g2Y = g1Y + guidePaint.textSize * 1.35f
        canvas.drawText(guide1, pad.toFloat(), g1Y, guidePaint)
        canvas.drawText(guide2, pad.toFloat(), g2Y, guidePaint)

        // ── RIGHT BLOCK: authId (mint) + QR, vertically centered ─────────────
        val rightColL = (W - rightColW).toFloat()
        val authIdPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color          = MINT
            typeface       = Typeface.MONOSPACE
            isFakeBoldText = true
            textSize       = (actualWSz * 0.60f).coerceIn(12f, 60f)
        }
        val authIdMaxW = (rightColW - 2 * pad).toFloat()
        val authIdMeasured = authIdPaint.measureText(authId)
        if (authIdMeasured > authIdMaxW) {
            authIdPaint.textSize = maxOf(authIdPaint.textSize * authIdMaxW / authIdMeasured, 12f)
        }
        val authIdActualW = authIdPaint.measureText(authId)

        val qrAvailH = (stripH - 2 * pad - authIdPaint.textSize - lineGap).toInt()
        val qrAvailW = (rightColW - 2 * pad).toInt()
        val qrSize   = minOf(qrAvailH, qrAvailW).coerceAtLeast(32)
        val qrBgPad  = maxOf(3, qrSize / 32)
        val qrBmp    = generateQrBitmap("$VERIFY_BASE_URL/$authId", qrSize)

        val rightBlockH  = authIdPaint.textSize + lineGap + (qrSize + qrBgPad * 2).toFloat()
        val rightBlockTopY = stripMidY - rightBlockH * 0.5f

        // authId horizontally centered in right column
        canvas.drawText(
            authId,
            rightColL + (rightColW - authIdActualW) * 0.5f,
            rightBlockTopY + authIdPaint.textSize,
            authIdPaint
        )

        val qrLeft = (rightColL + (rightColW - qrSize) / 2f).toInt()
        val qrTop  = (rightBlockTopY + authIdPaint.textSize + lineGap).toInt()
        canvas.drawRect(
            (qrLeft - qrBgPad).toFloat(), (qrTop - qrBgPad).toFloat(),
            (qrLeft + qrSize + qrBgPad).toFloat(), (qrTop + qrSize + qrBgPad).toFloat(),
            Paint().apply { color = Color.WHITE }
        )
        canvas.drawBitmap(qrBmp, qrLeft.toFloat(), qrTop.toFloat(), null)

        return result
    }

    private fun drawLogoIcon(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        // Outer ring: mint
        canvas.drawCircle(cx, cy, r, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color       = MINT
            style       = Paint.Style.STROKE
            strokeWidth = r * 0.10f
        })
        // Middle ring: dark (aperture effect)
        canvas.drawCircle(cx, cy, r * 0.56f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color       = DARK_RING
            style       = Paint.Style.STROKE
            strokeWidth = r * 0.08f
        })
        // Inner filled dot
        canvas.drawCircle(cx, cy, r * 0.22f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = DARK_INNER
            style = Paint.Style.FILL
        })
        // Bottom-right check badge
        val badgeR = r * 0.40f
        val bCX    = cx + r * 0.82f
        val bCY    = cy + r * 0.82f
        canvas.drawCircle(bCX, bCY, badgeR + r * 0.06f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = BG_COLOR
            style = Paint.Style.FILL
        })
        canvas.drawCircle(bCX, bCY, badgeR, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = MINT
            style = Paint.Style.FILL
        })
        val ck = badgeR * 0.52f
        val path = Path()
        path.moveTo(bCX - ck * 0.60f, bCY + ck * 0.05f)
        path.lineTo(bCX - ck * 0.05f, bCY + ck * 0.55f)
        path.lineTo(bCX + ck * 0.70f, bCY - ck * 0.50f)
        canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color       = DARK_CHECK
            style       = Paint.Style.STROKE
            strokeWidth = badgeR * 0.28f
            strokeCap   = Paint.Cap.ROUND
            strokeJoin  = Paint.Join.ROUND
        })
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
