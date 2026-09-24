---
name: "Feature: Radio Power Switches & Carrier Isolation"
about: Dedicated Bluetooth & Wi-Fi ON/OFF switches and deterministic carrier lock
title: "feat(radio): add dedicated Bluetooth & Wi-Fi ON/OFF switches and deterministic carrier lock"
labels: ["enhancement", "ui", "networking", "radio"]
assignees: []
---

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
