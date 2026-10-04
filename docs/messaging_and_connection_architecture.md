# Phase 4 — Bluetooth Peer Connection & Message Exchange Architecture
## Offline Peer-to-Peer Chat — Technical Specification

### 1. Connection Architecture: RFCOMM Sockets

Bluetooth communication in OfflineChat uses **RFCOMM (Radio Frequency Communication)** sockets, emulating RS-232 serial ports over the Bluetooth L2CAP layer.

```
+─────────────────────────────────────────────────────────────────────────────+
|                               Device A (Initiator)                          |
+─────────────────────────────────────────────────────────────────────────────+
                                       │
            1. Cancel Discovery (`adapter.cancelDiscovery()`)
            2. `device.createInsecureRfcommSocketToServiceRecord(SPP_UUID)`
            3. `socket.connect()`
                                       │
                                       ▼
+─────────────────────────────────────────────────────────────────────────────+
|                               Device B (Listener)                           |
| `adapter.listenUsingInsecureRfcommWithServiceRecord("OfflineChatSPP", UUID)`|
| `serverSocket.accept()`                                                     |
+─────────────────────────────────────────────────────────────────────────────+
```

#### Why Insecure RFCOMM with Fallback?
- **Insecure RFCOMM (`createInsecureRfcommSocketToServiceRecord`):** Establishes an unauthenticated L2CAP link without triggering Android OS pairing dialogs or requiring user PIN interaction.
- **Mutual Handshake:** Authentication is performed at the application layer using our Keystore-backed cryptographic identities (`HandshakePayload`).
- **Discovery Cancellation:** Android's Bluetooth controller shares physical radio time slices between inquiry scanning and connection setup. Calling `adapter.cancelDiscovery()` before `socket.connect()` prevents connection timeouts.

---

### 2. Mutual Handshake & Identity Verification

Immediately after socket connection, both peers perform a bidirectional handshake:

```
Device A                                                    Device B
   │                                                           │
   ├─────── HANDSHAKE (deviceId, name, EC pubKey) ────────────>│
   │                                                           │ (verifies & associates socket)
   │<────── HANDSHAKE (deviceId, name, EC pubKey) ─────────────┤
   │                                                           │
(verifies & associates socket)
```

1. **Local Handshake Generation:** Constructs `HandshakePayload(deviceId, displayName, publicKeyBase64)`.
2. **Payload Framing:** Enveloped inside `MessageEnvelope(messageType = "HANDSHAKE")`.
3. **Socket Association:** The active socket is indexed in `activeSockets[peerId]` by the verified `deviceId` rather than the temporary MAC address.
4. **Foreground Service:** Triggers `ConnectionForegroundService` to keep CPU alive in Android background.

---

### 3. Wire Protocol & Frame Format

Messages are transmitted over the socket output stream using a length-prefixed protocol:

```text
+-----------------------+---------------------------------------------+
|  Length Header        |  Payload Body                               |
|  (4 Bytes Big-Endian) |  (JSON-serialized MessageEnvelope, UTF-8)   |
+-----------------------+---------------------------------------------+
```

- **Thread Safety:** Stream writes are synchronized per socket stream to prevent interleaved frames.
- **Buffer Safety:** Input stream checks `length > 0 && length <= 1_048_576` (1MB limit) to protect against memory exhaustion attacks or corrupted frames.

---

### 4. Reliable Offline Synchronization (`MessageSyncEngine`)

A central, application-scoped `@Singleton` engine coordinates envelope processing:

1. **Incoming Text Message:**
   - Persisted to Room SQLite as `MessageStatus.DELIVERED`.
   - Conversation preview updated.
   - Immediate `ACK` envelope transmitted back with original `messageId`.
2. **Delivery Receipt (`ACK`):**
   - Transmitted message status updated from `SENT` $\rightarrow$ `DELIVERED`.
   - UI reflects double checkmark.
3. **Offline Queueing & Flush:**
   - If sending when disconnected, message is stored as `PENDING`.
   - When a peer connects, `flushPendingMessages(peerId)` automatically transmits all pending messages in chronological order.
