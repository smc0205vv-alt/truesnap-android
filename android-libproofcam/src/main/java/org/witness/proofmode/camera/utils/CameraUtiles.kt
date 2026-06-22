package org.witness.proofmode.camera.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.net.Uri
import android.util.Size
import android.webkit.MimeTypeMap
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraProvider
import androidx.camera.core.CameraSelector
import androidx.camera.core.DynamicRange
import androidx.camera.core.ImageCapture
import androidx.camera.video.Quality
import androidx.camera.video.Recorder
import androidx.compose.material.icons.Icons
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import org.witness.proofmode.camera.R
import org.witness.proofmode.camera.adapter.Media
import java.io.File

sealed class CameraTrustResult {
    object Trusted : CameraTrustResult()
    data class Suspicious(val reason: String) : CameraTrustResult()
    data class Blocked(val reason: String) : CameraTrustResult()
}

/**
 * Validates that the selected camera is a real hardware camera (front/back).
 * Returns [CameraTrustResult.Blocked] for external/virtual cameras
 * (LENS_FACING_EXTERNAL, HARDWARE_LEVEL_EXTERNAL) and
 * [CameraTrustResult.Suspicious] for legacy drivers.
 * Call this before starting a certification session.
 */
@OptIn(ExperimentalCamera2Interop::class)
fun validateCameraHardware(
    cameraSelector: CameraSelector,
    cameraProvider: CameraProvider
): CameraTrustResult {
    val targetLens = if (cameraSelector == CameraSelector.DEFAULT_FRONT_CAMERA)
        CameraMetadata.LENS_FACING_FRONT else CameraMetadata.LENS_FACING_BACK

    val cameraInfo = cameraProvider.availableCameraInfos.firstOrNull {
        Camera2CameraInfo.from(it)
            .getCameraCharacteristic(CameraCharacteristics.LENS_FACING) == targetLens
    } ?: return CameraTrustResult.Blocked("선택한 카메라를 찾을 수 없습니다")

    val cam2 = Camera2CameraInfo.from(cameraInfo)

    val facing = cam2.getCameraCharacteristic(CameraCharacteristics.LENS_FACING)
    if (facing == CameraMetadata.LENS_FACING_EXTERNAL) {
        return CameraTrustResult.Blocked("외부/가상 카메라는 인증에 사용할 수 없습니다")
    }

    val hwLevel = cam2.getCameraCharacteristic(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)
    if (hwLevel == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL) {
        return CameraTrustResult.Blocked("외부 하드웨어 카메라는 인증에 사용할 수 없습니다")
    }
    if (hwLevel == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY) {
        return CameraTrustResult.Suspicious("레거시 카메라 드라이버가 감지됐습니다. 인증 신뢰도가 낮게 평가될 수 있습니다")
    }

    return CameraTrustResult.Trusted
}

@OptIn(ExperimentalCamera2Interop::class)
fun getSupportedQualities(
    cameraSelector: CameraSelector,
    cameraProvider: CameraProvider): List<Quality> {
    val cameraMetadataLens = if (cameraSelector == CameraSelector.DEFAULT_FRONT_CAMERA) CameraMetadata.LENS_FACING_FRONT else CameraMetadata.LENS_FACING_BACK

    val cameraInfo = cameraProvider.availableCameraInfos.firstOrNull {
        Camera2CameraInfo
            .from(it)
            .getCameraCharacteristic(CameraCharacteristics.LENS_FACING) == cameraMetadataLens
    }?: return emptyList()

    val videoCapabilities = Recorder.getVideoCapabilities(cameraInfo)
    val supportedDynamicRanges = videoCapabilities.supportedDynamicRanges
    val dynamicRange = if (supportedDynamicRanges.contains(DynamicRange.HDR10_10_BIT)) DynamicRange.HDR10_10_BIT else DynamicRange.SDR
    return videoCapabilities.getSupportedQualities(dynamicRange)
        .filter { quality ->
            try {
                videoCapabilities.isQualitySupported(quality,dynamicRange)
            }catch (ex:Exception) {
                false
            }
        }
}

suspend fun getWorkingQualitiesForCameras(){

}

fun isUltraHdrSupported(cameraSelector: CameraSelector, cameraProvider: CameraProvider): Boolean {
    val cameraInfo = cameraProvider.getCameraInfo(cameraSelector)
    return ImageCapture.getImageCaptureCapabilities(cameraInfo)
        .supportedOutputFormats
        .contains(ImageCapture.OUTPUT_FORMAT_JPEG_ULTRA_HDR)

}

fun Quality.getName():String {
    return when (this) {
        Quality.UHD -> "4K(UHD)"
        Quality.FHD -> "1080p(FHD)"
        Quality.HD -> "720p(HD)"
        Quality.SD -> "480p(SD)"
        else -> this.toString()
    }
}

fun getQualityFromName(name: String): Quality {
    return when (name) {
        "4K(UHD)" -> Quality.UHD
        "1080p(FHD)" -> Quality.FHD
        "720p(HD)" -> Quality.HD
        "480p(SD)" -> Quality.SD
        else -> throw IllegalArgumentException("Invalid quality name: $name")
    }
}

fun Quality.toIconRes(): Int {
    return when (this) {
        Quality.UHD -> R.drawable.ic_4k
        Quality.FHD -> R.drawable.ic_fhd
        Quality.HD -> R.drawable.ic_hd
        Quality.SD -> R.drawable.ic_sd
        else -> throw IllegalStateException("Icon resource not found")
    }
}

@Composable
fun flashModeToIconRes(flashMode: Int): ImageVector {
    return when (flashMode) {
        ImageCapture.FLASH_MODE_OFF -> ImageVector.vectorResource(R.drawable.ic_flash_off)
        ImageCapture.FLASH_MODE_ON -> ImageVector.vectorResource(R.drawable.ic_flash_on)
        ImageCapture.FLASH_MODE_AUTO -> ImageVector.vectorResource(R.drawable.ic_flash_auto)
        else -> ImageVector.vectorResource(R.drawable.ic_flash_off)
    }


}

fun getMediaFlow(context: Context, capturesDir: File): Flow<List<Media>> = flow {
    emit(emptyList())
    emit(listCaptureFiles(context, capturesDir))
}.flowOn(Dispatchers.IO)

private fun listCaptureFiles(context: Context, capturesDir: File): List<Media> {
    val authority = context.applicationContext.packageName + ".provider"
    return (capturesDir.listFiles() ?: emptyArray())
        .filter { it.isFile && (it.extension == "jpg" || it.extension == "mp4") }
        .sortedByDescending { it.lastModified() }
        .map { file ->
            val uri = FileProvider.getUriForFile(context, authority, file)
            Media(uri, file.extension == "mp4", file.lastModified())
        }
}

/**
 * Get a thumbnail for a video.
 *
 * @param context The application context.
 * @param videoUri The Uri of the video The uri needs to be a content uri to work.
 * @return A Bitmap thumbnail.
 */
fun getVideoThumbnail(context: Context, videoUri: Uri, size: ThumbSize = ThumbSize.SMALL): Bitmap {
    try {
        val retriever = android.media.MediaMetadataRetriever()
        retriever.setDataSource(context, videoUri)
        val frame = retriever.getFrameAtTime(0)
        retriever.release()
        if (frame != null) return frame
    } catch (e: Exception) {
        // fall through to placeholder
    }
    return BitmapFactory.decodeResource(context.resources, R.drawable.ic_no_picture)
}


enum class ThumbSize {
    SMALL,
    LARGE
}

