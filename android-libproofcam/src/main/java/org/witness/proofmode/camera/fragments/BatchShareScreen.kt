package org.witness.proofmode.camera.fragments

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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

@Composable
fun BatchShareScreen(
    viewModel: CameraViewModel,
    onDone: () -> Unit
) {
    val context = LocalContext.current
    val batchUploadState by viewModel.batchUploadState.collectAsStateWithLifecycle()
    val items = (batchUploadState as? BatchUploadState.Finished)?.items ?: emptyList()
    val successItems = items.filter { it.uploadSuccess && it.watermarkBitmap != null }

    // Tracks whether "save all" was initiated via permission request (API < Q only)
    var pendingSaveAll by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted && pendingSaveAll) {
            pendingSaveAll = false
            performSaveAll(context, successItems)
        } else if (!granted) {
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
                "${successItems.size} / ${items.size}건 인증 완료",
                color = AccentGreen,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
            val failCount = items.size - successItems.size
            if (failCount > 0) {
                Text(
                    "${failCount}건 전송 실패",
                    color = Color(0xFFFF6B6B),
                    fontSize = 13.sp
                )
            }
        }

        // Per-item result list
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(items, key = { it.certDone.authId }) { item ->
                BatchResultRow(item = item, context = context)
            }
        }

        // Bottom actions
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF111111))
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (successItems.isNotEmpty()) {
                // Batch share all
                Button(
                    onClick = {
                        val uris = successItems.mapNotNull { it.shareUri }
                        if (uris.isEmpty()) return@Button
                        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                            type = "image/jpeg"
                            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(Intent.createChooser(intent, "전체 공유"))
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AccentGreen,
                        contentColor   = Color.Black
                    )
                ) {
                    Text("전체 공유 (${successItems.size}장)", fontWeight = FontWeight.Bold)
                }

                // Save all to gallery
                OutlinedButton(
                    onClick = {
                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                            pendingSaveAll = true
                            permissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                        } else {
                            performSaveAll(context, successItems)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                ) {
                    Text("갤러리에 모두 저장 (${successItems.size}장)")
                }
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

private fun performSaveAll(context: Context, successItems: List<BatchItem>) {
    var savedCount = 0
    successItems.forEach { item ->
        val bmp = item.watermarkBitmap ?: return@forEach
        val msg = saveToGallery(context, bmp, item.certDone.authId)
        if (msg.contains("완료") || msg.contains("저장")) savedCount++
    }
    Toast.makeText(context, "${savedCount}장 갤러리에 저장됐습니다", Toast.LENGTH_LONG).show()
}

@Composable
private fun BatchResultRow(item: BatchItem, context: Context) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF1A1A1A), RoundedCornerShape(8.dp))
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Thumbnail (watermarked)
        val bmp = item.watermarkBitmap
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(60.dp)
                    .background(Color(0xFF2A2A2A), RoundedCornerShape(4.dp))
            )
        } else {
            Box(
                modifier = Modifier
                    .size(60.dp)
                    .background(Color(0xFF2A2A2A), RoundedCornerShape(4.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text("—", color = Color(0xFF555555), fontSize = 18.sp)
            }
        }

        // Auth ID + status
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                item.certDone.authId,
                color = Color.White,
                fontSize = 14.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold
            )
            if (item.uploadSuccess) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        Icons.Filled.Done,
                        contentDescription = null,
                        tint = AccentGreen,
                        modifier = Modifier.size(14.dp)
                    )
                    Text("전송 완료", color = AccentGreen, fontSize = 12.sp)
                }
            } else {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        Icons.Filled.Warning,
                        contentDescription = null,
                        tint = Color(0xFFFF6B6B),
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        item.uploadError?.take(40) ?: "전송 실패",
                        color = Color(0xFFFF6B6B),
                        fontSize = 12.sp
                    )
                }
            }
        }

        // Individual share button
        if (item.uploadSuccess && item.shareUri != null) {
            IconButton(
                onClick = {
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "image/jpeg"
                        putExtra(Intent.EXTRA_STREAM, item.shareUri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(intent, "공유하기"))
                },
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    Icons.Filled.Share,
                    contentDescription = "공유",
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }
        } else {
            Spacer(Modifier.size(36.dp))
        }
    }
}
