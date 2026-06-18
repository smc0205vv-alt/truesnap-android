package org.witness.proofmode.camera.fragments

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
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
    val certState  by viewModel.certificationState.collectAsStateWithLifecycle()
    val uploadState by viewModel.uploadState.collectAsStateWithLifecycle()
    val doneState  = certState as? CertificationState.Done

    var nickname     by remember { mutableStateOf("") }
    var isConfirmed  by remember { mutableStateOf(false) }
    val recentNames  = remember { viewModel.getRecentNames() }

    val isUploading = uploadState is UploadState.Uploading
    val isLocked    = isConfirmed || isUploading

    val keyboardController = LocalSoftwareKeyboardController.current
    val focusRequester     = remember { FocusRequester() }

    // Auto-navigate on upload success
    LaunchedEffect(uploadState) {
        if (uploadState is UploadState.Success) {
            onConfirmed()
        }
    }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    // Error dialog on upload failure
    if (uploadState is UploadState.Failure) {
        val failure = uploadState as UploadState.Failure
        AlertDialog(
            onDismissRequest = {},
            containerColor = Color(0xFF1E1E1E),
            title = {
                Text("전송 실패", color = Color(0xFFFF6B6B), fontWeight = FontWeight.Bold)
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        failure.error.take(120),
                        color = Color(0xFFDDDDDD),
                        fontSize = 13.sp
                    )
                    Text(
                        "재시도하거나, 지금은 건너뛰고 나중에 다시 시도할 수 있습니다.",
                        color = Color(0xFF888888),
                        fontSize = 12.sp
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    // Retry with same data
                    doneState?.let {
                        viewModel.startCertificationUpload(
                            authId             = it.authId,
                            sha256Hash         = it.sha256Hash,
                            pHash              = it.pHash,
                            captureTimestampMs = it.captureTimestampMs,
                            nickname           = nickname.trim()
                        )
                    }
                }) {
                    Text("재시도", color = AccentGreen, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { onConfirmed() }) {
                    Text("건너뛰기", color = Color(0xFF888888))
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
                    contentDescription = "닫기",
                    tint = if (isLocked) Color.Gray else Color.White
                )
            }
            Spacer(Modifier.weight(1f))
            Text("닉네임 입력", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Spacer(Modifier.width(48.dp))
        }

        // Auth summary card
        if (doneState != null) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .background(Color(0xFF1A1A1A), RoundedCornerShape(8.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text("인증 ID", color = Color(0xFF888888), fontSize = 11.sp)
                Text(
                    doneState.authId,
                    color = AccentGreen,
                    fontSize = 15.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
                HorizontalDivider(
                    color = Color(0xFF333333),
                    modifier = Modifier.padding(vertical = 2.dp)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Column {
                        Text("SHA-256", color = Color(0xFF888888), fontSize = 11.sp)
                        Text(
                            doneState.sha256Hash.take(20) + "…",
                            color = Color(0xFFBBBBBB),
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    if (doneState.pHash != null) {
                        Column {
                            Text("pHash", color = Color(0xFF888888), fontSize = 11.sp)
                            Text(
                                doneState.pHash.take(14) + "…",
                                color = Color(0xFFBBBBBB),
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // Recent name chips
        if (recentNames.isNotEmpty() && !isLocked) {
            Text(
                "최근 사용한 이름",
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
            label = { Text("닉네임") },
            placeholder = { Text("거래 상대방의 닉네임 입력") },
            singleLine = true,
            enabled = !isLocked,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .focusRequester(focusRequester),
            keyboardOptions = KeyboardOptions.Default.copy(imeAction = ImeAction.Done),
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

        Spacer(Modifier.height(12.dp))

        // Disclaimer box
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .background(Color(0xFF1A1200), RoundedCornerShape(8.dp))
                .padding(horizontal = 14.dp, vertical = 12.dp)
        ) {
            Text(
                "⚠  이 이름은 당사가 소유를 검증하지 않습니다. " +
                    "거래 상대방의 실제 닉네임과 같은지 직접 확인하세요.",
                color = Color(0xFFFFCC44),
                fontSize = 13.sp,
                lineHeight = 19.sp
            )
        }

        Spacer(Modifier.weight(1f))

        // Confirm / uploading button
        Button(
            onClick = {
                if (nickname.isBlank() || isLocked) return@Button
                keyboardController?.hide()
                isConfirmed = true
                doneState?.let {
                    viewModel.saveNicknameForAuth(it.authId, nickname.trim())
                    viewModel.startCertificationUpload(
                        authId             = it.authId,
                        sha256Hash         = it.sha256Hash,
                        pHash              = it.pHash,
                        captureTimestampMs = it.captureTimestampMs,
                        nickname           = nickname.trim()
                    )
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 16.dp),
            enabled = nickname.isNotBlank() && !isLocked,
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
                Text("전송 중…", fontWeight = FontWeight.Bold, fontSize = 15.sp)
            } else {
                Text("확정 (이후 수정 불가)", fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }
        }
    }
}
