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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
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
    val context      = LocalContext.current
    val focusManager = LocalFocusManager.current
    val state        by viewModel.state.collectAsStateWithLifecycle()

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
                    contentDescription = stringResource(R.string.close),
                    tint = TextPrimary
                )
            }
            Spacer(Modifier.weight(1f))
            Text(stringResource(R.string.verify_title), color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
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
            Text(stringResource(R.string.verify_guide_title), color = AccentGreen, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            GuideStep(
                step = "1",
                text = stringResource(R.string.verify_guide_step1)
            )
            GuideStep(
                step = "2",
                text = stringResource(R.string.verify_guide_step2)
            )
            GuideStep(
                step = "3",
                text = stringResource(R.string.verify_guide_step3)
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
                if (state.imageUri == null) stringResource(R.string.verify_btn_pick)
                else stringResource(R.string.verify_btn_pick_other),
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
                    contentDescription = null,
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
                                Text(stringResource(R.string.verify_qr_decoding), color = TextSecondary, fontSize = 13.sp)
                            }
                        }
                        state.authId != null -> {
                            Text(stringResource(R.string.verify_qr_success), color = AccentGreen, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
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
                            Text(stringResource(R.string.verify_qr_fail), color = ErrorRed, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            Text(stringResource(R.string.verify_qr_fail_hint), color = TextSecondary, fontSize = 12.sp)
                        }
                    }
                }
            }

            // Manual auth ID input — shown when QR decode failed and authId not yet set
            if (state.qrError != null && state.authId == null) {
                Spacer(Modifier.height(12.dp))
                ManualAuthIdCard(
                    value    = state.manualAuthIdInput,
                    onChange = { viewModel.onManualAuthIdChanged(it) },
                    onSubmit = {
                        focusManager.clearFocus()
                        viewModel.onManualAuthIdSubmit(context)
                    }
                )
            }

            // Stage 1 + Stage 2 cards (shown only when authId found)
            if (state.authId != null) {
                Spacer(Modifier.height(16.dp))
                VerifyStageCard(
                    stageNumber = stringResource(R.string.verify_stage1_number),
                    stageLabel  = stringResource(R.string.verify_stage1_label),
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
                    stageNumber = stringResource(R.string.verify_stage2_number),
                    stageLabel  = stringResource(R.string.verify_stage2_label),
                    loading     = state.compareLoading,
                    content     = {
                        Text(
                            stringResource(R.string.verify_stage2_disclaimer),
                            color = TextSecondary,
                            fontSize = 11.sp,
                            lineHeight = 16.sp
                        )
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
        StatusRow(ok = false, text = stringResource(R.string.verify_lookup_unregistered))
        return
    }
    StatusRow(ok = true, text = stringResource(R.string.verify_lookup_registered))
    if (!r.captureTimeUtc.isNullOrBlank()) {
        DetailRow(label = stringResource(R.string.verify_label_capture_utc), value = r.captureTimeUtc)
    }
    if (!r.nickname.isNullOrBlank()) {
        DetailRow(label = stringResource(R.string.verify_label_nickname), value = r.nickname)
    }
}

@Composable
private fun CompareResultContent(r: VerificationService.CompareResult) {
    data class VerdictUi(val icon: String, val color: Color, val title: String, val subtitle: String = "")
    val ui = when (r.classification) {
        "identical" -> VerdictUi(
            icon     = "✓",
            color    = AccentGreen,
            title    = stringResource(R.string.verify_verdict_identical_title),
            subtitle = stringResource(R.string.verify_verdict_identical_sub)
        )
        "minor_edit" -> VerdictUi(
            icon     = "✓",
            color    = AccentGreen,
            title    = stringResource(R.string.verify_verdict_minor_title),
            subtitle = ""
        )
        "tampered", "suspicious" -> VerdictUi(
            icon     = "✗",
            color    = ErrorRed,
            title    = stringResource(R.string.verify_verdict_tampered_title),
            subtitle = ""
        )
        "unknown" -> VerdictUi("?", TextSecondary, stringResource(R.string.verify_verdict_unknown))
        else      -> VerdictUi("?", TextSecondary, stringResource(R.string.verify_verdict_other))
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(ui.icon, color = ui.color, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(ui.title, color = ui.color, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                if (ui.subtitle.isNotEmpty()) {
                    Text(ui.subtitle, color = ui.color, fontSize = 12.sp, fontWeight = FontWeight.Normal)
                }
            }
        }
        Text(
            stringResource(R.string.verify_compare_disclaimer),
            color = TextSecondary,
            fontSize = 11.sp,
            lineHeight = 16.sp
        )
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
private fun ManualAuthIdCard(
    value: String,
    onChange: (String) -> Unit,
    onSubmit: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .background(CardBg, RoundedCornerShape(10.dp))
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            stringResource(R.string.verify_manual_title),
            color = AccentGreen,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold
        )
        HorizontalDivider(color = Color(0xFF2A2A2A))
        Text(
            stringResource(R.string.verify_manual_hint),
            color = TextSecondary,
            fontSize = 12.sp,
            lineHeight = 17.sp
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment     = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value         = value,
                onValueChange = onChange,
                modifier      = Modifier.weight(1f),
                placeholder   = { Text("TS-XXXXXX", color = TextSecondary, fontSize = 13.sp) },
                singleLine    = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onSubmit() }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor   = AccentGreen,
                    unfocusedBorderColor = Color(0xFF444444),
                    focusedTextColor     = TextPrimary,
                    unfocusedTextColor   = TextPrimary,
                    cursorColor          = AccentGreen
                )
            )
            Button(
                onClick = onSubmit,
                enabled = value.trim().isNotBlank(),
                colors  = ButtonDefaults.buttonColors(
                    containerColor         = AccentGreen,
                    contentColor           = Color.Black,
                    disabledContainerColor = Color(0xFF2A2A2A),
                    disabledContentColor   = TextSecondary
                )
            ) {
                Text(stringResource(R.string.verify_manual_confirm), fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun ErrorRow(message: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("⚠", color = WarnYellow, fontSize = 14.sp)
        Text(stringResource(R.string.verify_error_prefix, message), color = TextSecondary, fontSize = 12.sp)
    }
}
