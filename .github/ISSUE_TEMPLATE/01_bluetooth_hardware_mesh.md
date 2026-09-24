---
name: "Feature: Native Bluetooth Mesh Calling"
about: Native Bluetooth Duplex Calling, RFCOMM SPP Server/Client, and BLE L2CAP CoC
title: "feat(bluetooth): implement full-stack native Bluetooth duplex calling, RFCOMM server/client, and BLE L2CAP CoC"
labels: ["enhancement", "bluetooth", "audio"]
assignees: []
---

### Summary
Upgraded both Android and Windows desktop applications to implement full-stack Bluetooth mesh calling following standard source.android.com Bluetooth specifications.

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
