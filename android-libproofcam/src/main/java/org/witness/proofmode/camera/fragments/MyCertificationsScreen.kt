package org.witness.proofmode.camera.fragments

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import org.witness.proofmode.camera.R
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.coroutines.launch
import org.witness.proofmode.camera.db.CertificationRecord
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MyCertificationsScreen(
    viewModel: MyCertificationsViewModel,
    onNavigateBack: () -> Unit
) {
    val certifications by viewModel.certifications.collectAsStateWithLifecycle()
    val extendState    by viewModel.extendState.collectAsStateWithLifecycle()

    var selectedRecord    by remember { mutableStateOf<CertificationRecord?>(null) }
    var pendingDeleteRecord by remember { mutableStateOf<CertificationRecord?>(null) }
    val sheetState        = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val snackbarHost      = remember { SnackbarHostState() }
    val scope             = rememberCoroutineScope()

    val extendedSuccessMsg = stringResource(R.string.cert_extended_success)
    LaunchedEffect(extendState) {
        when (val s = extendState) {
            is ExtendState.Success -> {
                snackbarHost.showSnackbar(extendedSuccessMsg)
                viewModel.resetExtendState()
            }
            is ExtendState.Failure -> {
                snackbarHost.showSnackbar(s.error)
                viewModel.resetExtendState()
            }
            else -> Unit
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.my_certs_title), color = Color.White, fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Black)
            )
        },
        snackbarHost = { SnackbarHost(snackbarHost) },
        containerColor = Color.Black
    ) { padding ->
        if (certifications.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.my_certs_empty_title), color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Text(stringResource(R.string.my_certs_empty_desc), color = Color(0xFF888888), fontSize = 13.sp, textAlign = TextAlign.Center)
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 12.dp)
            ) {
                items(certifications, key = { it.authId }) { record ->
                    CertificationCard(
                        record = record,
                        onClick = { selectedRecord = record }
                    )
                }
            }
        }
    }

    // Detail bottom sheet
    selectedRecord?.let { record ->
        ModalBottomSheet(
            onDismissRequest = { selectedRecord = null },
            sheetState = sheetState,
            containerColor = Color(0xFF1A1A1A)
        ) {
            CertificationDetailContent(
                record = record,
                extendState = extendState,
                onExtend = { viewModel.freeExtend(record.authId) },
                onDelete = {
                    pendingDeleteRecord = record
                    scope.launch { sheetState.hide() }.invokeOnCompletion { selectedRecord = null }
                },
                onClose = {
                    scope.launch { sheetState.hide() }.invokeOnCompletion { selectedRecord = null }
                }
            )
        }
    }

    // Delete confirmation dialog
    pendingDeleteRecord?.let { record ->
        AlertDialog(
            onDismissRequest = { pendingDeleteRecord = null },
            containerColor = Color(0xFF1E1E1E),
            title = { Text(stringResource(R.string.cert_delete_title), color = Color.White, fontWeight = FontWeight.Bold) },
            text = { Text(stringResource(R.string.cert_delete_message, record.authId), color = Color(0xFFDDDDDD), fontSize = 13.sp) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(record)
                    pendingDeleteRecord = null
                }) {
                    Text(stringResource(R.string.cert_delete_confirm), color = Color(0xFFFF6B6B), fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteRecord = null }) {
                    Text(stringResource(R.string.cert_delete_cancel), color = Color(0xFF888888))
                }
            }
        )
    }
}

