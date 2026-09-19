# Global Offline Voice Calling & Mesh Communication Platform

An off-grid, peer-to-peer real-time voice and text messaging communication network over Bluetooth Low Energy (BLE L2CAP CoC), Wi-Fi Direct, and multi-hop mesh routing.

---

## 🚀 Key Features

- **No Internet or Cellular Required**: Complete peer-to-peer communication over BLE L2CAP and Wi-Fi Direct.
- **Multi-Hop Mesh Protocol**: Epidemic and routing-directed multi-hop packet propagation with dynamic TTL and duplicate suppression.
- **End-to-End Encryption (E2EE)**: Built on Noise Protocol Framework (Noise_XX) with Curve25519, ChaCha20-Poly1305, and SAS (Short Authentication String) voice verification.
- **Real-Time Voice Calling**: Low-latency 16kHz PCM audio engine with hardware Acoustic Echo Cancellation (AEC) and Noise Suppression.
- **Emergency SOS Broadcast**: High-priority broadcast channel relaying emergency beacon and optional geolocation across all reachable peers.
- **Cross-Platform Architecture**:
  - `core/`: Shared C++20 protocol frames, crypto engine, jitter buffer, store-and-forward queue, and mesh router.
  - `mobile/android`: Native Android Kotlin engine with foreground service, L2CAP sockets, and real-time audio pipeline.
  - `mobile/ios`: Native iOS Swift engine with CallKit integration and CoreBluetooth transport.
  - `mobile/src`: React Native / TypeScript cross-platform UI.
  - `web-preview`: Interactive web preview and mesh simulation tool.

---

## 📁 Repository Structure

```
├── core/                  # Core C++20 routing, crypto, jitter buffer & unit tests
│   ├── include/           # Header files (crypto_engine.h, mesh_router.h, etc.)
│   └── tests/             # CTest test suite (test_core_engine.cpp)
├── mobile/                # Mobile application
│   ├── android/           # Android native project (Kotlin, BLE L2CAP, Audio Engine)
│   ├── ios/               # iOS native project (Swift, CoreBluetooth, CallKit)
│   └── src/               # React Native screens, components, and native bridge
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
Prerequisites: JDK 17, Android SDK (API 34/35).

```bash
cd mobile/android
./gradlew assembleDebug
```
The compiled APK will be generated at `mobile/android/app/build/outputs/apk/debug/app-debug.apk`.

### 2. Build Core C++ Engine & Tests
```bash
cd core
mkdir build && cd build
cmake ..
cmake --build .
ctest --output-on-failure
```

### 3. Web Simulation Preview
```bash
cd web-preview
node server.js
```
Open `http://localhost:3000` in your browser.

---

## 📄 License
MIT License.
