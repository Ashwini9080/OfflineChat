package com.offlinechat.transport.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import com.offlinechat.core.model.TransportType
import com.offlinechat.core.result.AppError
import com.offlinechat.core.result.AppResult
import com.offlinechat.core.util.TimeProvider
import com.offlinechat.transport.api.PeerDevice
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.nio.ByteBuffer
import javax.inject.Inject

/**
 * Scans for BLE advertisements from nearby OfflineChat devices.
 *
 * ## Scan strategy
 * - Filters on [BleAdvertiser.SERVICE_UUID] to ignore unrelated advertisements.
 * - Uses SCAN_MODE_LOW_LATENCY while the UI is open, LOW_POWER when backgrounded.
 *   (Backgrounded scanning must be initiated via WorkManager — not shown here.)
 * - Parses the manufacturer data payload to extract peer identity without
 *   establishing a GATT connection, keeping discovery energy-efficient.
 *
 * ## Partial trust
 * A [PeerDevice] emitted from this scanner carries the peer's claimed identity
 * parsed from the BLE advertisement. The FULL [security.model.DeviceCertificate]
 * (with Ed25519 signature) is verified during the RFCOMM handshake.
 * The scanner performs only a "sanity check" (correct payload length, non-zero
 * device ID) to filter obviously invalid advertisements.
 */
@SuppressLint("MissingPermission") // Permission check is performed by BluetoothTransport
class BleScanner @Inject constructor(
    @ApplicationContext private val context: Context,
    private val timeProvider: TimeProvider,
) {
    companion object {
        private val SERVICE_UUID = ParcelUuid(BleAdvertiser.SERVICE_UUID)
        private const val MANUFACTURER_ID = 0x0E19
        private const val MIN_PAYLOAD_LENGTH = 17 // 1 version + 16 device ID bytes
    }

    /**
     * Returns a [Flow] that emits [ScanEvent]s as BLE advertisements are
     * received or lost.
     *
     * The flow is backed by a [callbackFlow] tied to the Android [ScanCallback].
     * It completes (and scanning stops) when the collector's scope is cancelled.
     *
     * @param lowPower If true, uses SCAN_MODE_LOW_POWER (for background use).
     *                 If false, uses SCAN_MODE_LOW_LATENCY (for foreground scanning UI).
     */
    fun scan(lowPower: Boolean = false): AppResult<Flow<ScanEvent>> {
        val bluetoothAdapter = getBluetoothAdapter()
            ?: return AppResult.Failure(AppError.BluetoothNotAvailable)

        if (!bluetoothAdapter.isEnabled) {
            return AppResult.Failure(AppError.BluetoothNotAvailable)
        }

        val leScanner = bluetoothAdapter.bluetoothLeScanner
            ?: return AppResult.Failure(AppError.BluetoothNotAvailable)

        val filters = listOf(
            ScanFilter.Builder()
                .setServiceUuid(SERVICE_UUID)
                .build(),
        )

        val settings = ScanSettings.Builder()
            .setScanMode(
                if (lowPower) ScanSettings.SCAN_MODE_LOW_POWER
                else ScanSettings.SCAN_MODE_LOW_LATENCY,
            )
            .setReportDelay(0L) // Deliver results immediately
            .build()

        val flow: Flow<ScanEvent> = callbackFlow {
            val callback = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    val peer = parseAdvertisement(result) ?: return
                    trySend(ScanEvent.Found(peer))
                }

                override fun onBatchScanResults(results: List<ScanResult>) {
                    results.forEach { result ->
                        val peer = parseAdvertisement(result) ?: return@forEach
                        trySend(ScanEvent.Found(peer))
                    }
                }

                override fun onScanFailed(errorCode: Int) {
                    close(Exception("BLE scan failed with error code $errorCode"))
                }
            }

            leScanner.startScan(filters, settings, callback)

            awaitClose {
                leScanner.stopScan(callback)
            }
        }

        return AppResult.Success(flow)
    }

    // ── Advertisement parsing ─────────────────────────────────────────────────

    /**
     * Parses a [ScanResult] into a [PeerDevice].
     *
     * Returns null if the advertisement is not valid OfflineChat format.
     * This is a "best-effort" parse — full verification happens at RFCOMM handshake.
     */
    private fun parseAdvertisement(result: ScanResult): PeerDevice? {
        val record = result.scanRecord ?: return null
        val payload = record.getManufacturerSpecificData(MANUFACTURER_ID) ?: return null

        if (payload.size < MIN_PAYLOAD_LENGTH) return null

        val version = payload[0].toInt()
        if (version != 0x01) return null  // Unknown advertisement version

        // Extract 16-byte device ID and convert back to hex string
        val idBytes = payload.copyOfRange(1, 17)
        val deviceId = idBytes.joinToString("") { "%02x".format(it) }

        // Remaining bytes are the display name (UTF-8, may be truncated)
        val displayName = if (payload.size > 17) {
            String(payload.copyOfRange(17, payload.size), Charsets.UTF_8)
        } else {
            "Unknown Device"
        }

        return PeerDevice(
            deviceId = deviceId,
            displayName = displayName,
            bluetoothAddress = result.device.address,
            // Public keys not available from BLE advert — populated during RFCOMM handshake
            publicSigningKeyBytes = ByteArray(0),
            publicDhKeyBytes = ByteArray(0),
            rssi = result.rssi,
            transport = TransportType.BLUETOOTH,
            discoveredAt = timeProvider.nowMillis(),
        )
    }

    private fun getBluetoothAdapter(): BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
}

/**
 * Events produced by [BleScanner].
 *
 * Separate from [transport.api.TransportEvent] because scanner events are
 * internal to [BluetoothTransport] — only [PeerDevice]s that complete the
 * RFCOMM handshake are surfaced as [transport.api.TransportEvent.PeerDiscovered].
 */
sealed class ScanEvent {
    data class Found(val peer: PeerDevice) : ScanEvent()
    data class Lost(val bluetoothAddress: String) : ScanEvent()
}
