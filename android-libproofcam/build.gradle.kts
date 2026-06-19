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
val truesnapApiKey: String = localProps.getProperty("TRUESNAP_API_KEY")
    ?: error("TRUESNAP_API_KEY not found in local.properties")

android {
    compileSdk = 36
    namespace = "org.witness.proofmode.camera"

    defaultConfig {
        minSdk = 28
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "TRUESNAP_API_KEY", "\"$truesnapApiKey\"")
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

}
