package org.witness.proofmode.camera.fragments

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import jp.co.cyberagent.android.gpuimage.GPUImage
import jp.co.cyberagent.android.gpuimage.GPUImageView
import jp.co.cyberagent.android.gpuimage.filter.GPUImageBrightnessFilter
import jp.co.cyberagent.android.gpuimage.filter.GPUImageContrastFilter
import jp.co.cyberagent.android.gpuimage.filter.GPUImageFilterGroup
import jp.co.cyberagent.android.gpuimage.filter.GPUImageSaturationFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.witness.proofmode.camera.R
import timber.log.Timber

/**
 * Full-screen photo editor shown immediately after capture.
 *
 * Filters (GPU-accelerated):
 *   • 밝기 (Brightness)   –1.0 … +1.0  (default 0.0)
 *   • 채도 (Saturation)    0.0 …  2.0  (default 1.0)
 *   • 대비 (Contrast)      0.5 …  2.0  (default 1.0)
 *
 * "완료" button pipeline:
 *   1. Pull filtered bitmap from GPUImageView
 *   2. SHA-256 hash of JPEG bytes
 *   3. Generate "TS-XXXXXX" auth ID
 *   4. Overwrite MediaStore entry with filtered JPEG
 *   5. POST to certification server
 *   6. Show result dialog → navigate back on dismiss
 *
 * "버리기" returns without touching the file.
 */
@Composable
fun PhotoEditScreen(
    viewModel: CameraViewModel,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current

    val lastMedia         by viewModel.lastCapturedMedia.collectAsStateWithLifecycle()
    val certificationState by viewModel.certificationState.collectAsStateWithLifecycle()
    val uri: Uri?          = lastMedia?.uri
    val captureTimestamp   = lastMedia?.date ?: 0L

    // Filter state — reset when a new image arrives
    var brightness by remember(uri) { mutableFloatStateOf(0f) }   // -1.0 … 1.0
    var saturation by remember(uri) { mutableFloatStateOf(1f) }   //  0.0 … 2.0
    var contrast   by remember(uri) { mutableFloatStateOf(1f) }   //  0.5 … 2.0

    var sourceBitmap by remember(uri) { mutableStateOf<Bitmap?>(null) }
    var isLoading    by remember(uri) { mutableStateOf(true) }

    val gpuImageViewRef = remember { mutableStateOf<GPUImageView?>(null) }

    // --- Load bitmap on IO thread ---
    LaunchedEffect(uri) {
        if (uri == null) return@LaunchedEffect
        isLoading = true
        sourceBitmap = withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
            }.getOrElse { e ->
                Timber.e(e, "Failed to decode bitmap for edit screen")
                null
            }
        }
        isLoading = false
    }

    // --- Apply GPU filters when sliders or bitmap change ---
    val filterGroup = remember(brightness, saturation, contrast) {
        GPUImageFilterGroup(listOf(
            GPUImageBrightnessFilter(brightness),
            GPUImageSaturationFilter(saturation),
            GPUImageContrastFilter(contrast)
        ))
    }

    LaunchedEffect(filterGroup, sourceBitmap) {
        val view = gpuImageViewRef.value ?: return@LaunchedEffect
        val bmp  = sourceBitmap          ?: return@LaunchedEffect
        withContext(Dispatchers.Main) {
            view.filter = filterGroup
            view.setImage(bmp)
        }
    }

    DisposableEffect(Unit) {
        onDispose { gpuImageViewRef.value = null }
    }

    // --- Certification result dialog ---
    val doneState = certificationState as? CertificationState.Done
    if (doneState != null) {
        CertificationResultDialog(
            authId           = doneState.authId,
            sha256Hash       = doneState.sha256Hash,
            pHash            = doneState.pHash,
            uploadedToServer = doneState.uploadedToServer,
            serverError      = doneState.serverError,
            onDismiss = {
                viewModel.resetCertificationState()
                onNavigateBack()
            }
        )
    }

    // --- Full-screen processing overlay ---
    val isProcessing = certificationState is CertificationState.Processing

    Box(modifier = Modifier.fillMaxSize()) {
        // Main editor column
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
                Text(
                    text = "사진 편집",
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.weight(1f))
                Spacer(Modifier.width(48.dp))
            }

            // Preview area
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                if (isLoading) {
                    CircularProgressIndicator(color = AccentGreen)
                } else if (sourceBitmap != null) {
                    AndroidView(
                        factory = { ctx ->
                            GPUImageView(ctx).apply {
                                setScaleType(GPUImage.ScaleType.CENTER_INSIDE)
                                gpuImageViewRef.value = this
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Text("이미지를 불러올 수 없습니다.", color = Color.White)
                }
            }

            // Sliders panel
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
                        val view = gpuImageViewRef.value ?: return@Button
                        val filtered = view.gpuImage?.bitmapWithFilterApplied ?: sourceBitmap
                        if (filtered != null) {
                            viewModel.certifyAndSave(uri, filtered, captureTimestamp)
                        }
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
                        Text("완료", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // Semi-transparent processing overlay
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
// Certification result dialog
// ---------------------------------------------------------------------------

@Composable
private fun CertificationResultDialog(
    authId: String,
    sha256Hash: String,
    pHash: String?,
    uploadedToServer: Boolean,
    serverError: String?,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1E1E1E),
        title = {
            Text(
                text = if (uploadedToServer) "✓  인증 완료" else "인증 완료 (오프라인)",
                color = AccentGreen,
                fontWeight = FontWeight.Bold,
                fontSize = 17.sp
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                LabeledValue(label = "인증 ID", value = authId, mono = true)
                LabeledValue(
                    label = "SHA-256",
                    value = sha256Hash.take(16) + "…",
                    mono = true
                )
                // pHash — shown as presence indicator; full value lives on server
                if (pHash != null) {
                    LabeledValue(
                        label = "퍼셉추얼 해시 (pHash)",
                        value = pHash.take(16) + if (pHash.length > 16) "…" else "",
                        mono = true
                    )
                } else {
                    StatusRow(ok = false, text = "퍼셉추얼 해시 계산 실패")
                }
                // Separator
                Text(
                    text = "▸ 두 해시가 서버에 저장됩니다. 나중에 SHA-256(무결성)과 pHash(유사도)를 함께 비교해 '단순 보정' vs '구조 변경'을 판정합니다.",
                    color = Color(0xFF777777),
                    fontSize = 11.sp
                )
                if (uploadedToServer) {
                    StatusRow(ok = true, text = "서버 전송 완료")
                } else {
                    StatusRow(ok = false, text = "서버 전송 실패 — 로컬 저장됨")
                    if (serverError != null) {
                        Text(
                            text = serverError.take(80),
                            color = Color.Gray,
                            fontSize = 11.sp
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("확인", color = AccentGreen, fontWeight = FontWeight.SemiBold)
            }
        }
    )
}

@Composable
private fun LabeledValue(label: String, value: String, mono: Boolean = false) {
    Column {
        Text(label, color = Color(0xFF888888), fontSize = 11.sp)
        Text(
            text = value,
            color = Color.White,
            fontSize = 14.sp,
            fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .background(Color(0xFF2A2A2A), RoundedCornerShape(4.dp))
                .padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

@Composable
private fun StatusRow(ok: Boolean, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = if (ok) "✓" else "✗",
            color = if (ok) AccentGreen else Color(0xFFFF6B6B),
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.width(6.dp))
        Text(text, color = Color(0xFFDDDDDD), fontSize = 13.sp)
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
