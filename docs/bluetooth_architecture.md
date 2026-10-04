# Android Bluetooth Architecture & Technical Specifications
## Offline Peer-to-Peer Chat — Phase 3 Discovery Architecture

### 1. Bluetooth Discovery Concepts & Realities on Android

| Concept | Definition & Android Behavior |
| :--- | :--- |
| **Bluetooth Discovery** | The radio inquiry / beacon scan phase where an adapter detects the presence, signal strength (RSSI), and identifier of nearby radios. **Does NOT establish a data link or allow sending messages.** |
| **Pairing / Bonding** | An Android OS-level cryptographic procedure exchanging link keys (PIN / Numeric Comparison) stored in the Bluetooth subsystem (`BluetoothDevice.getBondState()`). Pairing is not strictly required for insecure RFCOMM sockets, but provides hardware link-layer authentication. |
| **Bluetooth Connection** | An active, established bidirectional communication channel (such as an RFCOMM socket on Classic or GATT connection on BLE) through which data streams can be read and written. |
| **Bluetooth Classic (BR/EDR)** | Optimized for continuous high-throughput data streams (such as audio and RFCOMM sockets). **Critical Android limitation:** Android devices do *not* respond to Classic Bluetooth inquiry scans unless explicitly placed into "Discoverable Mode" (`ACTION_REQUEST_DISCOVERABLE`), which has a system-enforced timeout (default 120s, max 300s). |
| **Bluetooth Low Energy (BLE)** | Optimized for low power, burst packets, and autonomous beaconing. Can broadcast (`BluetoothLeAdvertiser`) custom Service UUIDs and service data even when the device is not discoverable in OS Settings. |

---

### 2. Strategy for Phone-to-Phone Discovery: Dual-Mode Hybrid

To achieve reliable real-world Android phone-to-phone detection without forcing users to navigate into Android system settings on both devices simultaneously, OfflineChat implements a **Dual-Mode Hybrid Discovery Architecture**:

```
                              ┌────────────────────────────────────────┐
                              │           BluetoothDiscovery           │
                              └───────────────────┬────────────────────┘
                                                  │
                         ┌────────────────────────┴────────────────────────┐
                         ▼                                                 ▼
             [Classic Inquiry Mode]                             [BLE Beaconing Mode]
             • adapter.startDiscovery()                         • BluetoothLeAdvertiser
             • BroadcastReceiver (ACTION_FOUND)                 • Advertises SERVICE_UUID
             • Discovers paired & discoverable devices          • BluetoothLeScanner
             • Gathers device name & MAC                        • Low-latency peer detection
                         │                                                 │
                         └────────────────────────┬────────────────────────┘
                                                  │
                                                  ▼
                                      ┌───────────────────────┐
                                      │   PeerDeduplicator    │
                                      └───────────┬───────────┘
                                                  │
                                                  ▼
                                      ┌───────────────────────┐
                                      │  List<Peer> (by RSSI) │
                                      └───────────────────────┘
```

#### Why Hybrid Was Chosen:
1. **Classic Inquiry Alone is Insufficient:** If two phones are placed side by side with Bluetooth turned ON, `startDiscovery()` on Phone A will **NOT** see Phone B unless Phone B has explicitly invoked `ACTION_REQUEST_DISCOVERABLE` or is actively in the Bluetooth Settings screen.
2. **BLE Advertising Solves Spontaneous Discovery:** When User B opens OfflineChat, the app starts BLE advertising with our custom `SERVICE_UUID` (`0000feed-0000-1000-8000-00805f9b34fb`) containing their device ID slice. Phone A's `BluetoothLeScanner` detects this advertisement packet immediately, without any user intervention needed on Phone B.
3. **Graceful Fallback:** "Make Discoverable" is also provided directly in the UI as a one-tap action (`createDiscoverableIntent(120)`) for users who want to accept Classic connections from legacy or non-BLE hardware.

---

### 3. Permission Model & Version Matrix

Modern Android enforces strict separation of Bluetooth permissions based on SDK version:

#### Android 12+ (API level 31, 32, 33, 34, 35):
- `BLUETOOTH_SCAN`: Required to invoke `startDiscovery()` or `BluetoothLeScanner.startScan()`. Flagged with `neverForLocation` in `AndroidManifest.xml` to avoid requiring GPS location tracking.
- `BLUETOOTH_ADVERTISE`: Required to broadcast presence via `BluetoothLeAdvertiser.startAdvertising()`.
- `BLUETOOTH_CONNECT`: Required to query device names (`device.name`), bond state, and cancel discovery.

