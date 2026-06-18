package org.witness.proofmode.verify

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import org.witness.proofmode.R

private val AccentGreen  = Color(0xFF4CAF82)
private val CardBg       = Color(0xFF1A1A1A)
private val TextPrimary  = Color.White
private val TextSecondary = Color(0xFF888888)
private val ErrorRed     = Color(0xFFFF6B6B)
private val WarnYellow   = Color(0xFFFFCC44)

@Composable
fun VerifyScreen(
    viewModel: VerifyViewModel = viewModel(),
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val state   by viewModel.state.collectAsStateWithLifecycle()

    val pickMedia = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        uri?.let { viewModel.onImageSelected(context, it) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
    ) {
        // ── Top bar ────────────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onNavigateBack) {
                Icon(
                    imageVector = ImageVector.vectorResource(R.drawable.ic_close_black_24dp),
                    contentDescription = "닫기",
                    tint = TextPrimary
                )
            }
            Spacer(Modifier.weight(1f))
            Text("사진으로 인증 확인", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Spacer(Modifier.width(48.dp))
        }

        // ── Guide card ─────────────────────────────────────────────────────
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp)
                .background(CardBg, RoundedCornerShape(10.dp))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("인증 확인 방법", color = AccentGreen, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            GuideStep(
                step = "1",
                text = "판매자에게 받은 매물 사진을 길게 눌러 갤러리에 저장하세요."
            )
            GuideStep(
                step = "2",
                text = "아래 '사진 선택' 버튼을 눌러 저장한 사진을 고르세요. QR 코드를 자동으로 읽어 인증 정보를 조회합니다."
            )
            GuideStep(
                step = "3",
                text = "1단계(등록 여부)와 2단계(위변조 비교) 결과를 확인하세요."
            )
        }

        Spacer(Modifier.height(12.dp))

        // ── Image picker button ────────────────────────────────────────────
        Button(
            onClick = {
                pickMedia.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                )
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = AccentGreen,
                contentColor   = Color.Black
            )
        ) {
            Text(
                if (state.imageUri == null) "갤러리에서 사진 선택하기"
                else "다른 사진 선택하기",
                fontWeight = FontWeight.Bold
            )
        }

        // ── Results (shown after image is selected) ────────────────────────
        if (state.imageUri != null) {
            Spacer(Modifier.height(16.dp))

            // Thumbnail + QR status row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AsyncImage(
                    model          = state.imageUri,
                    contentDescription = "선택된 사진",
                    contentScale   = ContentScale.Crop,
                    modifier       = Modifier
                        .size(88.dp)
                        .clip(RoundedCornerShape(8.dp))
                )
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    when {
                        state.qrDecoding -> {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    color = AccentGreen,
                                    strokeWidth = 2.dp
                                )
                                Spacer(Modifier.width(8.dp))
                                Text("QR 코드 인식 중…", color = TextSecondary, fontSize = 13.sp)
                            }
                        }
                        state.authId != null -> {
                            Text("QR 인식 성공", color = AccentGreen, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            Text(
                                state.authId!!,
                                color = TextPrimary,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                letterSpacing = 1.sp
                            )
                        }
                        state.qrError != null -> {
                            Text("✗ QR 인식 실패", color = ErrorRed, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            Text(state.qrError!!, color = TextSecondary, fontSize = 12.sp)
                        }
                    }
                }
            }

            // Stage 1 + Stage 2 cards (shown only when authId found)
            if (state.authId != null) {
                Spacer(Modifier.height(16.dp))
                VerifyStageCard(
                    stageNumber = "1단계",
                    stageLabel  = "등록 확인",
                    loading     = state.lookupLoading,
                    content     = {
                        when {
                            state.lookupLoading -> {}
                            state.lookupResult != null -> LookupResultContent(state.lookupResult!!)
                            state.lookupError != null  -> ErrorRow(state.lookupError!!)
                        }
                    }
                )
                Spacer(Modifier.height(12.dp))
                VerifyStageCard(
                    stageNumber = "2단계",
                    stageLabel  = "위변조 비교",
                    loading     = state.compareLoading,
                    content     = {
                        when {
                            state.compareLoading -> {}
                            state.compareResult != null -> CompareResultContent(state.compareResult!!)
                            state.compareError != null  -> ErrorRow(state.compareError!!)
                        }
                    }
                )
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

// ── Sub-composables ───────────────────────────────────────────────────────────

@Composable
private fun GuideStep(step: String, text: String) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment     = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .size(20.dp)
                .background(AccentGreen, RoundedCornerShape(50)),
            contentAlignment = Alignment.Center
        ) {
            Text(step, color = Color.Black, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
        Text(text, color = Color(0xFFCCCCCC), fontSize = 13.sp, lineHeight = 19.sp)
    }
}

