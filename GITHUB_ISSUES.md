# GitHub Issues — Sharp-Bose Tactical Mesh
**Target Repository:** [`GAbdulWahab/sharp-bose`](https://github.com/GAbdulWahab/sharp-bose)

This document contains the official GitHub issues, tracking tasks, bug fixes, and feature specifications for the Sharp-Bose Tactical Mesh platform.

---

## 📋 Issue #1: `feat(bluetooth): implement full-stack native Bluetooth duplex calling, RFCOMM server/client, and BLE L2CAP CoC`
- **Labels:** `enhancement`, `bluetooth`, `audio`, `mobile`, `desktop`

### Description
```markdown
### Summary
Upgraded both Android and Windows desktop applications to implement full-stack Bluetooth mesh calling following standard `source.android.com` Bluetooth specifications.

### Capabilities Implemented
- **Classic Bluetooth (BR/EDR)**: RFCOMM SPP Server socket listener (`listenUsingInsecureRfcommWithServiceRecord`) and client connector using standard UUID `00001101-0000-1000-8000-00805F9B34FB`.
- **BLE GATT Transport**: GATT Server with write-no-response and notification characteristics (`00002A37-...`), MTU negotiation up to 517 bytes, and `CONNECTION_PRIORITY_HIGH`.
- **BLE L2CAP Connection-Oriented Channels (CoC)**: Dynamic PSM server socket and client channel for Android 10+ (API 29+) ultra-low latency audio frames.
- **BLE Discovery & Advertisement**: `BluetoothLeAdvertiser` & `BluetoothLeScanner` with background state receiver triggers.
- **Deterministic Scanning**: Added `triggerImmediateScanAndConnect()` and `startBluetoothScan()` with explicit connection management and zero background infinite reconnect loops.
- **Windows Native Bluetooth (`SharpBoseWinBluetooth.exe`)**: Compiled C# WinRT backend utilizing `RfcommServiceProvider`, `StreamSocketListener`, `BluetoothLEAdvertisementWatcher`, and `Radio.SetStateAsync`.

### Acceptance Criteria
- [x] Duplex audio frames stream over Bluetooth SPP/GATT without requiring any Wi-Fi connection.
- [x] Zero background auto-reconnecting retry spam when peers disconnect.
- [x] Clean teardown and radio power toggle support.
```

---

## 📋 Issue #2: `feat(radio): add dedicated Bluetooth & Wi-Fi ON/OFF switches and deterministic carrier lock`
- **Labels:** `enhancement`, `ui`, `networking`, `radio`

### Description
```markdown
### Summary
Added explicit radio power management and strict carrier lock policies (`BLUETOOTH_ONLY`, `WIFI_ONLY`, `COMBINED`, `MANUAL`) across mobile and desktop interfaces.

### Problem
Previously, background auto-discovery mechanisms could attempt to switch carriers between Wi-Fi and Bluetooth automatically, causing packet jitter and dropped calls.

### Solution
1. **Dedicated UI Toggle Buttons**:
   - **Header Controls**: Quick `[ ⚡ BT: ON / OFF ]` and `[ 📶 Wi-Fi: ON / OFF ]` toggle buttons.
   - **Carrier Isolation Controls**: `[ 🔵 BT Only ]`, `[ 🟢 Wi-Fi Only ]`, and `[ 🌐 Combined ]` buttons.
2. **Deterministic Carrier Isolation**:
   - In `BLUETOOTH_ONLY` mode, embedded WebSocket server, UDP discovery beacons, and subnet auto-scanners are completely stopped and bypassed.
   - Live calling audio frames and signaling exclusively travel over Bluetooth hardware channels.
3. **Desktop Radio Hardware API**:
   - Integrated IPC handlers `bluetooth:set-radio-state`, `bluetooth:pair`, and `bluetooth:unpair` into Electron `main.js` and `preload.js`.

### Acceptance Criteria
- [x] Toggling Bluetooth OFF cleanly terminates Bluetooth sockets.
- [x] Toggling Wi-Fi OFF stops embedded TCP/UDP server threads.
- [x] In `BLUETOOTH_ONLY` mode, Wi-Fi auto-discovery and WebSocket connections remain inactive.
```

---

## 📋 Issue #3: `fix(identity): prevent Node ID mutation on reconnect and persist identity across sessions`
- **Labels:** `bug`, `identity`, `stability`, `mesh`

### Description
```markdown
### Summary
Resolved the issue where the local Node ID changed on every reconnection or application relaunch.

### Root Cause
1. Handshake logic processed `ASSIGN_ID` control packets from host servers and dynamically overwritten the client's local node ID.
2. Applications generated a new random UUID on every launch instead of loading a stored identifier.

### Fix
- **Android**: Stored `persistent_node_id` in `SharedPreferences` in `ForegroundMeshService.kt` and ignored `ASSIGN_ID` packets if a local persistent ID is already present.
- **Desktop**: Stored `tactical_mesh_node_id` in `localStorage` in `desktop/app.js` and maintained machine-unique hash in `.mesh_node_id` via `desktop/main.js`.
- **Protocol**: Preserved persistent node identity across all RFCOMM, BLE, and WebSocket handshakes.

### Acceptance Criteria
- [x] Node ID remains constant across restarts, network disconnects, and server reconnects.
```

---

## 📋 Issue #4: `fix(mesh-core): resolve compilation errors in Android mesh server, hangup signaling, and crypto engine`
- **Labels:** `bug`, `android`, `crypto`, `build`

### Description
```markdown
### Summary
Resolved build and runtime method contract issues in Android Kotlin mesh core:

### Fixes
1. **`AndroidMeshServer.kt`**: Added `broadcastLocalJson(text: String)` to broadcast control packets to all active WebSocket client sessions.
2. **`MainActivity.kt`**: Updated `stopVoiceCall()` to supply the target peer ID parameter into `bridge.sendCallHangup(activeCallPeerId.ifEmpty { "BROADCAST" })`.
3. **`MeshCryptoEngine.kt`**: Implemented `rotateKeys()` to regenerate 256-bit AES-GCM session keys dynamically.
4. **`BluetoothStateReceiver.kt`**: Standardized scan invocations to `bridge.startBluetoothScan()`.

### Acceptance Criteria
- [x] `:app:compileDebugKotlin` code contracts satisfied.
- [x] Audio frames, call invites, declines, and hangups cleanly pass correct target IDs.
```

---

## 📋 Issue #5: `feat(transfer): implement offline peer-to-peer file & media sharing with CRC32 chunking and multi-hop reassembly`
- **Labels:** `enhancement`, `file-sharing`, `mesh`, `p2p`, `storage`

### Description
```markdown
### Summary
Implemented decentralized offline media and file transfer across the mesh network, enabling peers to exchange vector maps, tactical imagery, sensor dumps, and voice memos without internet access.

### Technical Implementation
- **Binary Chunker (`file_chunker.h`)**: Splits arbitrary binary payloads into MTU-friendly segments (default 512 bytes for BLE L2CAP / RFCOMM).
- **CRC32 Integrity Verification**: Computes per-chunk 32-bit polynomial CRC (`0xEDB88320`) embedded into the binary header (`FileChunkHeader`) to detect radio transmission corruption.
- **Store-and-Forward Reassembler (`FileReassembler`)**: Tracks in-flight chunks by `transfer_id` using sparse index maps, validates payload checksums upon arrival, and reassembles ordered byte streams upon 100% completion.
- **Multi-Hop Mesh Propagation**: Chunks route over multi-hop AODV-Lite topology with intermediate relay caching and selective retransmission of missing chunk indices.

### Acceptance Criteria
- [x] Large files split cleanly into numbered 512B frames with embedded metadata and CRC32 tags.
- [x] Corrupted or dropped frames trigger individual chunk resend requests without restarting the entire file transfer.
- [x] Successfully reassembles and saves images, vector maps, and voice memos across multi-hop peer topologies.
```
