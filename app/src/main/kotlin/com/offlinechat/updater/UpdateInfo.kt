package com.offlinechat.updater

/**
 * Metadata model for an Over-The-Air (OTA) in-app update.
 */
data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val fileSizeBytes: Long = 0L,
    val changelog: String = ""
)
