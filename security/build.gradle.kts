// :security — Android module.
// Depends on :core for DeviceIdentity.
// Uses Android Keystore, EncryptedSharedPreferences, and standard Java crypto.
// No cloud SDK or third-party crypto beyond what ships with Android.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace  = "com.offlinechat.security"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(project(":core"))

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    // EncryptedSharedPreferences — stores serialised public key + device ID
    implementation(libs.androidx.security.crypto)

    // Hilt DI
    implementation(libs.hilt.android)
    ksp(libs.hilt.android.compiler)
}
