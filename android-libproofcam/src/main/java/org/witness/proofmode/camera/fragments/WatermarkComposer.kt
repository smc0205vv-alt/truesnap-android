package org.witness.proofmode.camera.fragments

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.os.Build
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
     * 3-column strip (left → center → right), all vertically centered:
     *
     *   ┌──────────────────┬──────────────────────────────────────────┬────────────────────────┐
     *   │   [logo icon]    │  TrueSnap 실제 촬영 인증 사진             │  TS-XXXXXX  (mint)     │
     *   │  True·Snap       │  직접 찍은 사진인지, 수정됐는지            │  ┌──────────────────┐  │
     *   │  (white/mint)    │  https://truesnap.app에서 확인하세요       │  │     QR code      │  │
     *   │                  │                                          │  └──────────────────┘  │
     *   └──────────────────┴──────────────────────────────────────────┴────────────────────────┘
     *   Background: #10141b · "Snap" and authId in #3ccfc2 (mint)
     */
    fun compose(photo: Bitmap, authId: String, context: Context): Bitmap {
        val isKorean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
            context.resources.configuration.locales[0].language == "ko"
        else
            @Suppress("DEPRECATION")
            context.resources.configuration.locale.language == "ko"

        val W = photo.width
        val H = photo.height

        val stripH = (H * 0.15f).toInt().coerceIn(180, 600)
        val pad    = (stripH * 0.07f).toInt().coerceAtLeast(8)

        val leftColW  = (W * 0.22f).toInt()
        val rightColW = (W * 0.28f).toInt()
        val rightColL = (W - rightColW).toFloat()
        val centerColX = leftColW.toFloat()

        val result = Bitmap.createBitmap(W, H + stripH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)

        canvas.drawBitmap(photo, 0f, 0f, null)

        canvas.drawRect(0f, H.toFloat(), W.toFloat(), (H + stripH).toFloat(),
            Paint().apply { color = BG_COLOR })

        val stripMidY = H.toFloat() + stripH * 0.5f

        val boldTyp   = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        val normalTyp = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)

        // ── LEFT: logo icon (top) + True·Snap wordmark (bottom), centered ───
        val leftAvailW = (leftColW - 2 * pad).toFloat()
        val iconDiam   = minOf(leftAvailW, stripH * 0.38f)
        val iconR      = iconDiam * 0.5f
        val iconGapV   = iconDiam * 0.10f

        val truePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color    = Color.WHITE
            typeface = boldTyp
            textSize = (iconDiam * 0.85f).coerceIn(14f, 80f)
        }
        val snapPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color    = MINT
            typeface = boldTyp
            textSize = truePaint.textSize
        }
        val totalWordmarkW0 = truePaint.measureText("True") + snapPaint.measureText("Snap")
        if (totalWordmarkW0 > leftAvailW) {
            val sc = leftAvailW / totalWordmarkW0
            truePaint.textSize *= sc
            snapPaint.textSize *= sc
        }
        val wSize    = truePaint.textSize
        val trueW    = truePaint.measureText("True")
        val snapW    = snapPaint.measureText("Snap")
        val wordmarkW = trueW + snapW

        val leftBlockH   = iconDiam + iconGapV + wSize
        val leftBlockTopY = stripMidY - leftBlockH * 0.5f

        val iconCX = pad.toFloat() + iconR
        val iconCY = leftBlockTopY + iconR
        drawLogoIcon(canvas, iconCX, iconCY, iconR)

        val wordmarkStartX = pad.toFloat() + (leftAvailW - wordmarkW) * 0.5f
        val wordmarkBaseline = leftBlockTopY + iconDiam + iconGapV + wSize * 0.82f
        canvas.drawText("True", wordmarkStartX, wordmarkBaseline, truePaint)
        canvas.drawText("Snap", wordmarkStartX + trueW, wordmarkBaseline, snapPaint)

        // ── CENTER: two guide lines, vertically centered ──────────────────────
        val guide1 = if (isKorean) "TrueSnap 실제 촬영 인증 사진" else "TrueSnap Certified Photo"
        val guide2 = if (isKorean) "직접 찍은 사진인지, 수정됐는지 https://truesnap.app에서 확인하세요"
                     else "Check if it's real or edited at https://truesnap.app"
        val centerAvailW = (rightColL - centerColX - 2 * pad).coerceAtLeast(1f)

        val guidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color    = Color.WHITE
            typeface = normalTyp
            textSize = (stripH * 0.15f).coerceIn(12f, 56f)
        }
        val maxGW = maxOf(guidePaint.measureText(guide1), guidePaint.measureText(guide2))
        if (maxGW > centerAvailW) {
            guidePaint.textSize = guidePaint.textSize * centerAvailW / maxGW
        }
        val lineStep    = guidePaint.textSize * 1.45f
        val guidesBlockH = guidePaint.textSize + lineStep
        val g1Y = stripMidY - guidesBlockH * 0.5f + guidePaint.textSize
        val g2Y = g1Y + lineStep
        canvas.drawText(guide1, centerColX + pad, g1Y, guidePaint)
        canvas.drawText(guide2, centerColX + pad, g2Y, guidePaint)

        // ── RIGHT: authId (mint) + QR, vertically centered ───────────────────
        val authIdRowH = (stripH * 0.22f).toInt()
        val authIdPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color          = MINT
            typeface       = Typeface.MONOSPACE
            isFakeBoldText = true
            textSize       = (authIdRowH * 0.68f).coerceIn(12f, 60f)
        }
        val authIdMaxW = (rightColW - 2 * pad).toFloat()
        val authIdMeasured = authIdPaint.measureText(authId)
        if (authIdMeasured > authIdMaxW) {
            authIdPaint.textSize = maxOf(authIdPaint.textSize * authIdMaxW / authIdMeasured, 12f)
        }
        val authIdActualW = authIdPaint.measureText(authId)

        val qrAvailH = stripH - authIdRowH - 2 * pad
        val qrSize   = minOf(qrAvailH, rightColW - 2 * pad).coerceAtLeast(32)
        val qrBgPad  = maxOf(3, qrSize / 32)
        val qrBmp    = generateQrBitmap("$VERIFY_BASE_URL/$authId", qrSize)

        val rightBlockH    = authIdPaint.textSize + (qrSize + qrBgPad * 2).toFloat() + pad * 0.5f
        val rightBlockTopY = stripMidY - rightBlockH * 0.5f

        canvas.drawText(
            authId,
            rightColL + (rightColW - authIdActualW) * 0.5f,
            rightBlockTopY + authIdPaint.textSize,
            authIdPaint
        )

        val qrLeft = (rightColL + (rightColW - qrSize) / 2f).toInt()
        val qrTop  = (rightBlockTopY + authIdPaint.textSize + pad * 0.5f).toInt()
        canvas.drawRect(
            (qrLeft - qrBgPad).toFloat(), (qrTop - qrBgPad).toFloat(),
            (qrLeft + qrSize + qrBgPad).toFloat(), (qrTop + qrSize + qrBgPad).toFloat(),
            Paint().apply { color = Color.WHITE }
        )
        canvas.drawBitmap(qrBmp, qrLeft.toFloat(), qrTop.toFloat(), null)

        return result
    }

    private fun drawLogoIcon(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        canvas.drawCircle(cx, cy, r, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color       = MINT
            style       = Paint.Style.STROKE
            strokeWidth = r * 0.10f
        })
        canvas.drawCircle(cx, cy, r * 0.56f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color       = DARK_RING
            style       = Paint.Style.STROKE
            strokeWidth = r * 0.08f
        })
        canvas.drawCircle(cx, cy, r * 0.22f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = DARK_INNER
            style = Paint.Style.FILL
        })
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