#### Android 8.0 to Android 11 (API level 26 - 30):
- `BLUETOOTH` & `BLUETOOTH_ADMIN`: Legacy permissions to initiate scanning and connect.
- `ACCESS_FINE_LOCATION`: Required by Android runtime for BLE beacon scanning on Android 11 and lower because beacons could theoretically infer physical location.

---

### 4. Lifecycle Safety & Resource Management

- **Receiver Leak Prevention:** `discoveryReceiver` is only registered when actively starting discovery, guarded by an internal registration flag and a Coroutine `Mutex`.
- **Atomic Teardown:** `stopDiscovery()` cancels classic inquiry (`adapter.cancelDiscovery()`), unregisters the broadcast receiver, stops BLE advertising, and stops the BLE scanner before setting status to `DiscoveryCancelled`.
- **No Background Leaks:** When the Activity is destroyed or the user stops discovery, all radio listeners and scanners are released immediately.

---

### 5. Bluetooth Communication Approach & Key Android Limitations

#### Core Principle: Discovery is NOT Connection or Messaging
Discovery only gathers radio presence, identity tokens, and signal strength (RSSI). **Discovery alone does NOT mean two devices can exchange chat messages.** An explicit RFCOMM channel (Phase 4) with mutual cryptographic handshake must be established before any message frames can travel between phones.

#### Transport Rationale: Why RFCOMM Sockets over BLE GATT for Phone-to-Phone Chat
1. **Throughput and Packet Size:**
   - BLE GATT has an effective MTU typically between 23 and 517 bytes and high latency for bulk data. Sending end-to-end encrypted chat messages, identity certificates (typically 500+ bytes), or attachments over BLE GATT requires complex segmentation/reassembly and custom flow control.
   - Bluetooth Classic RFCOMM provides a stream-oriented, full-duplex socket (similar to TCP) with high throughput (up to 2-3 Mbps with EDR) and built-in hardware flow control.
2. **Peripheral/Central Role Inversion:**
   - On Android, maintaining simultaneous GATT Server and GATT Client roles across arbitrary Android vendor devices (Samsung, Xiaomi, Pixel) suffers from severe vendor-specific firmware bugs and connection timeouts.
   - RFCOMM `BluetoothServerSocket.accept()` and `device.createRfcommSocketToServiceRecord()` are mature, rock-solid APIs supported reliably across all Android versions since API 1.
3. **The Chosen Hybrid Strategy:**
   - **Phase 3 Discovery:** BLE Advertising + BLE Scanning + Classic Inquiry (Autonomous, zero-interaction peer detection).
   - **Phase 4 Data Link:** Bluetooth Classic RFCOMM socket connection using custom Service Record UUID `B2C3D4E5-F6A7-8901-BCDE-F12345678901`.

---

### 6. Physical Hardware Test Matrix & Setup Protocol

To verify dual-phone Bluetooth discovery without mock dependencies:

```
[Phone A]                                      [Phone B]
Pixel / Samsung / OnePlus                      Xiaomi / Motorola / Pixel
Android 12+ (API 31+)                          Android 10 - 15
        │                                              │
        ├──────────── Bluetooth: ON ───────────────────┤
        │                                              │
  Launch OfflineChat                             Launch OfflineChat
  Tap "Scan Nearby"                              Screen: Home / Discovery
        │                                              │
  Transmits BLE Ad ────────────────────────────> Detects Phone A
  Receives BLE Ad <───────────────────────────── Transmits BLE Ad
        │                                              │
  Action: "Rahul's Pixel" (Found)                Action: "Priya's Galaxy" (Found)
  RSSI: -56 dBm                                  RSSI: -58 dBm
```

#### Protocol Steps:
1. Ensure Bluetooth radio is enabled on both physical devices.
2. Launch OfflineChat on Phone B (auto-starts presence broadcast).
3. On Phone A, open "Discover Nearby" screen and verify scanning pulse starts.
4. Verify Phone B appears in the list within 2-5 seconds with its human-readable display name and signal strength.
5. Tap "Stop" on Phone A: verify scan indicator halts and radio listeners unregister immediately.

