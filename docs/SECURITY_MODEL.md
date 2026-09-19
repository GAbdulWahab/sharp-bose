# Offline Mesh Cryptographic & Security Model

## 1. Cryptographic Primitives
* **Identity Keys**: Ed25519 asymmetric key pairs generated on-device and stored in hardware-backed keystores (iOS Secure Enclave / Android KeyStore).
* **Key Agreement**: X25519 Diffie-Hellman via `Noise_XX_25519_ChaChaPoly_BLAKE2s`.
* **Authenticated Encryption**: ChaCha20-Poly1305 AEAD (128-bit authentication tag).
* **Hash / Key Derivation**: BLAKE2s / HKDF.

## 2. Handshake Pattern: `Noise_XX`
Provides mutual authentication, protects static public keys from passive eavesdroppers, and ensures Perfect Forward Secrecy (PFS):
1. `-> e` (Initiator sends ephemeral public key)
2. `<- e, ee, s, es` (Responder sends ephemeral public key, performs DH, sends encrypted static key)
3. `-> s, se` (Initiator sends encrypted static key and completes handshake)

## 3. Short Authentication String (SAS) Verification
To prevent Man-in-the-Middle (MitM) attacks during initial discovery:
* Both devices compute `H = BLAKE2s(handshake_transcript)`.
* Derive a 6-digit numerical code `Code = (H[0..3]) mod 1,000,000` and a 4-word mnemonic phrase.
* Users visually verify matching codes out-of-band before trusting the link.

## 4. Replay Attack Mitigation
* Every packet carries a monotonic 32-bit sequence number.
* Receivers maintain a 64-bit sliding window bitmask.
* Stale packets or duplicate sequence numbers are immediately dropped before cryptographic decryption.
