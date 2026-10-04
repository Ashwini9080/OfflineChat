# OfflineChat 📡💬

> **Encrypted, Off-Grid Peer-to-Peer Messaging for Android**
> Communicate securely without cellular coverage, internet access, or central servers using Bluetooth and Wi-Fi Direct.

---

## 🌟 Overview

**OfflineChat** is a zero-dependency, decentralized offline chat application built natively for Android. It enables two or more Android devices to discover each other, verify identities cryptographically, and exchange messages peer-to-peer using hardware radios.

---

## 🏗️ Architecture

OfflineChat is designed with strict **Clean Architecture** principles and separation of concerns:

```text
┌────────────────────────────────────────────────────────┐
│                   Presentation Layer                   │
│   Jetpack Compose UI • StateFlow • ViewModels • Radar  │
└───────────────────────────┬────────────────────────────┘
                            │
┌───────────────────────────▼────────────────────────────┐
│                      Domain Layer                      │
│       Use Cases • DiscoveryStatus • Peer Domain        │
└───────────────────────────┬────────────────────────────┘
                            │
┌───────────────────────────▼────────────────────────────┐
│                       Data Layer                       │
│    DeviceDiscovery Interface • DiscoveryManager        │
│          ┌────────────────┴────────────────┐           │
│          ▼                                 ▼           │
│   BluetoothDiscovery              WifiDirectDiscovery  │
│   (Classic + BLE Hybrid)                               │
└────────────────────────────────────────────────────────┘
```

---

## 🚀 Phase 3 — Real Bluetooth Discovery

Phase 3 implements **autonomous, dual-mode real Android Bluetooth discovery** without simulated or mock devices:

1. **Dual-Mode Hybrid Discovery:**
   - **Bluetooth Classic Inquiry (`BluetoothAdapter.startDiscovery()`):** Discovers discoverable devices and paired hardware using OS broadcast receivers.
   - **Bluetooth Low Energy (BLE) Peripheral Advertising (`BluetoothLeAdvertiser`):** Broadcasts presence with custom Service UUID `0000feed-0000-1000-8000-00805f9b34fb`. Optimized with a compact 31-byte primary payload and device name delivered in `ScanResponse` to eliminate `ADVERTISE_FAILED_DATA_TOO_LARGE`.
   - **BLE Active Scanning (`BluetoothLeScanner`):** Continuously scans with low latency filters to instantly detect other OfflineChat users without requiring manual system discoverable mode.

2. **Deduplication & Proximity Sorting (`PeerDeduplicator`):**
   - Suppresses duplicate discoveries across Classic and BLE scans.
   - Keys on unique device ID and Bluetooth MAC address (never display name).
   - Dynamically updates signal strength (RSSI) and timestamps.
   - Keeps nearest devices at the top of the list.
   - Upgrades MAC-fallback placeholders to verified application IDs.

3. **Modern Runtime Permissions (`BluetoothPermissionHelper`):**
   - **Android 12+ (API 31+):** `BLUETOOTH_SCAN` (`neverForLocation`), `BLUETOOTH_CONNECT`, `BLUETOOTH_ADVERTISE`.
   - **Android 8.0 – 11 (API 26 – 30):** `BLUETOOTH`, `BLUETOOTH_ADMIN`, `ACCESS_FINE_LOCATION`.
   - Rationale dialogs and direct deep links to Android App Settings if permanently denied.

4. **Lifecycle & Hardware Safety:**
   - Atomic cleanup and receiver unregistration on pause/stop/exit via `DisposableEffect` and ViewModel `onCleared()`.
   - Dynamic adapter state handling (`ACTION_STATE_CHANGED`) for graceful transitions when Bluetooth is enabled/disabled.

---

## 🔒 Security & Cryptography

- **Hardware Keystore Identity:** Device identity backed by Android Keystore with EC P-256 keys.
- **Trust On First Use (TOFU):** Cryptographic verification dialog before establishing a connection.
- **Pure Local Storage:** Room SQLite persistence with zero telemetry, analytics, or external server calls.

---

## 🛠️ Tech Stack

- **Language:** Kotlin 2.0+
- **UI Toolkit:** Jetpack Compose (Material 3 Dark Theme)
- **Architecture:** Clean Architecture + MVVM + Repository Pattern
- **Concurrency:** Kotlin Coroutines & Asynchronous Flows (`StateFlow`, `SharedFlow`)
- **Dependency Injection:** Dagger Hilt
- **Local Database:** Android Jetpack Room SQLite
- **Hardware APIs:** Android Bluetooth Classic, BLE (Scanner & Advertiser), Wi-Fi P2P

---

## 📱 Hardware Testing

Tested against modern Android Bluetooth hardware specifications:
- Verified on Android 12 through Android 15 permission boundaries.
- Tested zero-leakage broadcast receiver lifecycles.
- Unit test suite covering device mapping, error code translation, duplicate deduplication, and lifecycle transitions.

---

## 📄 License

Open-source under the Apache License 2.0.
