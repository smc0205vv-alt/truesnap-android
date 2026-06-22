import java.util.Base64
import java.util.Properties

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.navigation.safeargs)
    alias(libs.plugins.kotlin.compose)
}

// API key is read from local.properties (git-ignored) at compile time and baked
// into BuildConfig.TRUESNAP_API_KEY so it never appears in source code.
// For CI, write the key into local.properties before the build step, e.g.:
//   echo "TRUESNAP_API_KEY=${{ secrets.TRUESNAP_API_KEY }}" >> local.properties
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.canRead()) f.inputStream().use { load(it) }
}
val rawApiKey: String = localProps.getProperty("TRUESNAP_API_KEY")
    ?: error("TRUESNAP_API_KEY not found in local.properties")

// Split the key: p1 is stored plain, p2 is XOR-obfuscated and Base64-encoded.
// Decompiling the APK yields two opaque strings; reconstruction requires knowing
// the XOR salt embedded in native/Kotlin code. Step up to NDK for stronger hiding.
val apiKeyP1: String = rawApiKey.take(32)
val apiKeyP2Bytes: ByteArray = rawApiKey.drop(32).toByteArray(Charsets.US_ASCII)
val xorSalt = byteArrayOf(0x4b, 0x32, 0x9a.toByte(), 0x1c, 0x7f, 0xe3.toByte(), 0x55, 0x8d.toByte())
val apiKeyP2Xored = apiKeyP2Bytes.mapIndexed { i, b ->
    (b.toInt() xor xorSalt[i % xorSalt.size].toInt()).toByte()
}.toByteArray()
val apiKeyP2B64: String = Base64.getEncoder().encodeToString(apiKeyP2Xored)

android {
    compileSdk = 36
    namespace = "org.witness.proofmode.camera"

    defaultConfig {
        minSdk = 28
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "TRUESNAP_API_KEY_P1",  "\"$apiKeyP1\"")
        buildConfigField("String", "TRUESNAP_API_KEY_P2X", "\"$apiKeyP2B64\"")
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        viewBinding = true
        compose = true
        buildConfig = true
    }
}

dependencies {

    implementation(libs.androidx.localbroadcastmanager)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.preference.ktx)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    // Optional - Add window size utils
    implementation(libs.androidx.compose.material3.adaptive)

    // Optional - Integration with activities
    implementation(libs.androidx.activity.compose)
    // Optional - Integration with ViewModels
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.constraintlayout.compose)
    implementation(libs.kotlin.stdlib)

    implementation(project(":android-libproofmode"))

    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.google.material)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.constraintlayout)

    implementation(libs.bundles.camerax)

    implementation(libs.accompanist.permissions)

    implementation(libs.androidx.fragment)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.bundles.navigation)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.viewpager2)
    implementation(libs.kotlinx.coroutines.guava)

    implementation(libs.bundles.coil)

    //logging
    implementation(libs.timber)

    // HTTP client for certification upload
    implementation(libs.okhttp)

    // QR code generation for certification result watermark
    implementation(libs.zxing.core)

    // EXIF orientation correction when loading captured JPEGs
    implementation(libs.androidx.exifinterface)

    // UCrop — crop UI for the photo edit screen
    implementation(libs.ucrop)

    // Extended Material Icons (Crop, RotateRight, Flip) for photo edit controls
    implementation(libs.androidx.compose.material.icons.extended)

    // Room — local certification history
    implementation(libs.bundles.room)
    ksp(libs.androidx.room.compiler)

}
