package org.witness.proofmode.camera.fragments

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.witness.proofmode.camera.R
import timber.log.Timber

/** Builds a ColorMatrix combining brightness, saturation, and contrast. */
private fun buildColorMatrix(brightness: Float, saturation: Float, contrast: Float): ColorMatrix {
    val cm = ColorMatrix()

    // Saturation
    val sat = ColorMatrix()
    sat.setSaturation(saturation)
    cm.postConcat(sat)

    // Contrast + brightness: scale channels then translate
    val t = ((-0.5f * contrast + 0.5f) + brightness) * 255f
    val cb = ColorMatrix(floatArrayOf(
        contrast, 0f, 0f, 0f, t,
        0f, contrast, 0f, 0f, t,
        0f, 0f, contrast, 0f, t,
        0f, 0f, 0f, 1f, 0f
    ))
    cm.postConcat(cb)

    return cm
}

/** Applies [matrix] to [src] and returns a new Bitmap. */
private fun applyColorMatrix(src: Bitmap, matrix: ColorMatrix): Bitmap {
    val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
    val paint = Paint().apply { colorFilter = ColorMatrixColorFilter(matrix) }
    Canvas(out).drawBitmap(src, 0f, 0f, paint)
    return out
}

/**
 * @param onCertDone  Called when [CertificationState.Done] is reached.
 *   Receives (done, savedEdits) where savedEdits is non-null when "전체 적용" is active.
 *   The caller decides whether to navigate to the next photo or to the nickname screen.
 */
