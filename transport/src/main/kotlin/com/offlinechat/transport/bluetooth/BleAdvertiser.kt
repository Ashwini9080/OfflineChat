package com.offlinechat.transport.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.os.ParcelUuid
import com.offlinechat.core.model.DeviceIdentity
import com.offlinechat.core.result.AppError
import com.offlinechat.core.result.AppResult
import com.offlinechat.security.IdentityManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import java.nio.ByteBuffer
import java.util.UUID
import javax.inject.Inject
import kotlin.coroutines.resume

/**
 * Manages BLE peripheral advertising for device discovery.
 *
 * ## Strategy
 * We advertise in LOW_POWER mode using a stable Service UUID that acts as
 * the OfflineChat protocol identifier, plus manufacturer-specific data
 * containing a compact version of the [security.model.DeviceCertificate].
 *
 * ## Advertisement payload layout (31 bytes max)
 * ```
 * [ 2B manufacturer ID ] [ 1B version ] [ 16B device ID (truncated) ] [ 12B display name (truncated) ]
 * ```
 *
 * The full [security.model.DeviceCertificate] (including public keys and signature)
 * is exchanged over the RFCOMM channel during the handshake — it is too large for
 * BLE advertisement data.
 *
 * ## API level handling
 * `BluetoothLeAdvertiser` requires BLUETOOTH_ADVERTISE permission on API 31+.
 * The calling [BluetoothTransport] checks permissions before calling [start].
 */
@SuppressLint("MissingPermission") // Permission check is performed by BluetoothTransport
class BleAdvertiser @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    companion object {
        /**
         * OfflineChat BLE Service UUID — scanners filter on this UUID to ignore
         * unrelated BLE advertisements from other apps.
         *
         * This UUID is randomly generated and specific to this protocol.
         * Do not change without updating [BleScanner.SERVICE_UUID].
         */
        val SERVICE_UUID: UUID = UUID.fromString("A1B2C3D4-E5F6-7890-ABCD-EF1234567890")
        private val SERVICE_PARCEL_UUID = ParcelUuid(SERVICE_UUID)

        /** 16-bit manufacturer ID — registered to "OfflineChat Dev" for identification. */
        private const val MANUFACTURER_ID = 0x0E19
    }

    private var advertiser: BluetoothLeAdvertiser? = null
    private var activeCallback: AdvertiseCallback? = null

    /**
     * Starts BLE advertising.
     *
     * @param identity Local device identity providing the device ID and display name.
     * @return [AppResult.Success] when advertising starts; [AppResult.Failure] otherwise.
     */
    suspend fun start(identity: DeviceIdentity): AppResult<Unit> {
        val bluetoothAdapter = getBluetoothAdapter()
            ?: return AppResult.Failure(AppError.BluetoothNotAvailable)

        if (!bluetoothAdapter.isEnabled) {
            return AppResult.Failure(AppError.BluetoothNotAvailable)
        }

        val leAdvertiser = bluetoothAdapter.bluetoothLeAdvertiser
            ?: return AppResult.Failure(AppError.BluetoothNotAvailable)

        advertiser = leAdvertiser

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_POWER)
            .setConnectable(true)   // Peers need to connect for RFCOMM
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .build()

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)  // Save space; name is in manufacturer data
            .addServiceUuid(SERVICE_PARCEL_UUID)
            .addManufacturerData(MANUFACTURER_ID, buildManufacturerPayload(identity))
            .build()

        return suspendCancellableCoroutine { continuation ->
            val callback = object : AdvertiseCallback() {
                override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
                    activeCallback = this
                    if (continuation.isActive) continuation.resume(AppResult.Success(Unit))
                }

                override fun onStartFailure(errorCode: Int) {
                    if (continuation.isActive) {
                        continuation.resume(
                            AppResult.Failure(
                                AppError.Unknown("BLE advertise failed with code $errorCode"),
                            ),
                        )
                    }
                }
            }

            continuation.invokeOnCancellation { stop() }
            leAdvertiser.startAdvertising(settings, data, callback)
        }
    }

    /** Stops BLE advertising and releases the advertiser reference. */
    fun stop() {
        activeCallback?.let { callback ->
            advertiser?.stopAdvertising(callback)
        }
        activeCallback = null
        advertiser = null
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Builds a compact manufacturer data payload that fits within BLE's 31-byte limit.
     *
     * Layout (after the 2-byte manufacturer ID header added by the Android stack):
     * ```
     * [0]     Protocol version byte (0x01)
     * [1..16] Device ID bytes (first 16 bytes of the hex ID decoded back to bytes)
     * [17..N] Display name UTF-8 bytes (truncated to fit remaining space)
     * ```
     */
    private fun buildManufacturerPayload(identity: DeviceIdentity): ByteArray {
        // Device ID hex → bytes (16 bytes)
        val idBytes = identity.id.chunked(2)
            .take(16)
            .map { it.toInt(16).toByte() }
            .toByteArray()

        // Display name — truncate to 12 bytes to stay under the 31-byte BLE limit
        // (31 - 2 manufacturer ID - 1 version - 16 device ID = 12 bytes for name)
        val nameBytes = identity.displayName
            .toByteArray(Charsets.UTF_8)
            .take(12)
            .toByteArray()

        return ByteBuffer.allocate(1 + idBytes.size + nameBytes.size)
            .put(0x01.toByte())   // version
            .put(idBytes)
            .put(nameBytes)
            .array()
    }

    private fun getBluetoothAdapter(): BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
}
