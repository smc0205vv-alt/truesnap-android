package org.witness.proofmode.camera.fragments

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.widget.Toast
import timber.log.Timber

@Composable
fun CertificationShareScreen(
    viewModel: CameraViewModel,
    onDone: () -> Unit
) {
    val context      = LocalContext.current
    val certState    by viewModel.certificationState.collectAsStateWithLifecycle()
    val watermarkState by viewModel.watermarkState.collectAsStateWithLifecycle()
    val doneState    = certState as? CertificationState.Done

    var saveMessage  by remember { mutableStateOf<String?>(null) }

    // Runtime WRITE_EXTERNAL_STORAGE permission needed only on API 28 (minSdk).
    // API 29+ can write to MediaStore without permission.
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            val ready = watermarkState as? WatermarkState.Ready ?: return@rememberLauncherForActivityResult
            val msg = saveToGallery(context, ready.bitmap, doneState?.authId ?: "unknown")
            saveMessage = msg
            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
        } else {
            saveMessage = "저장 권한이 거부되었습니다"
            Toast.makeText(context, "저장 권한이 거부되었습니다", Toast.LENGTH_SHORT).show()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        // Header
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                "인증 완료",
                color = AccentGreen,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
            if (doneState != null) {
                Text(
                    doneState.authId,
                    color = Color.White,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 2.sp
                )
            }
        }

        // Watermarked image preview
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            contentAlignment = Alignment.Center
        ) {
            when (val state = watermarkState) {
                WatermarkState.Idle, WatermarkState.Generating -> {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = AccentGreen)
                        Spacer(Modifier.height(12.dp))
                        Text("워터마크 생성 중…", color = Color.White, fontSize = 14.sp)
                    }
                }
                is WatermarkState.Ready -> {
                    Image(
                        bitmap = state.bitmap.asImageBitmap(),
                        contentDescription = "인증 워터마크 이미지",
                        contentScale = ContentScale.FillWidth,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                is WatermarkState.Failed -> {
                    Text(
                        "워터마크 생성 실패: ${state.error}",
                        color = Color(0xFFFF6B6B),
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
        }

        // Save status feedback
        if (saveMessage != null) {
            Text(
                saveMessage!!,
                color = if (saveMessage!!.contains("완료") || saveMessage!!.contains("저장")) AccentGreen
                        else Color(0xFFFF6B6B),
                fontSize = 13.sp,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(bottom = 4.dp)
            )
        }

        // Action buttons
        val readyState = watermarkState as? WatermarkState.Ready
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF111111))
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Share — primary action
            Button(
                onClick = {
                    readyState?.let { state ->
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "image/jpeg"
                            putExtra(Intent.EXTRA_STREAM, state.shareUri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(Intent.createChooser(intent, "공유하기"))
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = readyState != null,
                colors = ButtonDefaults.buttonColors(
                    containerColor         = AccentGreen,
                    contentColor           = Color.Black,
                    disabledContainerColor = Color(0xFF2A2A2A),
                    disabledContentColor   = Color(0xFF555555)
                )
            ) {
                Text("공유하기", fontWeight = FontWeight.Bold)
            }

            // Save to gallery
            OutlinedButton(
                onClick = {
                    readyState?.let { state ->
                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                            permissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                        } else {
                            val msg = saveToGallery(context, state.bitmap, doneState?.authId ?: "unknown")
                            saveMessage = msg
                            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = readyState != null,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
            ) {
                Text("갤러리에 저장")
            }

            TextButton(
                onClick = onDone,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            ) {
                Text("홈으로 돌아가기", color = Color(0xFF888888))
            }
        }
    }
}

// ── MediaStore helper ─────────────────────────────────────────────────────────

internal fun saveToGallery(context: Context, bitmap: Bitmap, authId: String): String {
    val filename = "truesnap_$authId.jpg"
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, filename)
        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/TrueSnap")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
    }
    val resolver = context.contentResolver
    val uri: Uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        ?: return "갤러리 저장 실패 (URI 생성 오류)"
    return try {
        resolver.openOutputStream(uri)?.use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        }
        "갤러리 저장 완료 (Pictures/TrueSnap)"
    } catch (e: Exception) {
        Timber.e(e, "Failed to save watermarked image to gallery")
        resolver.delete(uri, null, null)
        "갤러리 저장 실패: ${e.message?.take(50)}"
    }
}
