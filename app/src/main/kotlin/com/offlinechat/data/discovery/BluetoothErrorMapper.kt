package com.offlinechat.data.discovery

import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.ScanCallback
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Translates Android Bluetooth subsystem error codes and runtime exceptions
 * into human-readable, domain-safe error descriptions without leaking raw stack traces.
 */
@Singleton
class BluetoothErrorMapper @Inject constructor() {

    fun mapScanError(errorCode: Int): String {
        return when (errorCode) {
            ScanCallback.SCAN_FAILED_ALREADY_STARTED ->
                "Bluetooth scanning is already in progress."
            ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED ->
                "Failed to register Bluetooth scan handler with the system."
            ScanCallback.SCAN_FAILED_INTERNAL_ERROR ->
                "Internal Android Bluetooth subsystem error occurred."
            ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED ->
                "Bluetooth Low Energy scanning is unsupported on this hardware."
            ScanCallback.SCAN_FAILED_OUT_OF_HARDWARE_RESOURCES ->
                "Hardware scan capacity reached. Try disabling other Bluetooth apps."
            ScanCallback.SCAN_FAILED_SCANNING_TOO_FREQUENTLY ->
                "Scanning too frequently. Android throttled the radio; wait a moment."
            else ->
                "Bluetooth scan encountered error code: $errorCode"
        }
    }

    fun mapAdvertiseError(errorCode: Int): String {
        return when (errorCode) {
            AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE ->
                "BLE advertisement payload exceeds the 31-byte radio packet limit."
            AdvertiseCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS ->
                "No hardware advertisement slots available on this radio."
            AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED ->
                "BLE presence broadcast is already running."
            AdvertiseCallback.ADVERTISE_FAILED_INTERNAL_ERROR ->
                "Android Bluetooth adapter internal error during advertising."
            AdvertiseCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED ->
                "BLE peripheral advertising is not supported by this chipset."
            else ->
                "BLE advertising encountered error code: $errorCode"
        }
    }

    fun mapException(throwable: Throwable): String {
        return when (throwable) {
            is SecurityException ->
                "Bluetooth permissions were denied or revoked by the system."
            is IllegalStateException ->
                throwable.message ?: "Bluetooth adapter is in an invalid state."
            else ->
                throwable.message ?: "Unexpected Bluetooth discovery failure."
        }
    }
}