@Composable
private fun VerifyStageCard(
    stageNumber: String,
    stageLabel: String,
    loading: Boolean,
    content: @Composable () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .background(CardBg, RoundedCornerShape(10.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            verticalAlignment  = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .background(Color(0xFF2A2A2A), RoundedCornerShape(4.dp))
                    .padding(horizontal = 8.dp, vertical = 2.dp)
            ) {
                Text(stageNumber, color = AccentGreen, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
            Text(stageLabel, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            if (loading) {
                Spacer(Modifier.weight(1f))
                CircularProgressIndicator(
                    modifier    = Modifier.size(16.dp),
                    color       = AccentGreen,
                    strokeWidth = 2.dp
                )
            }
        }
        if (!loading) {
            HorizontalDivider(color = Color(0xFF2A2A2A))
            content()
        }
    }
}

@Composable
private fun LookupResultContent(r: VerificationService.LookupResult) {
    if (!r.registered) {
        StatusRow(ok = false, text = "미등록 인증ID — 서버에 등록된 인증이 없습니다")
        return
    }
    StatusRow(ok = true, text = "인증 등록됨")
    if (!r.captureTimeUtc.isNullOrBlank()) {
        DetailRow(label = "촬영일시 (UTC)", value = r.captureTimeUtc)
    }
    if (!r.nickname.isNullOrBlank()) {
        DetailRow(label = "등록 닉네임", value = r.nickname)
    }
    if (!r.sha256Hash.isNullOrBlank()) {
        DetailRow(
            label = "SHA-256",
            value = r.sha256Hash.take(20) + "…",
            mono  = true
        )
    }
}

@Composable
private fun CompareResultContent(r: VerificationService.CompareResult) {
    val (icon, color, label) = when (r.classification) {
        "identical"   -> Triple("✓", AccentGreen,  "원본과 동일")
        "minor_edit"  -> Triple("△", WarnYellow,   "단순 보정됨 (밝기·채도·대비 등)")
        "tampered"    -> Triple("✗", ErrorRed,      "구조 변경 의심")
        else          -> Triple("?", TextSecondary, "결과 알 수 없음")
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(icon, color = color, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text(label, color = color, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
    r.sha256Match?.let {
        DetailRow(label = "SHA-256 일치", value = if (it) "예 (비트 단위 동일)" else "아니오 (파일 변경됨)")
    }
    r.pHashHamming?.let { hamming ->
        val desc = when {
            hamming == 0        -> "완전 동일"
            hamming <= 5        -> "거의 동일"
            hamming <= 10       -> "유사 (단순 보정 범위)"
            else                -> "차이 큼 (구조 변경 의심)"
        }
        DetailRow(label = "pHash 해밍 거리", value = "$hamming  ← $desc")
    }
}

@Composable
private fun StatusRow(ok: Boolean, text: String) {
    Row(
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            if (ok) "✓" else "✗",
            color = if (ok) AccentGreen else ErrorRed,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold
        )
        Text(text, color = if (ok) AccentGreen else ErrorRed, fontSize = 14.sp)
    }
}

@Composable
private fun DetailRow(label: String, value: String, mono: Boolean = false) {
    Column {
        Text(label, color = TextSecondary, fontSize = 11.sp)
        Text(
            value,
            color      = TextPrimary,
            fontSize   = 13.sp,
            fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default
        )
    }
}

@Composable
private fun ErrorRow(message: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("⚠", color = WarnYellow, fontSize = 14.sp)
        Text("조회 실패: $message", color = TextSecondary, fontSize = 12.sp)
    }
}
