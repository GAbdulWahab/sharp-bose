#pragma once
#include <cstdint>
#include <vector>
#include <array>
#include <string>
#include <chrono>
#include <cstring>

namespace OfflineMesh {

constexpr size_t CRYPTO_KEY_SIZE = 32;       // 256-bit X25519 / ChaCha20
constexpr size_t CRYPTO_PUBLIC_KEY_SIZE = 32;// Ed25519 / X25519 Public
constexpr size_t CRYPTO_SECRET_KEY_SIZE = 64;// Ed25519 Private Key
constexpr size_t CRYPTO_NONCE_SIZE = 12;     // ChaCha20-Poly1305 96-bit Nonce
constexpr size_t CRYPTO_TAG_SIZE = 16;       // Poly1305 128-bit Tag

struct DeviceIdentity {
    std::array<uint8_t, 32> public_key;
    std::array<uint8_t, 64> secret_key;
    std::string device_nickname;
};

struct SessionKeys {
    std::array<uint8_t, 32> send_key;
    std::array<uint8_t, 32> receive_key;
    uint32_t send_seq_num = 0;
    uint32_t receive_seq_num = 0;
    bool is_established = false;
};

// Replay protection sliding window filter (64-packet window)
class ReplayFilter {
private:
    uint32_t max_seq_ = 0;
    uint64_t window_bitmask_ = 0;
    bool initialized_ = false;

public:
    void Reset() {
        max_seq_ = 0;
        window_bitmask_ = 0;
        initialized_ = false;
    }

    bool CheckAndAdd(uint32_t seq) {
        if (!initialized_) {
            max_seq_ = seq;
            window_bitmask_ = 1;
            initialized_ = true;
            return true;
        }

        if (seq > max_seq_) {
            uint32_t diff = seq - max_seq_;
            if (diff >= 64) {
                window_bitmask_ = 1;
            } else {
                window_bitmask_ = (window_bitmask_ << diff) | 1;
            }
            max_seq_ = seq;
            return true;
        }

        uint32_t diff = max_seq_ - seq;
        if (diff >= 64) {
            return false; // Packet is too old; drop
        }

        if ((window_bitmask_ & (1ULL << diff)) != 0) {
            return false; // Replayed packet!
        }

        window_bitmask_ |= (1ULL << diff);
        return true;
    }
};

// Short Authentication String (SAS) generator for out-of-band peer verification
class SASGenerator {
public:
    // Generate a 6-digit verification code from handshake transcript hash
    static std::string GenerateNumericCode(const uint8_t* handshake_hash, size_t len) {
        if (len < 4) return "000000";
        uint32_t val = (static_cast<uint32_t>(handshake_hash[0]) << 24) |
                       (static_cast<uint32_t>(handshake_hash[1]) << 16) |
                       (static_cast<uint32_t>(handshake_hash[2]) << 8)  |
                       (static_cast<uint32_t>(handshake_hash[3]));
        uint32_t code = val % 1000000;
        char buf[8];
        snprintf(buf, sizeof(buf), "%06u", code);
        return std::string(buf);
    }

    // Generate visual 4-word verification phrase
    static std::vector<std::string> GenerateWordCode(const uint8_t* handshake_hash, size_t len) {
        static const std::vector<std::string> WORD_LIST = {
            "apple", "anchor", "beacon", "bridge", "canyon", "castle", "delta", "eagle",
            "falcon", "forest", "galaxy", "harbor", "island", "jungle", "lagoon", "meadow",
            "nebula", "oasis", "planet", "quartz", "river", "summit", "timber", "valley"
        };
        std::vector<std::string> words;
        if (len >= 4) {
            for (size_t i = 0; i < 4; ++i) {
                words.push_back(WORD_LIST[handshake_hash[i] % WORD_LIST.size()]);
            }
        }
        return words;
    }
};

class CryptoEngine {
public:
    static bool GenerateIdentity(DeviceIdentity& out_identity, const std::string& nickname);
    static bool EncryptPayload(
        const uint8_t* key, uint32_t seq_num,
        const uint8_t* plaintext, size_t plaintext_len,
        const uint8_t* aad, size_t aad_len,
        uint8_t* out_ciphertext, uint8_t* out_tag);
    
    static bool DecryptPayload(
        const uint8_t* key, uint32_t seq_num,
        const uint8_t* ciphertext, size_t ciphertext_len,
        const uint8_t* aad, size_t aad_len,
        const uint8_t* tag,
        uint8_t* out_plaintext);

    static bool PerformNoiseHandshakeStep(
        int step,
        const uint8_t* local_priv,
        const uint8_t* local_pub,
        const uint8_t* remote_pub,
        SessionKeys& out_session_keys,
        std::string& out_sas_code);
};

} // namespace OfflineMesh
