# Global Offline Voice Calling & Mesh Communication Platform

An off-grid, peer-to-peer real-time voice, walkie-talkie (PTT), file sharing, and mesh communication network over Bluetooth Low Energy (BLE L2CAP CoC), Wi-Fi Direct, and multi-hop routing.

---

## 🚀 Key Features

- **No Internet or Cellular Required**: Complete peer-to-peer communication over BLE L2CAP and Wi-Fi Direct.
- **Walkie-Talkie Push-To-Talk (PTT)**: Half-duplex tactical group voice intercom with multi-channel selection (Emergency, General, Recon, Tactical Ops).
- **Offline P2P File & Media Transfer**: Chunks photos, vector maps, and voice logs with CRC32 verification and store-and-forward reassembly.
- **Tactical Mesh Radar & Geolocation**: 360° node polar coordinates, distance estimation, RSSI signal rings, and offline rally point markers.
- **Multi-Hop Mesh Routing**: Epidemic & routing-directed packet propagation with dynamic TTL and duplicate replay suppression.
- **End-to-End Encryption (E2EE)**: Built on Noise Protocol Framework (Noise_XX) with Curve25519, ChaCha20-Poly1305, and SAS (Short Authentication String) voice verification.
- **Real-Time Voice Calling**: Low-latency 16kHz PCM audio engine with hardware Acoustic Echo Cancellation (AEC) and Noise Suppression.
- **Emergency SOS Broadcast**: High-priority broadcast channel relaying emergency beacons across all reachable peers.

---

## 📁 Repository Structure

```
├── core/                  # Core C++20 routing, crypto, jitter buffer, file chunker & tests
│   ├── include/           # Header files (file_chunker.h, ptt_group_router.h, crypto_engine.h, etc.)
│   └── tests/             # CTest unit test suite (test_core_engine.cpp)
├── mobile/                # Mobile application
│   ├── android/           # Android native project (Kotlin, BLE L2CAP, Audio Engine, PTT)
│   ├── ios/               # iOS native project (Swift, CoreBluetooth, CallKit)
│   └── src/               # React Native screens (PTT, Tactical Radar, File Share, Call, Chat)
├── docs/                  # Architecture, protocol, security & compliance specs
│   ├── PROTOCOL_SPEC.md
│   ├── SECURITY_MODEL.md
│   ├── REGULATORY_COMPLIANCE.md
│   └── BUILD_INSTRUCTIONS.md
└── web-preview/           # Web simulation dashboard
```

---

## 🛠️ Building & Running

### 1. Build Android App
The Android app is automatically compiled in the cloud via GitHub Actions on every push. You can download the latest APK directly from the GitHub repository Actions tab:
- **GitHub Actions**: [https://github.com/GAbdulWahab/sharp-bose/actions](https://github.com/GAbdulWahab/sharp-bose/actions)

To build locally:
```bash
cd mobile/android
./gradlew assembleDebug
```

### 2. Run Web Simulation Preview
```bash
cd web-preview
node server.js
```
Open `http://localhost:3000` in your browser to interact with the full mesh simulator, PTT walkie-talkie, and file transfer engine.

---

## 📄 License
MIT License.