@Composable
fun PhotoEditScreen(
    viewModel: CameraViewModel,
    onNavigateBack: () -> Unit,
    onCertDone: (CertificationState.Done, savedEdits: Triple<Float, Float, Float>?) -> Unit = { _, _ -> }
) {
    val context = LocalContext.current

    val lastMedia          by viewModel.lastCapturedMedia.collectAsStateWithLifecycle()
    val certificationState by viewModel.certificationState.collectAsStateWithLifecycle()
    val batchQueue         by viewModel.batchQueue.collectAsStateWithLifecycle()
    val batchEditIndex     by viewModel.batchEditIndex.collectAsStateWithLifecycle()
    val batchGlobalEdits   by viewModel.batchGlobalEdits.collectAsStateWithLifecycle()

    val uri: Uri?           = lastMedia?.uri
    val captureTimestamp    = lastMedia?.date ?: 0L
    val isBatchMode         = batchQueue.isNotEmpty()
    val batchTotal          = batchQueue.size

    var brightness by remember(uri) { mutableFloatStateOf(batchGlobalEdits?.first  ?: 0f) }
    var saturation by remember(uri) { mutableFloatStateOf(batchGlobalEdits?.second ?: 1f) }
    var contrast   by remember(uri) { mutableFloatStateOf(batchGlobalEdits?.third  ?: 1f) }
    var applyToAll by remember { mutableStateOf(batchGlobalEdits != null) }

    var sourceBitmap by remember(uri) { mutableStateOf<Bitmap?>(null) }
    var isLoading    by remember(uri) { mutableStateOf(true) }

    // Load bitmap on IO thread, applying EXIF orientation so portrait shots display upright.
    LaunchedEffect(uri) {
        if (uri == null) return@LaunchedEffect
        isLoading = true
        sourceBitmap = withContext(Dispatchers.IO) {
            runCatching {
                val bitmap = context.contentResolver.openInputStream(uri)
                    ?.use { BitmapFactory.decodeStream(it) }
                    ?: return@runCatching null
                val orientation = context.contentResolver.openInputStream(uri)?.use {
                    ExifInterface(it).getAttributeInt(
                        ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
                    )
                } ?: ExifInterface.ORIENTATION_NORMAL
                val matrix = Matrix()
                when (orientation) {
                    ExifInterface.ORIENTATION_ROTATE_90  -> matrix.postRotate(90f)
                    ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
                    ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
                    ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
                    ExifInterface.ORIENTATION_FLIP_VERTICAL   -> matrix.postScale(1f, -1f)
                    else -> return@runCatching bitmap
                }
                Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                    .also { bitmap.recycle() }
            }.getOrElse { e ->
                Timber.e(e, "Failed to decode bitmap for edit screen")
                null
            }
        }
        isLoading = false
    }

    // Build ColorFilter for live preview
    val colorFilter = remember(brightness, saturation, contrast) {
        ColorFilter.colorMatrix(
            androidx.compose.ui.graphics.ColorMatrix(
                buildColorMatrix(brightness, saturation, contrast).array
            )
        )
    }

    // Tracks the edit values to pass to onCertDone when applyToAll is on
    var pendingSavedEdits: Triple<Float, Float, Float>? by remember { mutableStateOf(null) }

    LaunchedEffect(certificationState) {
        Timber.d("BATCH_TRACE EditScreen LaunchedEffect: certState=${certificationState::class.simpleName} batchIndex=$batchEditIndex batchTotal=$batchTotal")
        if (certificationState is CertificationState.Done) {
            Timber.d("BATCH_TRACE EditScreen calling onCertDone for index=$batchEditIndex")
            onCertDone(certificationState as CertificationState.Done, pendingSavedEdits)
            pendingSavedEdits = null
        }
    }

    val isProcessing = certificationState is CertificationState.Processing

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            // Top bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onNavigateBack, enabled = !isProcessing) {
                    Icon(
                        imageVector = ImageVector.vectorResource(R.drawable.baseline_close_24),
                        contentDescription = "닫기",
                        tint = if (isProcessing) Color.Gray else Color.White
                    )
                }
                Spacer(Modifier.weight(1f))
                // Title: show batch progress in batch mode
                if (isBatchMode) {
                    Text(
                        text = "사진 편집  ${batchEditIndex + 1} / $batchTotal",
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                } else {
                    Text(
                        text = "사진 편집",
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Spacer(Modifier.weight(1f))
                Spacer(Modifier.width(48.dp))
            }

            // Preview
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                when {
                    isLoading -> CircularProgressIndicator(color = AccentGreen)
                    sourceBitmap != null -> Image(
                        bitmap = sourceBitmap!!.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        colorFilter = colorFilter,
                        modifier = Modifier.fillMaxSize()
                    )
                    else -> Text("이미지를 불러올 수 없습니다.", color = Color.White)
                }
            }

            // Sliders
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF1A1A1A))
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                FilterSlider(
                    label = "밝기",
                    value = brightness,
                    valueRange = -1f..1f,
                    defaultValue = 0f,
                    displayRange = "-100" to "+100",
                    displayValue = (brightness * 100).toInt().let { if (it >= 0) "+$it" else "$it" },
                    enabled = !isProcessing,
                    onValueChange = { brightness = it }
                )
                FilterSlider(
                    label = "채도",
                    value = saturation,
                    valueRange = 0f..2f,
                    defaultValue = 1f,
                    displayRange = "0" to "200",
                    displayValue = (saturation * 100).toInt().toString(),
                    enabled = !isProcessing,
                    onValueChange = { saturation = it }
                )
                FilterSlider(
                    label = "대비",
                    value = contrast,
                    valueRange = 0.5f..2f,
                    defaultValue = 1f,
                    displayRange = "50" to "200",
                    displayValue = (contrast * 100).toInt().toString(),
                    enabled = !isProcessing,
                    onValueChange = { contrast = it }
                )

                // "전체 적용" toggle — only visible when there are multiple photos in the batch
                if (isBatchMode && batchTotal > 1) {
                    Spacer(Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            "이 보정값을 다음 사진에도 적용",
                            color = if (!isProcessing) Color.White else Color.Gray,
                            fontSize = 13.sp
                        )
                        Switch(
                            checked = applyToAll,
                            onCheckedChange = { applyToAll = it },
                            enabled = !isProcessing,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.Black,
                                checkedTrackColor = AccentGreen,
                                uncheckedThumbColor = Color.Gray,
                                uncheckedTrackColor = Color(0xFF333333)
                            )
                        )
                    }
                }
            }

            // Bottom buttons
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF111111))
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = onNavigateBack,
                    modifier = Modifier.weight(1f),
                    enabled = !isProcessing,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                ) {
                    Text("버리기")
                }

                Button(
                    onClick = {
                        if (isProcessing || uri == null) return@Button
                        val src = sourceBitmap ?: return@Button
                        val filtered = applyColorMatrix(src, buildColorMatrix(brightness, saturation, contrast))
                        if (applyToAll && isBatchMode) {
                            pendingSavedEdits = Triple(brightness, saturation, contrast)
                        }
                        viewModel.certifyAndSave(uri, filtered, captureTimestamp)
                    },
                    modifier = Modifier.weight(1f),
                    enabled = !isProcessing && sourceBitmap != null,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AccentGreen,
                        contentColor = Color.Black
                    )
                ) {
                    if (isProcessing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = Color.Black,
                            strokeWidth = 2.dp
                        )
                    } else {
                        val label = if (isBatchMode && batchEditIndex < batchTotal - 1) "완료 →" else "완료"
                        Text(label, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // Processing overlay
        if (isProcessing) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.55f)),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = AccentGreen, strokeWidth = 3.dp)
                    Spacer(Modifier.height(14.dp))
                    Text("인증 중...", color = Color.White, fontSize = 14.sp)
                }
            }
        }
    }
}


// ---------------------------------------------------------------------------
// Reusable slider row
// ---------------------------------------------------------------------------

@Composable
private fun FilterSlider(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    defaultValue: Float,
    displayRange: Pair<String, String>,
    displayValue: String,
    enabled: Boolean = true,
    onValueChange: (Float) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, color = if (enabled) Color.White else Color.Gray,
                fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(displayValue, color = if (enabled) AccentGreen else Color.Gray, fontSize = 13.sp)
                Spacer(Modifier.width(8.dp))
                if (value != defaultValue && enabled) {
                    IconButton(
                        onClick = { onValueChange(defaultValue) },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Text("↺", color = Color.Gray, fontSize = 16.sp)
                    }
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(displayRange.first, color = Color.Gray, fontSize = 10.sp)
            Slider(
                value = value,
                onValueChange = onValueChange,
                valueRange = valueRange,
                enabled = enabled,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 6.dp),
                colors = SliderDefaults.colors(
                    thumbColor = AccentGreen,
                    activeTrackColor = AccentGreen,
                    inactiveTrackColor = Color(0xFF444444),
                    disabledThumbColor = Color.Gray,
                    disabledActiveTrackColor = Color.Gray
                )
            )
            Text(displayRange.second, color = Color.Gray, fontSize = 10.sp)
        }
    }
}
