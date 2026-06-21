package org.witness.proofmode.camera.fragments

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.witness.proofmode.camera.R

@Composable
fun NicknameInputScreen(
    viewModel: CameraViewModel,
    onConfirmed: () -> Unit,
    onNavigateBack: () -> Unit
) {
    val certState      by viewModel.certificationState.collectAsStateWithLifecycle()
    val uploadState    by viewModel.uploadState.collectAsStateWithLifecycle()
    val watermarkState by viewModel.watermarkState.collectAsStateWithLifecycle()
    val batchQueue     by viewModel.batchQueue.collectAsStateWithLifecycle()
    val batchCertItems by viewModel.batchCertItems.collectAsStateWithLifecycle()
    val batchUploadState by viewModel.batchUploadState.collectAsStateWithLifecycle()

    val isBatchMode = batchQueue.isNotEmpty()
    val doneState   = certState as? CertificationState.Done

    // 진입 시점 로그 — batchCertItems.size가 예상 장수와 다르면 여기서 감지됨
    android.util.Log.d("BATCH_TRACE", "NicknameInputScreen: isBatchMode=$isBatchMode batchQueue.size=${batchQueue.size} batchCertItems.size=${batchCertItems.size}")

    var nickname    by remember { mutableStateOf("") }
    var isConfirmed by remember { mutableStateOf(false) }
    val recentNames = remember { viewModel.getRecentNames() }

    val isWatermarkReady = watermarkState is WatermarkState.Ready
    val isSingleUploading = uploadState is UploadState.Uploading
    val isBatchRunning    = batchUploadState is BatchUploadState.Running
    val isUploading       = isSingleUploading || isBatchRunning
    val isLocked          = isConfirmed || isUploading

    // Single mode: start watermark generation as soon as this screen opens
    LaunchedEffect(doneState?.authId) {
        if (!isBatchMode && doneState != null && watermarkState is WatermarkState.Idle) {
            viewModel.generateWatermark(doneState.authId)
        }
    }

    val keyboardController = LocalSoftwareKeyboardController.current
    val focusRequester     = remember { FocusRequester() }

    // Single mode: navigate on upload success
    LaunchedEffect(uploadState) {
        if (!isBatchMode && uploadState is UploadState.Success) {
            onConfirmed()
        }
    }

    // Batch mode: navigate when batch upload finishes
    LaunchedEffect(batchUploadState) {
        if (isBatchMode && batchUploadState is BatchUploadState.Finished) {
            onConfirmed()
        }
    }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    // Single mode: error dialog on upload failure
    if (!isBatchMode && uploadState is UploadState.Failure) {
        val failure = uploadState as UploadState.Failure
        AlertDialog(
            onDismissRequest = {},
            containerColor = Color(0xFF1E1E1E),
            title = { Text(stringResource(R.string.nickname_error_title), color = Color(0xFFFF6B6B), fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(failure.error.take(120), color = Color(0xFFDDDDDD), fontSize = 13.sp)
                    Text(
                        stringResource(R.string.nickname_error_retry_hint),
                        color = Color(0xFF888888), fontSize = 12.sp
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    doneState?.let {
                        viewModel.startCertificationUpload(
                            authId              = it.authId,
                            sha256Hash          = it.sha256Hash,
                            captureTimestampMs  = it.captureTimestampMs,
                            nickname            = nickname.trim(),
                            lofiThumbnailBase64 = it.lofiThumbnailBase64,
                            edgeDensities       = it.edgeDensities,
                            edgeStdDev          = it.edgeStdDev
                        )
                    }
                }) {
                    Text(stringResource(R.string.nickname_error_retry), color = AccentGreen, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { onConfirmed() }) {
                    Text(stringResource(R.string.nickname_error_skip), color = Color(0xFF888888))
                }
            }
        )
    }

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
            IconButton(onClick = { if (!isLocked) onNavigateBack() }) {
                Icon(
                    imageVector = ImageVector.vectorResource(R.drawable.baseline_close_24),
                    contentDescription = null,
                    tint = if (isLocked) Color.Gray else Color.White
                )
            }
            Spacer(Modifier.weight(1f))
            Text(stringResource(R.string.nickname_title), color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Spacer(Modifier.width(48.dp))
        }

        // Auth summary card — single mode shows auth ID, batch mode shows photo count
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp)
                .background(Color(0xFF1A1A1A), RoundedCornerShape(8.dp))
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (isBatchMode) {
                Text(stringResource(R.string.nickname_cert_planned), color = Color(0xFF888888), fontSize = 11.sp)
                Text(
                    stringResource(R.string.nickname_batch_count, batchCertItems.size),
                    color = AccentGreen,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
            } else if (doneState != null) {
                Text(stringResource(R.string.nickname_auth_id_label), color = Color(0xFF888888), fontSize = 11.sp)
                Text(
                    doneState.authId,
                    color = AccentGreen,
                    fontSize = 15.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.nickname_cert_validity),
                    color = Color(0xFF888888),
                    fontSize = 11.sp
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.nickname_privacy_detail),
                    color = Color(0xFF666666),
                    fontSize = 10.sp,
                    lineHeight = 14.sp
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        // Recent name chips
        if (recentNames.isNotEmpty() && !isLocked) {
            Text(
                stringResource(R.string.nickname_recent_names),
                color = Color(0xFF888888),
                fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            Spacer(Modifier.height(6.dp))
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(recentNames) { name ->
                    SuggestionChip(
                        onClick = { nickname = name },
                        label = { Text(name, fontSize = 13.sp) },
                        colors = SuggestionChipDefaults.suggestionChipColors(
                            containerColor = Color(0xFF2A2A2A),
                            labelColor = Color.White
                        ),
                        border = SuggestionChipDefaults.suggestionChipBorder(
                            enabled = true,
                            borderColor = Color(0xFF444444)
                        )
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        // Name input
        OutlinedTextField(
            value = nickname,
            onValueChange = { if (!isLocked) nickname = it },
            label = { Text(stringResource(R.string.nickname_field_label)) },
            singleLine = true,
            enabled = !isLocked,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .focusRequester(focusRequester),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { keyboardController?.hide() }),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor   = AccentGreen,
                unfocusedBorderColor = Color(0xFF444444),
                focusedTextColor     = Color.White,
                unfocusedTextColor   = Color.White,
                focusedLabelColor    = AccentGreen,
                unfocusedLabelColor  = Color(0xFF888888),
                cursorColor          = AccentGreen,
                disabledBorderColor  = Color(0xFF333333),
                disabledTextColor    = Color(0xFF888888),
                disabledLabelColor   = Color(0xFF555555)
            )
        )

        Spacer(Modifier.weight(1f))

        // Watermark / upload progress indicators
        if (isUploading) {
            Column(
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(bottom = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    color = AccentGreen,
                    strokeWidth = 2.dp
                )
                val progressText = if (isBatchMode) {
                    val running = batchUploadState as? BatchUploadState.Running
                    if (running != null) stringResource(R.string.nickname_uploading_batch, running.completed, running.total)
                    else stringResource(R.string.nickname_preparing)
                } else stringResource(R.string.nickname_uploading)
                Text(progressText, color = Color(0xFF888888), fontSize = 12.sp)
            }
        } else if (!isBatchMode && !isWatermarkReady && !isLocked) {
            Row(
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    color = Color(0xFF888888),
                    strokeWidth = 2.dp
                )
                Text(
                    if (watermarkState is WatermarkState.Failed) stringResource(R.string.nickname_watermark_fail)
                    else stringResource(R.string.nickname_preparing),
                    color = Color(0xFF888888), fontSize = 12.sp
                )
            }
        }

        // Privacy notice
        Text(
            stringResource(R.string.nickname_privacy_notice),
            color = Color(0xFF888888),
            fontSize = 12.sp,
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .padding(bottom = 4.dp)
        )
        Text(
            stringResource(R.string.nickname_privacy_detail),
            color = Color(0xFF666666),
            fontSize = 10.sp,
            lineHeight = 14.sp,
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .padding(bottom = 8.dp)
        )

        // Confirm button
        val buttonEnabled = nickname.isNotBlank() && !isLocked &&
                if (isBatchMode) true else isWatermarkReady

        Button(
            onClick = {
                if (nickname.isBlank() || isLocked) return@Button
                keyboardController?.hide()
                isConfirmed = true
                val trimmed = nickname.trim()
                if (isBatchMode) {
                    batchCertItems.firstOrNull()?.let {
                        viewModel.saveNicknameForAuth(it.certDone.authId, trimmed)
                    }
                    viewModel.startBatchUpload(trimmed)
                } else {
                    doneState?.let {
                        viewModel.saveNicknameForAuth(it.authId, trimmed)
                        viewModel.startCertificationUpload(
                            authId              = it.authId,
                            sha256Hash          = it.sha256Hash,
                            captureTimestampMs  = it.captureTimestampMs,
                            nickname            = trimmed,
                            lofiThumbnailBase64 = it.lofiThumbnailBase64,
                            edgeDensities       = it.edgeDensities,
                            edgeStdDev          = it.edgeStdDev
                        )
                    }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 16.dp),
            enabled = buttonEnabled,
            colors = ButtonDefaults.buttonColors(
                containerColor          = AccentGreen,
                contentColor            = Color.Black,
                disabledContainerColor  = Color(0xFF2A2A2A),
                disabledContentColor    = Color(0xFF555555)
            )
        ) {
            if (isUploading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    color = Color.Black,
                    strokeWidth = 2.dp
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    stringResource(R.string.nickname_uploading),
                    fontWeight = FontWeight.Bold, fontSize = 15.sp
                )
            } else {
                val label = if (isBatchMode)
                    stringResource(R.string.nickname_confirm_batch, batchCertItems.size)
                else
                    stringResource(R.string.nickname_confirm_single)
                Text(label, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }
        }
    }
}
