package org.witness.proofmode.camera.fragments

import android.util.Log
import android.widget.Toast
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.LifecycleOwner
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import org.witness.proofmode.camera.utils.SharedPrefsManager

@Composable
fun CameraNavigation(
    navController: NavHostController,
    viewModel: CameraViewModel,
    lifecycleOwner: LifecycleOwner,
    onClosed: () -> Unit
) {
    val context      = LocalContext.current
    val prefsManager = remember { SharedPrefsManager.newInstance(context) }
    val savedMode    = remember {
        prefsManager.getString(SharedPrefsManager.KEY_CAMERA_MODE, CameraDestinations.PHOTO)
    }

    val cameraHardwareAlert by viewModel.cameraHardwareAlert.collectAsState()
    LaunchedEffect(cameraHardwareAlert) {
        if (cameraHardwareAlert is CameraHardwareAlert.Warning) {
            Toast.makeText(context, (cameraHardwareAlert as CameraHardwareAlert.Warning).reason, Toast.LENGTH_LONG).show()
            viewModel.dismissCameraHardwareAlert()
        }
    }
    if (cameraHardwareAlert is CameraHardwareAlert.Blocked) {
        AlertDialog(
            onDismissRequest = { onClosed() },
            title            = { Text("카메라 사용 불가") },
            text             = { Text((cameraHardwareAlert as CameraHardwareAlert.Blocked).reason) },
            confirmButton    = { TextButton(onClick = { onClosed() }) { Text("확인") } }
        )
    }

    val currentBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination     = currentBackStackEntry?.destination?.route
    LaunchedEffect(currentDestination) {
        when (currentBackStackEntry?.destination?.route) {
            CameraDestinations.PHOTO -> {
                prefsManager.putString(SharedPrefsManager.KEY_CAMERA_MODE, CameraDestinations.PHOTO)
                viewModel.unbindAll()
                viewModel.bindUseCasesForImage(lifecycleOwner)
            }
            CameraDestinations.VIDEO -> {
                prefsManager.putString(SharedPrefsManager.KEY_CAMERA_MODE, CameraDestinations.VIDEO)
                viewModel.unbindAll()
                viewModel.bindUseCasesForVideo(lifecycleOwner)
            }
            CameraDestinations.PREVIEW         -> {}
            CameraDestinations.SESSION_SELECT  -> {}
            CameraDestinations.EDIT            -> {}
        }
    }

    NavHost(navController = navController, startDestination = savedMode) {

        composable(CameraDestinations.PHOTO,
            enterTransition = { fadeIn() },
            exitTransition  = { fadeOut() }
        ) {
            PhotoCamera(
                cameraViewModel      = viewModel,
                lifecycleOwner       = lifecycleOwner,
                onNavigateToPreview  = { navController.navigate(CameraDestinations.PREVIEW) },
                onNavigateToVideo    = {
                    navController.navigate(CameraDestinations.VIDEO) {
                        popUpTo(CameraDestinations.PHOTO) { inclusive = true }
                    }
                },
                onNavigateToSessionSelect = { navController.navigate(CameraDestinations.SESSION_SELECT) },
                onClose              = onClosed
            )
        }

        composable(CameraDestinations.VIDEO,
            enterTransition = { fadeIn() },
            exitTransition  = { fadeOut() }
        ) {
            VideoCamera(
                cameraViewModel         = viewModel,
                lifecycleOwner          = lifecycleOwner,
                onNavigateToPhotoCamera = {
                    navController.navigate(CameraDestinations.PHOTO) {
                        popUpTo(CameraDestinations.VIDEO) { inclusive = true }
                    }
                },
                onNavigateBack      = { navController.popBackStack() },
                onNavigateToPreview = { navController.navigate(CameraDestinations.PREVIEW) },
                onClose             = onClosed
            )
        }

        composable(CameraDestinations.PREVIEW,
            enterTransition = { fadeIn() },
            exitTransition  = { fadeOut() }
        ) {
            MediaPreview(
                viewModel               = viewModel,
                modifier                = Modifier.fillMaxSize(),
                onNavigateBack          = { navController.popBackStack() },
                // "여러 장 인증" in preview also leads to session select
                onNavigateToBatchSelect = {
                    navController.navigate(CameraDestinations.SESSION_SELECT)
                }
            )
        }

        composable(CameraDestinations.SESSION_SELECT,
            enterTransition = { fadeIn() },
            exitTransition  = { fadeOut() }
        ) {
            SessionSelectScreen(
                viewModel            = viewModel,
                // Camera FAB: clear last capture so LaunchedEffect doesn't re-fire, go back to camera
                onNavigateToCamera   = {
                    viewModel.clearLastCapturedMedia()
                    navController.popBackStack()
                },
                // "인증하기": start batch, navigate to EDIT (keep SESSION_SELECT on stack so X goes back here)
                onStartCertification = {
                    navController.navigate(CameraDestinations.EDIT)
                }
            )
        }

        composable(CameraDestinations.EDIT,
            enterTransition = { fadeIn() },
            exitTransition  = { fadeOut() }
        ) {
            PhotoEditScreen(
                viewModel      = viewModel,
                // X: reset state and pop back to SESSION_SELECT
                onNavigateBack = {
                    viewModel.resetBatch()
                    viewModel.resetCertificationState()
                    viewModel.resetWatermarkState()
                    navController.popBackStack()
                },
                onCertDone     = { done, savedEdits ->
                    val isBatch = viewModel.batchQueue.value.isNotEmpty()
                    if (isBatch) {
                        val isLast =
                            viewModel.batchEditIndex.value + 1 >= viewModel.batchQueue.value.size
                        viewModel.acceptBatchCertItem(done, savedEdits)
                        if (isLast) {
                            navController.navigate(CameraDestinations.NICKNAME) {
                                popUpTo(CameraDestinations.EDIT) { inclusive = true }
                            }
                        }
                        // If not last, PhotoEditScreen auto-advances via LaunchedEffect(uri).
                    } else {
                        navController.navigate(CameraDestinations.NICKNAME) {
                            popUpTo(CameraDestinations.EDIT) { inclusive = true }
                        }
                    }
                }
            )
        }

        composable(CameraDestinations.NICKNAME,
            enterTransition = { fadeIn() },
            exitTransition  = { fadeOut() }
        ) {
            NicknameInputScreen(
                viewModel      = viewModel,
                onConfirmed    = {
                    val isBatch = viewModel.batchQueue.value.isNotEmpty()
                    if (isBatch) {
                        navController.navigate(CameraDestinations.BATCH_SHARE) {
                            popUpTo(CameraDestinations.NICKNAME) { inclusive = true }
                        }
                    } else {
                        viewModel.resetUploadState()
                        navController.navigate(CameraDestinations.SHARE) {
                            popUpTo(CameraDestinations.NICKNAME) { inclusive = true }
                        }
                    }
                },
                onNavigateBack = {
                    viewModel.resetCertificationState()
                    viewModel.resetUploadState()
                    viewModel.resetWatermarkState()
                    viewModel.resetBatch()
                    viewModel.clearLastCapturedMedia()
                    navController.navigate(CameraDestinations.PHOTO) {
                        popUpTo(0) { inclusive = true }
                    }
                }
            )
        }

        composable(CameraDestinations.SHARE,
            enterTransition = { fadeIn() },
            exitTransition  = { fadeOut() }
        ) {
            CertificationShareScreen(
                viewModel = viewModel,
                onDone    = {
                    viewModel.resetCertificationState()
                    viewModel.resetUploadState()
                    viewModel.resetWatermarkState()
                    onClosed()
                }
            )
        }

        composable(CameraDestinations.BATCH_SHARE,
            enterTransition = { fadeIn() },
            exitTransition  = { fadeOut() }
        ) {
            BatchShareScreen(
                viewModel = viewModel,
                onDone    = {
                    viewModel.resetBatch()
                    viewModel.resetCertificationState()
                    viewModel.resetUploadState()
                    viewModel.resetWatermarkState()
                    viewModel.clearLastCapturedMedia()
                    onClosed()
                }
            )
        }
    }
}

object CameraDestinations {
    const val PHOTO          = "photo"
    const val VIDEO          = "video"
    const val PREVIEW        = "preview"
    const val SESSION_SELECT = "session_select"
    const val EDIT           = "edit"
    const val NICKNAME       = "nickname"
    const val SHARE          = "share"
    const val BATCH_SELECT   = "batch_select"   // kept for compat, unused in main flow
    const val BATCH_SHARE    = "batch_share"
}
