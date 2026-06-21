package org.witness.proofmode

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.lifecycle.viewmodel.compose.viewModel
import org.witness.proofmode.camera.fragments.MyCertificationsScreen
import org.witness.proofmode.camera.fragments.MyCertificationsViewModel

class MyCertificationsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                MyCertificationsScreen(
                    viewModel = viewModel<MyCertificationsViewModel>(),
                    onNavigateBack = { finish() }
                )
            }
        }
    }
}
