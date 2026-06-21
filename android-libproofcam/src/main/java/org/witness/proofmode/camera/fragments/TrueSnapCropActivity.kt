package org.witness.proofmode.camera.fragments

import android.graphics.Color
import android.os.Bundle
import android.view.WindowManager
import androidx.core.view.WindowCompat
import com.yalantis.ucrop.UCropActivity

/**
 * Wrapper around UCropActivity that prevents both the status bar (top) and
 * navigation bar (bottom) from overlapping the crop UI on Android 15+.
 *
 * Key steps — all before super.onCreate() so they apply before UCrop's
 * setContentView() runs:
 *  1. setDecorFitsSystemWindows(true) → system adds inset padding to root view
 *  2. Clear FLAG_TRANSLUCENT_NAVIGATION → nav bar is not see-through
 *  3. Add FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS → we control bar colors
 *  4. Set opaque black nav bar so the bar itself is visible and not confused
 *     with a transparent overlay
 */
class TrueSnapCropActivity : UCropActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        WindowCompat.setDecorFitsSystemWindows(window, true)
        window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION)
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        window.navigationBarColor = Color.BLACK
        super.onCreate(savedInstanceState)
    }
}
