#pragma once
#include "protocol_frames.h"
#include "crypto_engine.h"
#include "jitter_buffer.h"
#include "mesh_router.h"
#include <memory>
#include <functional>
#include <string>

namespace OfflineMesh {

enum class CallState : uint8_t {
    IDLE       = 0x00,
    OUTGOING_RINGING = 0x01,
    INCOMING_RINGING = 0x02,
    CONNECTED  = 0x03,
    TERMINATING= 0x04
};

struct ActiveCallSession {
    uint32_t session_id;
    uint32_t peer_id;
    std::string peer_nickname;
    CallState state;
    std::chrono::steady_clock::time_point start_time;
    uint8_t  active_codec;
    uint8_t  route_hop_count;
    bool     is_muted;
    bool     is_speaker;
    SessionKeys crypto_keys;
    AdaptiveJitterBuffer jitter_buffer;
};

using OnIncomingCallCallback = std::function<void(uint32_t session_id, uint32_t caller_id, const std::string& caller_name)>;
using OnCallEstablishedCallback = std::function<void(uint32_t session_id)>;
using OnCallEndedCallback = std::function<void(uint32_t session_id, const std::string& reason)>;
using OnAudioFrameReadyCallback = std::function<void(const uint8_t* pcm_data, size_t samples)>;
using OnPacketOutboundCallback = std::function<void(uint32_t target_next_hop, const std::vector<uint8_t>& packet)>;

class SessionManager {
private:
    DeviceIdentity local_identity_;
    std::unique_ptr<MeshRouter> router_;
    std::unique_ptr<ActiveCallSession> current_call_;
    mutable std::mutex session_mutex_;

    OnIncomingCallCallback on_incoming_call_;
    OnCallEstablishedCallback on_call_established_;
    OnCallEndedCallback on_call_ended_;
    OnAudioFrameReadyCallback on_audio_ready_;
    OnPacketOutboundCallback on_packet_outbound_;

public:
    SessionManager(const DeviceIdentity& identity, std::unique_ptr<MeshRouter> router)
        : local_identity_(identity), router_(std::move(router)) {}

    void SetCallbacks(
        OnIncomingCallCallback on_inc,
        OnCallEstablishedCallback on_est,
        OnCallEndedCallback on_end,
        OnAudioFrameReadyCallback on_aud,
        OnPacketOutboundCallback on_out) {
        on_incoming_call_ = on_inc;
        on_call_established_ = on_est;
        on_call_ended_ = on_end;
        on_audio_ready_ = on_aud;
        on_packet_outbound_ = on_out;
    }

    bool InitiateCall(uint32_t target_peer_id, const std::string& peer_name) {
        std::lock_guard<std::mutex> lock(session_mutex_);
        if (current_call_ && current_call_->state != CallState::IDLE) {
            return false; // Busy
        }

        uint32_t session_id = static_cast<uint32_t>(rand());
        current_call_ = std::make_unique<ActiveCallSession>();
        current_call_->session_id = session_id;
        current_call_->peer_id = target_peer_id;
        current_call_->peer_nickname = peer_name;
        current_call_->state = CallState::OUTGOING_RINGING;
        current_call_->start_time = std::chrono::steady_clock::now();
        current_call_->active_codec = static_cast<uint8_t>(CodecType::OPUS_VOICE_16KHZ);
        current_call_->is_muted = false;
        current_call_->is_speaker = false;

        // Construct CALL_INIT packet
        CallControlPayload payload{};
        payload.command = static_cast<uint8_t>(CallCommand::INIT);
        payload.session_id = session_id;
        payload.supported_codecs = static_cast<uint8_t>(CodecType::OPUS_VOICE_16KHZ);
        strncpy(payload.caller_nickname, local_identity_.device_nickname.c_str(), sizeof(payload.caller_nickname) - 1);

        PacketHeader header{};
        header.magic = PROTOCOL_MAGIC;
        header.proto_version = PROTOCOL_VERSION;
        header.packet_type = static_cast<uint8_t>(PacketType::CALL_CONTROL);
        header.flags = static_cast<uint8_t>(QoSClass::CONTROL_P1);
        header.hop_limit = 5;
        header.hop_count = 0;
        header.payload_len = sizeof(payload);
        header.seq_num = 1;
        header.sender_prefix = router_->GetLocalNodeId();
        header.dest_prefix = target_peer_id;

        auto packet = PacketSerializer::Serialize(header, reinterpret_cast<const uint8_t*>(&payload), sizeof(payload));
        
        uint32_t next_hop = target_peer_id;
        uint8_t hops = 1;
        router_->ResolveNextHop(target_peer_id, next_hop, hops);

        if (on_packet_outbound_) {
            on_packet_outbound_(next_hop, packet);
        }
        return true;
    }