@Composable
private fun CertificationCard(record: CertificationRecord, onClick: () -> Unit) {
    val now      = System.currentTimeMillis()
    val daysLeft = ((record.expiresAtMs - now) / (24L * 60 * 60 * 1000)).toInt()
    val isExpired = daysLeft < 0

    val thumbnail: Bitmap? = remember(record.thumbnailBase64) {
        record.thumbnailBase64?.runCatching {
            val bytes = Base64.decode(this, Base64.NO_WRAP)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }?.getOrNull()
    }

    val captureDate = remember(record.captureTimestampMs) {
        SimpleDateFormat("yyyy.MM.dd HH:mm", Locale.KOREAN).format(Date(record.captureTimestampMs))
    }

    val expiryLabel = when {
        isExpired    -> stringResource(R.string.cert_status_expired)
        daysLeft == 0 -> stringResource(R.string.cert_status_expires_today)
        else          -> stringResource(R.string.cert_status_days_left, daysLeft)
    }
    val expiryColor = when {
        isExpired    -> Color(0xFF888888)
        daysLeft <= 1 -> Color(0xFFFF6B6B)
        daysLeft <= 3 -> Color(0xFFFFB347)
        else          -> AccentGreen
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (isExpired) 0.55f else 1f)
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF1A1A1A))
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Thumbnail
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Color(0xFF2A2A2A)),
            contentAlignment = Alignment.Center
        ) {
            if (thumbnail != null) {
                Image(
                    bitmap = thumbnail.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Text("?", color = Color(0xFF555555), fontSize = 18.sp)
            }
        }

        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                record.authId,
                color = Color.White,
                fontSize = 14.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold
            )
            Text(record.nickname, color = Color(0xFFAAAAAA), fontSize = 12.sp)
            Text(captureDate, color = Color(0xFF666666), fontSize = 11.sp)
        }

        Text(
            expiryLabel,
            color = expiryColor,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun CertificationDetailContent(
    record: CertificationRecord,
    extendState: ExtendState,
    onExtend: () -> Unit,
    onDelete: () -> Unit,
    onClose: () -> Unit
) {
    val now      = System.currentTimeMillis()
    val daysLeft = ((record.expiresAtMs - now) / (24L * 60 * 60 * 1000)).toInt()
    val isExpired = daysLeft < 0

    val captureDate = remember(record.captureTimestampMs) {
        SimpleDateFormat("yyyy.MM.dd HH:mm", Locale.KOREAN).format(Date(record.captureTimestampMs))
    }
    val expiryDate = remember(record.expiresAtMs) {
        SimpleDateFormat("yyyy.MM.dd", Locale.KOREAN).format(Date(record.expiresAtMs))
    }

    val qrBitmap: Bitmap? = remember(record.authId) {
        runCatching {
            val url  = "${WatermarkComposer.VERIFY_BASE_URL}/${record.authId}"
            val size = 400
            val hints = mapOf(
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
                EncodeHintType.MARGIN to 1
            )
            val matrix = QRCodeWriter().encode(url, BarcodeFormat.QR_CODE, size, size, hints)
            Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).apply {
                for (x in 0 until size) for (y in 0 until size) {
                    setPixel(x, y, if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
                }
            }
        }.getOrNull()
    }

    val isExtendingThis = (extendState as? ExtendState.Loading)?.authId == record.authId

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp)
            .padding(bottom = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Auth ID
        Text(
            record.authId,
            color = AccentGreen,
            fontSize = 22.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            letterSpacing = 2.sp
        )

        // Cert meta
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF111111), RoundedCornerShape(8.dp))
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            DetailRow(label = stringResource(R.string.cert_label_nickname), value = record.nickname)
            DetailRow(label = stringResource(R.string.cert_label_capture_date), value = captureDate)
            DetailRow(
                label = stringResource(R.string.cert_label_expiry),
                value = if (isExpired) stringResource(R.string.cert_expiry_expired, expiryDate)
                        else stringResource(R.string.cert_expiry_with_days, expiryDate, daysLeft),
                valueColor = if (isExpired) Color(0xFF888888) else AccentGreen
            )
        }

        // QR code
        if (qrBitmap != null) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.cert_qr_label), color = Color(0xFF888888), fontSize = 11.sp)
                Image(
                    bitmap = qrBitmap.asImageBitmap(),
                    contentDescription = stringResource(R.string.cert_verify_qr_label),
                    modifier = Modifier
                        .size(180.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.White)
                        .padding(4.dp)
                )
                Text(
                    stringResource(R.string.cert_qr_hint),
                    color = Color(0xFF666666),
                    fontSize = 10.sp,
                    textAlign = TextAlign.Center
                )
            }
        }

        // Extend button: visible when expiry notification fires (≤24h left) or already expired
        if (daysLeft <= 0) {
            Button(
                onClick = onExtend,
                enabled = !isExtendingThis,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor         = AccentGreen,
                    contentColor           = Color.Black,
                    disabledContainerColor = Color(0xFF2A2A2A),
                    disabledContentColor   = Color(0xFF555555)
                )
            ) {
                if (isExtendingThis) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), color = Color.Black, strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.cert_extending), fontWeight = FontWeight.Bold)
                } else {
                    Text(stringResource(R.string.cert_extend_button), fontWeight = FontWeight.Bold)
                }
            }
        }

        // Delete + close
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = onDelete) {
                Icon(Icons.Outlined.Delete, contentDescription = null, tint = Color(0xFF888888), modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.cert_delete_button), color = Color(0xFF888888), fontSize = 12.sp)
            }
            TextButton(onClick = onClose) {
                Text(stringResource(R.string.cert_close), color = Color(0xFF666666), fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String, valueColor: Color = Color.White) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = Color(0xFF888888), fontSize = 12.sp)
        Text(value, color = valueColor, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}
