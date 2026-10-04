package com.offlinechat.data.discovery

import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.ScanCallback
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ErrorMappingTest {

    private lateinit var errorMapper: BluetoothErrorMapper

    @Before
    fun setUp() {
        errorMapper = BluetoothErrorMapper()
    }

    @Test
    fun `scan error codes map to informative user-facing strings`() {
        assertEquals(
            "Bluetooth scanning is already in progress.",
            errorMapper.mapScanError(ScanCallback.SCAN_FAILED_ALREADY_STARTED)
        )
        assertEquals(
            "Failed to register Bluetooth scan handler with the system.",
            errorMapper.mapScanError(ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED)
        )
        assertEquals(
            "Internal Android Bluetooth subsystem error occurred.",
            errorMapper.mapScanError(ScanCallback.SCAN_FAILED_INTERNAL_ERROR)
        )
        assertEquals(
            "Bluetooth Low Energy scanning is unsupported on this hardware.",
            errorMapper.mapScanError(ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED)
        )
        assertEquals(
            "Hardware scan capacity reached. Try disabling other Bluetooth apps.",
            errorMapper.mapScanError(ScanCallback.SCAN_FAILED_OUT_OF_HARDWARE_RESOURCES)
        )
        assertEquals(
            "Scanning too frequently. Android throttled the radio; wait a moment.",
            errorMapper.mapScanError(ScanCallback.SCAN_FAILED_SCANNING_TOO_FREQUENTLY)
        )
        assertTrue(
            errorMapper.mapScanError(999).contains("999")
        )
    }

    @Test
    fun `advertise error codes map to informative user-facing strings`() {
        assertEquals(
            "BLE advertisement payload exceeds the 31-byte radio packet limit.",
            errorMapper.mapAdvertiseError(AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE)
        )
        assertEquals(
            "No hardware advertisement slots available on this radio.",
            errorMapper.mapAdvertiseError(AdvertiseCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS)
        )
        assertEquals(
            "BLE presence broadcast is already running.",
            errorMapper.mapAdvertiseError(AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED)
        )
        assertEquals(
            "Android Bluetooth adapter internal error during advertising.",
            errorMapper.mapAdvertiseError(AdvertiseCallback.ADVERTISE_FAILED_INTERNAL_ERROR)
        )
        assertEquals(
            "BLE peripheral advertising is not supported by this chipset.",
            errorMapper.mapAdvertiseError(AdvertiseCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED)
        )
        assertTrue(
            errorMapper.mapAdvertiseError(888).contains("888")
        )
    }

    @Test
    fun `exceptions map to clean domain error messages`() {
        val secEx = SecurityException("Permission Denial")
        assertEquals(
            "Bluetooth permissions were denied or revoked by the system.",
            errorMapper.mapException(secEx)
        )

        val stateEx = IllegalStateException("Bluetooth is disabled. Please enable Bluetooth.")
        assertEquals(
            "Bluetooth is disabled. Please enable Bluetooth.",
            errorMapper.mapException(stateEx)
        )

        val genericEx = RuntimeException("Connection aborted")
        assertEquals(
            "Connection aborted",
            errorMapper.mapException(genericEx)
        )
    }
}
