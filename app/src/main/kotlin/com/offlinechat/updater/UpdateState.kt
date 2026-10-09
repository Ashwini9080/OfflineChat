package com.offlinechat.updater

import java.io.File

/**
 * UI State representation for the In-App Auto-Updater workflow.
 */
sealed interface UpdateState {
    /** Idle / no active update check */
    data object Idle : UpdateState

    /** Actively querying dev server for update.json */
    data object Checking : UpdateState

    /** A newer versionCode was detected on the server */
    data class UpdateAvailable(val info: UpdateInfo) : UpdateState

    /** Download in progress with live percentage & bytes */
    data class Downloading(
        val info: UpdateInfo,
        val progress: Float, // 0.0f .. 1.0f
        val downloadedBytes: Long,
        val totalBytes: Long
    ) : UpdateState

    /** APK downloaded to cache and ready to be handed to Android installer */
    data class ReadyToInstall(
        val info: UpdateInfo,
        val apkFile: File
    ) : UpdateState

    /** Server reached and app is already on the newest build */
    data object UpToDate : UpdateState

    /** Error encountered during check or download */
    data class Error(val message: String) : UpdateState
}
