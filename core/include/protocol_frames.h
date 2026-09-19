#pragma once
#include <cstdint>
#include <vector>
#include <string>
#include <cstring>
#include <array>

namespace OfflineMesh {

constexpr uint16_t PROTOCOL_MAGIC = 0x4D45; // "ME" (Mesh Engine)
constexpr uint8_t  PROTOCOL_VERSION = 0x01;
constexpr size_t   HEADER_SIZE = 20;
constexpr size_t   AEAD_TAG_SIZE = 16;
constexpr size_t   NODE_ID_SIZE = 32; // Ed25519 Public Key / Identity
constexpr uint32_t BROADCAST_NODE_ID = 0xFFFFFFFF;

enum class PacketType : uint8_t {
    DISCOVERY_BEACON  = 0x01,
    PAIRING_HANDSHAKE = 0x02,
    CALL_CONTROL      = 0x03,
    VOICE_FRAME       = 0x04,
    TEXT_MESSAGE      = 0x05,
    ROUTING_CONTROL   = 0x06,
    EMERGENCY_SOS     = 0x07,
    ACK_RECEIPT       = 0x08
};

enum class QoSClass : uint8_t {
    VOICE_REALTIME = 0x00, // P0: Highest priority, drop on congestion, no retransmission
    EMERGENCY_P0   = 0x01, // P0: Emergency SOS flood
    CONTROL_P1     = 0x02, // P1: Handshake, Call setup, Routing
    MESSAGE_P2     = 0x03  // P2: Text messages, store-and-forward DTN
};

enum class CallCommand : uint8_t {
    INIT     = 0x01,
    RINGING  = 0x02,
    ACCEPT   = 0x03,
    REJECT   = 0x04,
    HANGUP   = 0x05,
    BUSY     = 0x06,
    SWITCH_TRANSPORT = 0x07 // Upgrade to Wi-Fi Direct / Local Hotspot
};

enum class CodecType : uint8_t {
    OPUS_VOICE_16KHZ = 0x01,
    OPUS_VOICE_48KHZ = 0x02,
    RAW_PCM_16KHZ    = 0x03
};

#pragma pack(push, 1)
struct PacketHeader {
    uint16_t magic;          // 0x4D45
    uint8_t  proto_version;  // 0x01
    uint8_t  packet_type;    // PacketType
    uint8_t  flags;          // Bit 0-1: QoSClass, Bit 2: Has FEC, Bit 3: Encrypted
    uint8_t  hop_limit;      // Max hops (e.g. 5)
    uint8_t  hop_count;      // Current hop counter
    uint8_t  reserved;       // Alignment / Future flags
    uint16_t payload_len;    // Payload length in bytes (excluding AEAD Tag)
    uint32_t seq_num;        // Monotonic sequence number for replay filtering & ordering
    uint32_t sender_prefix;  // 32-bit ephemeral prefix of sender node ID
    uint32_t dest_prefix;    // 32-bit ephemeral prefix of destination node ID (or 0xFFFFFFFF)
};

struct VoiceFrameHeader {
    uint32_t timestamp_ms;   // Millisecond timestamp for jitter buffer calculation
    uint8_t  codec_type;     // CodecType
    uint8_t  frame_duration; // 10ms, 20ms, or 40ms
    uint16_t sample_rate;    // 16000 or 48000
    uint16_t encoded_bytes;  // Size of Opus compressed data
    uint8_t  fec_present;    // 1 if in-band FEC data attached
};

struct CallControlPayload {
    uint8_t  command;        // CallCommand
    uint32_t session_id;     // 32-bit random call session ID
    uint8_t  supported_codecs;
    uint8_t  extra_flags;
    char     caller_nickname[32];
};

struct EmergencySOSPayload {
    uint32_t sos_id;         // Unique alert identifier
    uint64_t timestamp;      // Epoch milliseconds
    int32_t  latitude_e7;    // Lat * 1e7 (0 if location sharing disabled)
    int32_t  longitude_e7;   // Lon * 1e7 (0 if location sharing disabled)
    uint8_t  has_location;   // 0 = False, 1 = Explicit opt-in location
    char     emergency_text[128]; // e.g. "NEED MEDICAL ASSISTANCE"
    uint8_t  signature[64];  // Ed25519 signature of alert payload
};

struct DiscoveryBeacon {
    uint32_t ephemeral_id;   // Rotating 32-bit node ID
    uint8_t  battery_level;  // 0-100% (for energy-aware routing)
    uint8_t  link_capacity;  // Bitfield: BLE, Wi-Fi Direct, Local Hotspot
    uint8_t  neighbor_count; // Number of directly connected active neighbors
    char     nickname[24];   // User visible nickname
};
#pragma pack(pop)

class PacketSerializer {
public:
    static std::vector<uint8_t> Serialize(const PacketHeader& header, const uint8_t* payload, size_t payload_len, const uint8_t* aead_tag = nullptr) {
        size_t total_size = sizeof(PacketHeader) + payload_len + (aead_tag ? AEAD_TAG_SIZE : 0);
        std::vector<uint8_t> buffer(total_size);
        std::memcpy(buffer.data(), &header, sizeof(PacketHeader));
        if (payload && payload_len > 0) {
            std::memcpy(buffer.data() + sizeof(PacketHeader), payload, payload_len);
        }
        if (aead_tag) {
            std::memcpy(buffer.data() + sizeof(PacketHeader) + payload_len, aead_tag, AEAD_TAG_SIZE);
        }
        return buffer;
    }

    static bool DeserializeHeader(const uint8_t* data, size_t len, PacketHeader& out_header) {
        if (len < sizeof(PacketHeader)) return false;
        std::memcpy(&out_header, data, sizeof(PacketHeader));
        if (out_header.magic != PROTOCOL_MAGIC || out_header.proto_version != PROTOCOL_VERSION) {
            return false;
        }
        return true;
    }
};

} // namespace OfflineMesh
