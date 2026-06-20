package org.witness.proofmode.camera.fragments

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import org.witness.proofmode.camera.adapter.Media

@Composable
fun SessionSelectScreen(
    viewModel: CameraViewModel,
    onNavigateToCamera: () -> Unit,
    onStartCertification: () -> Unit
) {
    val mediaFiles by viewModel.mediaFiles.collectAsStateWithLifecycle()
    val photoItems = mediaFiles.filter { !it.isVideo }

    var selected by remember { mutableStateOf<Set<Media>>(emptySet()) }
    var initialSelectionDone by remember { mutableStateOf(false) }

    // Default: select all photos once the list loads.
    LaunchedEffect(photoItems) {
        if (!initialSelectionDone && photoItems.isNotEmpty()) {
            selected = photoItems.toSet()
            initialSelectionDone = true
        }
    }

    val selectionFull = selected.size >= MAX_BATCH_SIZE

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
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "세션 미리보기",
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.weight(1f))
            Text(
                if (selected.isEmpty()) "선택 없음"
                else "${selected.size}장 선택됨  (최대 ${MAX_BATCH_SIZE}장)",
                color = if (selected.isEmpty()) Color(0xFF888888)
                        else if (selectionFull) AccentGreen
                        else Color(0xFFAAAAAA),
                fontSize = 12.sp
            )
        }

        // Photo grid
        if (photoItems.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "세션 사진이 없습니다.\n카메라로 먼저 촬영해주세요.",
                    color = Color(0xFF888888),
                    fontSize = 14.sp
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(2.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                items(photoItems, key = { it.uri.toString() }) { media ->
                    val isSelected = media in selected
                    val canSelect  = isSelected || !selectionFull

                    Box(
                        modifier = Modifier
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(4.dp))
                            .clickable(enabled = canSelect) {
                                selected = if (isSelected) selected - media else selected + media
                            }
                    ) {
                        AsyncImage(
                            model = media.uri,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )

                        // Dim non-selectable items
                        if (!canSelect) {
                            Box(
                                Modifier
                                    .fillMaxSize()
                                    .background(Color.Black.copy(alpha = 0.5f))
                            )
                        }

                        // Selection badge (top-right)
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(6.dp)
                                .size(24.dp)
                                .clip(CircleShape)
                                .background(
                                    if (isSelected) AccentGreen
                                    else Color.Black.copy(alpha = 0.55f)
                                )
                                .border(
                                    1.5.dp,
                                    if (isSelected) AccentGreen
                                    else Color.White.copy(alpha = 0.7f),
                                    CircleShape
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            if (isSelected) {
                                Text(
                                    "${selected.toList().indexOf(media) + 1}",
                                    color = Color.Black,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }
        }

        // Bottom bar: certify button + camera return button
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF111111))
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = {
                    if (selected.isEmpty()) return@Button
                    viewModel.initBatch(selected.toList())
                    onStartCertification()
                },
                modifier = Modifier.weight(1f),
                enabled = selected.isNotEmpty(),
                colors = ButtonDefaults.buttonColors(
                    containerColor          = AccentGreen,
                    contentColor            = Color.Black,
                    disabledContainerColor  = Color(0xFF2A2A2A),
                    disabledContentColor    = Color(0xFF555555)
                )
            ) {
                Text(
                    if (selected.isEmpty()) "사진을 선택해주세요"
                    else "${selected.size}장 인증하기",
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )
            }

            // "추가 촬영" — return to camera to take more photos
            IconButton(
                onClick = onNavigateToCamera,
                modifier = Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF2A2A2A))
            ) {
                Icon(
                    Icons.Filled.CameraAlt,
                    contentDescription = "추가 촬영",
                    tint = Color.White,
                    modifier = Modifier.size(26.dp)
                )
            }
        }
    }
}
