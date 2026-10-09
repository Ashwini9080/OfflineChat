package com.offlinechat.updater

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import com.offlinechat.data.local.preferences.AppPreferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "AppUpdateManager"

@Singleton
class AppUpdateManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferencesDataStore: AppPreferencesDataStore
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _updateState = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val updateState: StateFlow<UpdateState> = _updateState.asStateFlow()

    val currentVersionCode: Int by lazy {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode.toInt()
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0).versionCode
            }
        } catch (e: Exception) {
            1
        }
    }

    val currentVersionName: String by lazy {
        try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1.0.0"
        } catch (e: Exception) {
            "1.0.0"
        }
    }

    /**
     * Checks the development server for update metadata.
     *
     * @param silent If true, suppresses [UpdateState.UpToDate] and [UpdateState.Error] to avoid disrupting user.
     * @param forceUpdate If true, offers the update even if versionCode is identical (useful for dev testing).
     */
    fun checkForUpdates(silent: Boolean = false, forceUpdate: Boolean = false) {
        scope.launch {
            if (_updateState.value is UpdateState.Checking || _updateState.value is UpdateState.Downloading) {
                return@launch
            }

            if (!silent) {
                _updateState.value = UpdateState.Checking
            }

            try {
                val serverBaseUrl = preferencesDataStore.updateServerUrl.first().trim().trimEnd('/')
                val targetEndpoint = if (serverBaseUrl.endsWith(".json")) {
                    serverBaseUrl
                } else {
                    "$serverBaseUrl/update.json"
                }

                Log.d(TAG, "Checking for update at: $targetEndpoint")
                val url = URL(targetEndpoint)
                val connection = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 4000
                    readTimeout = 5000
                    useCaches = false
                }

                val responseCode = connection.responseCode
                if (responseCode != HttpURLConnection.HTTP_OK) {
                    throw IllegalStateException("Server returned HTTP $responseCode")
                }

                val rawResponseBody = connection.inputStream.bufferedReader().use { it.readText() }
                connection.disconnect()

                val cleanJson = rawResponseBody.trim().removePrefix("\uFEFF")
                val json = JSONObject(cleanJson)
                val remoteVersionCode = json.getInt("versionCode")
                val remoteVersionName = json.optString("versionName", "v$remoteVersionCode")
                var apkUrl = json.getString("apkUrl")
                val fileSizeBytes = json.optLong("fileSizeBytes", 0L)
                val changelog = json.optString("changelog", "Bug fixes and improvements")

                // Resolve relative APK URLs if needed
                if (!apkUrl.startsWith("http://") && !apkUrl.startsWith("https://")) {
                    apkUrl = "$serverBaseUrl/${apkUrl.trimStart('/')}"
                }

                val updateInfo = UpdateInfo(
                    versionCode = remoteVersionCode,
                    versionName = remoteVersionName,
                    apkUrl = apkUrl,
                    fileSizeBytes = fileSizeBytes,
                    changelog = changelog
                )

                if (remoteVersionCode > currentVersionCode || forceUpdate) {
                    Log.i(TAG, "Update available: remote=$remoteVersionCode, current=$currentVersionCode")
                    _updateState.value = UpdateState.UpdateAvailable(updateInfo)
                } else {
                    Log.i(TAG, "App is up to date: current=$currentVersionCode")
                    if (!silent) {
                        _updateState.value = UpdateState.UpToDate
                    } else {
                        _updateState.value = UpdateState.Idle
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Update check failed: ${e.message}")
                if (!silent) {
                    _updateState.value = UpdateState.Error(e.message ?: "Failed to connect to dev server")
                } else {
                    _updateState.value = UpdateState.Idle
                }
            }
        }
    }

    /**
     * Downloads the APK file from the server into cache directory with progress reporting.
     */
    fun startDownload(info: UpdateInfo) {
        scope.launch {
            try {
                _updateState.value = UpdateState.Downloading(
                    info = info,
                    progress = 0f,
                    downloadedBytes = 0L,
                    totalBytes = info.fileSizeBytes
                )

                val updateDir = File(context.externalCacheDir ?: context.cacheDir, "updates")
                if (!updateDir.exists()) {
                    updateDir.mkdirs()
                }

                val targetFile = File(updateDir, "offlinechat_v${info.versionCode}.apk")
                if (targetFile.exists()) {
                    targetFile.delete()
                }

                val url = URL(info.apkUrl)
                val connection = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 6000
                    readTimeout = 15000
                    useCaches = false
                }

                val responseCode = connection.responseCode
                if (responseCode != HttpURLConnection.HTTP_OK) {
                    throw IllegalStateException("APK download returned HTTP $responseCode")
                }

                val totalLength = if (connection.contentLengthLong > 0) connection.contentLengthLong else info.fileSizeBytes
                var downloaded = 0L
                val buffer = ByteArray(32 * 1024)

                connection.inputStream.use { input: InputStream ->
                    FileOutputStream(targetFile).use { output: FileOutputStream ->
                        var bytesRead: Int
                        var lastProgressReportTime = 0L

                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            downloaded += bytesRead

                            val now = System.currentTimeMillis()
                            if (now - lastProgressReportTime > 150 || downloaded == totalLength) {
                                lastProgressReportTime = now
                                val progress = if (totalLength > 0) {
                                    (downloaded.toFloat() / totalLength.toFloat()).coerceIn(0f, 1f)
                                } else 0f

                                _updateState.value = UpdateState.Downloading(
                                    info = info,
                                    progress = progress,
                                    downloadedBytes = downloaded,
                                    totalBytes = totalLength
                                )
                            }
                        }
                        output.flush()
                    }
                }
                connection.disconnect()

                Log.i(TAG, "APK downloaded successfully: ${targetFile.absolutePath} (${targetFile.length()} bytes)")
                _updateState.value = UpdateState.ReadyToInstall(info, targetFile)

            } catch (e: Exception) {
                Log.e(TAG, "Failed to download update APK", e)
                _updateState.value = UpdateState.Error("Download failed: ${e.message}")
            }
        }
    }

    /**
     * Prompts the Android package installer to install the downloaded APK.
     */
    fun installApk(targetContext: Context, apkFile: File): Boolean {
        return try {
            // Check unknown sources installation permission on Android 8.0+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (!context.packageManager.canRequestPackageInstalls()) {
                    val settingsIntent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                        data = Uri.parse("package:${context.packageName}")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    targetContext.startActivity(settingsIntent)
                    return false
                }
            }

            val apkUri = FileProvider.getUriForFile(
                targetContext,
                "${targetContext.packageName}.fileprovider",
                apkFile
            )

            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            targetContext.startActivity(installIntent)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch package installer", e)
            _updateState.value = UpdateState.Error("Installer launch failed: ${e.message}")
            false
        }
    }

    fun dismissUpdate() {
        _updateState.value = UpdateState.Idle
    }
}
