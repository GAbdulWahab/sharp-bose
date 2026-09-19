# Regulatory Compliance, Privacy & App Store Guidelines

## 1. Zero-Account & Privacy Architecture
* **No Phone Numbers or Emails**: Devices identify solely by ephemeral cryptographic public keys.
* **Rotating Discovery Identifiers**: Ephemeral 32-bit Node IDs rotate every 15 minutes to prevent tracking by passive BLE sniffers.
* **Opt-in Emergency Location**: GPS coordinates are strictly disabled by default and only transmitted when the user explicitly triggers Emergency SOS with location sharing toggled ON.

## 2. Radio Emissions & Local Laws
* Transmits exclusively within standard 2.4 GHz and 5 GHz ISM bands adhering to FCC (US Part 15), ETSI (Europe), and regional equivalent EIRP transmission limits for Bluetooth Low Energy and Wi-Fi.

## 3. Truth-in-Advertising & User Communication
* The application clearly communicates that range is determined by physical wireless hardware (~10–30m for BLE, ~50–100m for line-of-sight Wi-Fi Direct, and multi-hop relay devices).
* It does NOT advertise "worldwide free offline calling without network infrastructure".
