#pragma once

#include <string>
#include <vector>
#include <map>
#include <chrono>
#include <cstdint>
#include <mutex>

namespace mesh::ptt {

enum class PTTState {
    IDLE,
    TRANSMITTING,
    RECEIVING
};

struct PTTChannel {
    uint8_t channel_id;
    std::string channel_name;
    bool is_emergency;
    uint32_t active_speaker_id;
    int64_t last_transmission_ms;
};

class PTTGroupRouter {
public:
    PTTGroupRouter(uint32_t local_node_id)
        : local_node_id_(local_node_id), current_channel_(1), current_state_(PTTState::IDLE) {
        // Initialize standard tactical channels
        channels_[1] = {1, "Emergency & SOS", true, 0, 0};
        channels_[2] = {2, "General Mesh", false, 0, 0};
        channels_[3] = {3, "Team Alpha Recon", false, 0, 0};
        channels_[4] = {4, "Tactical Ops", false, 0, 0};
    }

    bool select_channel(uint8_t channel_id) {
        std::lock_guard<std::mutex> lock(mutex_);
        if (channels_.find(channel_id) != channels_.end()) {
            current_channel_ = channel_id;
            return true;
        }
        return false;
    }

    uint8_t get_current_channel() const { return current_channel_; }

    // Attempt to acquire PTT floor (Half-Duplex contention resolution)
    bool request_ptt_talk() {
        std::lock_guard<std::mutex> lock(mutex_);
        auto& ch = channels_[current_channel_];

        int64_t now = get_now_ms();
        // If someone else is talking and their last burst was < 1500ms ago, floor is busy unless emergency
        if (ch.active_speaker_id != 0 && ch.active_speaker_id != local_node_id_ && (now - ch.last_transmission_ms) < 1500) {
            return false; // Floor busy
        }

        ch.active_speaker_id = local_node_id_;
        ch.last_transmission_ms = now;
        current_state_ = PTTState::TRANSMITTING;
        return true;
    }

    void release_ptt_talk() {
        std::lock_guard<std::mutex> lock(mutex_);
        auto& ch = channels_[current_channel_];
        if (ch.active_speaker_id == local_node_id_) {
            ch.active_speaker_id = 0;
        }
        current_state_ = PTTState::IDLE;
    }

    void handle_incoming_ptt_frame(uint8_t channel_id, uint32_t speaker_id, const std::vector<uint8_t>& /*audio_frame*/) {
        std::lock_guard<std::mutex> lock(mutex_);
        auto it = channels_.find(channel_id);
        if (it != channels_.end()) {
            it->second.active_speaker_id = speaker_id;
            it->second.last_transmission_ms = get_now_ms();
            if (channel_id == current_channel_ && current_state_ != PTTState::TRANSMITTING) {
                current_state_ = PTTState::RECEIVING;
            }
        }
    }

    PTTState get_state() const { return current_state_; }

    std::map<uint8_t, PTTChannel> get_channels() const {
        std::lock_guard<std::mutex> lock(mutex_);
        return channels_;
    }

private:
    static int64_t get_now_ms() {
        return std::chrono::duration_cast<std::chrono::milliseconds>(
            std::chrono::system_clock::now().time_since_epoch()).count();
    }

    uint32_t local_node_id_;
    uint8_t current_channel_;
    PTTState current_state_;
    std::map<uint8_t, PTTChannel> channels_;
    mutable std::mutex mutex_;
};

} // namespace mesh::ptt
