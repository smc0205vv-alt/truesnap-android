package org.witness.proofmode.camera.fragments

import androidx.compose.ui.graphics.Color

// Legacy brand color (retained as part of the module's public surface).
val ColorPrimary = Color(0xFF003f6b)

// ---------------------------------------------------------------------------
// TrueSnap camera palette — a clean monochrome "pro" look with a single
// sky-blue accent. The blue matches the main app (colorPrimary / colorAccent
// = #29B6F6) and is used sparingly: the shutter ring, active control states and
// the focus reticle. Everything else stays strictly black & white.
// ---------------------------------------------------------------------------

/** Primary sky-blue accent (matches app `colorPrimary` / `colorAccent`). */
val AccentGreen = Color(0xFF29B6F6)

/** Slightly deeper blue for pressed / secondary accent moments. */
val AccentGreenDark = Color(0xFF0288D1)

/** True black viewfinder backdrop. */
val CameraBlack = Color(0xFF000000)

/** Dark surface for sheets, dialogs and elevated chrome. */
val CameraSurface = Color(0xFF121212)
val CameraSurfaceVariant = Color(0xFF1E1E1E)

/** Translucent dark fill for secondary circular controls. */
val ControlSurface = Color(0x59000000) // ~35% black

/** Hairline border for secondary controls and framed thumbnails. */
val ControlBorder = Color(0x80FFFFFF) // ~50% white

/** De-emphasised icon/label tint for inactive controls. */
val InactiveWhite = Color(0x99FFFFFF) // ~60% white

/** Thin grid overlay lines. */
val GridLine = Color(0x4DFFFFFF) // ~30% white
