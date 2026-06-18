package org.witness.proofmode.verify

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.core.view.WindowCompat

class VerifyActivity : ComponentActivity() {

    private val verifyViewModel: VerifyViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        WindowCompat.setDecorFitsSystemWindows(window, false)

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                VerifyScreen(
                    viewModel      = verifyViewModel,
                    onNavigateBack = { finish() }
                )
            }
        }

        handleIntent(intent)
    }

    // Handles re-delivery when the Activity is already on-screen (singleTop).
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        if (intent.type?.startsWith("image/") != true) return

        val uri: Uri? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }
        // SECURITY: shared URI feeds VerifyViewModel → VerificationService (comparison only).
        // It never touches CameraViewModel, lastCapturedMedia, or CertificationService.
        uri?.let { verifyViewModel.onImageSelected(this, it) }
    }
}