    void AcceptIncomingCall() {
        std::lock_guard<std::mutex> lock(session_mutex_);
        if (!current_call_ || current_call_->state != CallState::INCOMING_RINGING) return;

        current_call_->state = CallState::CONNECTED;
        current_call_->start_time = std::chrono::steady_clock::now();

        CallControlPayload payload{};
        payload.command = static_cast<uint8_t>(CallCommand::ACCEPT);
        payload.session_id = current_call_->session_id;

        PacketHeader header{};
        header.magic = PROTOCOL_MAGIC;
        header.proto_version = PROTOCOL_VERSION;
        header.packet_type = static_cast<uint8_t>(PacketType::CALL_CONTROL);
        header.flags = static_cast<uint8_t>(QoSClass::CONTROL_P1);
        header.hop_limit = 5;
        header.hop_count = 0;
        header.payload_len = sizeof(payload);
        header.seq_num = 2;
        header.sender_prefix = router_->GetLocalNodeId();
        header.dest_prefix = current_call_->peer_id;

        auto packet = PacketSerializer::Serialize(header, reinterpret_cast<const uint8_t*>(&payload), sizeof(payload));
        uint32_t next_hop = current_call_->peer_id;
        uint8_t hops = 1;
        router_->ResolveNextHop(current_call_->peer_id, next_hop, hops);

        if (on_packet_outbound_) {
            on_packet_outbound_(next_hop, packet);
        }
        if (on_call_established_) {
            on_call_established_(current_call_->session_id);
        }
    }

    void EndCall() {
        std::lock_guard<std::mutex> lock(session_mutex_);
        if (!current_call_ || current_call_->state == CallState::IDLE) return;

        CallControlPayload payload{};
        payload.command = static_cast<uint8_t>(CallCommand::HANGUP);
        payload.session_id = current_call_->session_id;

        PacketHeader header{};
        header.magic = PROTOCOL_MAGIC;
        header.proto_version = PROTOCOL_VERSION;
        header.packet_type = static_cast<uint8_t>(PacketType::CALL_CONTROL);
        header.flags = static_cast<uint8_t>(QoSClass::CONTROL_P1);
        header.hop_limit = 5;
        header.hop_count = 0;
        header.payload_len = sizeof(payload);
        header.seq_num = 999;
        header.sender_prefix = router_->GetLocalNodeId();
        header.dest_prefix = current_call_->peer_id;

        auto packet = PacketSerializer::Serialize(header, reinterpret_cast<const uint8_t*>(&payload), sizeof(payload));
        uint32_t next_hop = current_call_->peer_id;
        uint8_t hops = 1;
        router_->ResolveNextHop(current_call_->peer_id, next_hop, hops);

        if (on_packet_outbound_) {
            on_packet_outbound_(next_hop, packet);
        }

        uint32_t sid = current_call_->session_id;
        current_call_->state = CallState::IDLE;
        if (on_call_ended_) {
            on_call_ended_(sid, "Call Ended");
        }
    }
};

} // namespace OfflineMesh
