package org.witness.proofmode

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.preference.PreferenceManager
import org.witness.proofmode.camera.CameraActivity
import org.witness.proofmode.verify.VerifyActivity

class HomeActivity : ComponentActivity() {

    private val requestNotificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* granted or denied — proceed silently either way */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // First launch → show onboarding, then come back here
        if (!isOnboardingCompleted(this)) {
            startActivity(Intent(this, TrueSnapOnboardingActivity::class.java))
            finish()
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
        ) {
            requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        if (intent?.action == "org.witness.proofmode.OPEN_CAMERA") {
            launchCamera()
        }

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                HomeScreen(
                    onOpenCamera = ::launchCamera,
                    onOpenVerify = ::launchVerify,
                    onOpenMyCerts = ::launchMyCertifications
                )
            }
        }
    }

    private fun launchCamera() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val intent = Intent(this, CameraActivity::class.java).apply {
            putExtra(ProofMode.PREF_OPTION_CREDENTIALS, (application as ProofModeApp).useContentCredentials())
            putExtra(ProofMode.PREF_OPTION_BLOCK_AI, prefs.getBoolean(ProofMode.PREF_OPTION_BLOCK_AI, ProofMode.PREF_OPTION_AI_DEFAULT))
        }
        startActivity(intent)
    }

    private fun launchVerify() {
        startActivity(Intent(this, VerifyActivity::class.java))
    }

    private fun launchMyCertifications() {
        startActivity(Intent(this, MyCertificationsActivity::class.java))
    }
}

private val AccentTeal = Color(0xFF3CCFC2)
private val TextSub = Color(0xFFAAAAAA)

@Composable
private fun HomeScreen(
    onOpenCamera: () -> Unit,
    onOpenVerify: () -> Unit,
    onOpenMyCerts: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.weight(1.4f))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(
                painter = painterResource(R.drawable.ic_truesnap_logo),
                contentDescription = null,
                modifier = Modifier.size(44.dp)
            )
            Spacer(Modifier.width(12.dp))
            Text(
                "TrueSnap",
                color = AccentTeal,
                fontSize = 40.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 1.sp
            )
        }

        Spacer(Modifier.height(24.dp))

        Text(
            stringResource(R.string.home_subtitle),
            color = Color.White,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(10.dp))

        Text(
            stringResource(R.string.home_tagline),
            color = TextSub,
            fontSize = 14.sp,
            fontWeight = FontWeight.Normal,
            textAlign = TextAlign.Center,
            lineHeight = 22.sp
        )

        Spacer(Modifier.weight(2f))

        Button(
            onClick = onOpenCamera,
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = AccentTeal,
                contentColor = Color.Black
            )
        ) {
            Text(
                stringResource(R.string.action_take_photo),
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(Modifier.height(14.dp))

        OutlinedButton(
            onClick = onOpenVerify,
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = AccentTeal
            ),
            border = androidx.compose.foundation.BorderStroke(1.5.dp, AccentTeal)
        ) {
            Text(
                stringResource(R.string.action_verify),
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold
            )
        }

        Spacer(Modifier.height(16.dp))

        TextButton(
            onClick = onOpenMyCerts,
            modifier = Modifier.align(Alignment.CenterHorizontally)
        ) {
            Icon(
                Icons.AutoMirrored.Outlined.List,
                contentDescription = null,
                tint = AccentTeal,
                modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                stringResource(R.string.action_my_certs),
                color = AccentTeal,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )
        }

        Spacer(Modifier.height(36.dp))
    }
}
