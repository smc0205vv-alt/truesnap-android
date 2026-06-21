package org.witness.proofmode.camera.fragments

import android.os.Bundle
import androidx.core.view.WindowCompat
import com.yalantis.ucrop.UCropActivity

/**
 * Thin wrapper around UCropActivity that opts out of edge-to-edge before
 * the parent sets up its layout, so the toolbar and confirm button are
 * never hidden behind the system status bar on Android 15+ (targetSdk 36).
 */
class TrueSnapCropActivity : UCropActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        WindowCompat.setDecorFitsSystemWindows(window, true)
        super.onCreate(savedInstanceState)
    }
}
