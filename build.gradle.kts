// Root build file — only plugin declarations; no configuration here.
// Each module's build.gradle.kts applies plugins it needs.
plugins {
    alias(libs.plugins.android.application)  apply false
    alias(libs.plugins.android.library)      apply false
    alias(libs.plugins.kotlin.android)       apply false
    alias(libs.plugins.kotlin.jvm)           apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.compose)       apply false
    alias(libs.plugins.hilt)                 apply false
    alias(libs.plugins.ksp)                  apply false
}
