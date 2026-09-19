#pragma once
#include <cstdint>
#include <vector>
#include <deque>
#include <mutex>
#include <chrono>
#include <algorithm>
#include <cmath>

namespace OfflineMesh {

struct AudioStats {
    uint32_t packets_received = 0;
    uint32_t packets_lost = 0;
    uint32_t packets_discarded = 0;
    uint32_t plc_frames_generated = 0;
    float    jitter_ms = 0.0f;
    float    current_delay_ms = 40.0f;
    float    packet_loss_rate = 0.0f;
    float    rtt_latency_ms = 0.0f;
    uint32_t current_bitrate_bps = 16000;
};

struct JitterFrame {
    uint32_t seq_num;
    uint32_t timestamp_ms;
    std::vector<uint8_t> opus_data;
    bool     is_fec;
};

class AdaptiveJitterBuffer {
private:
    std::deque<JitterFrame> buffer_;
    mutable std::mutex mutex_;
    
    uint32_t target_delay_ms_ = 40;  // 40ms default baseline delay
    uint32_t min_delay_ms_ = 20;     // 20ms minimum
    uint32_t max_delay_ms_ = 120;    // 120ms maximum to preserve conversational reactivity
    
    uint32_t last_popped_seq_ = 0;
    uint32_t last_arrival_time_ms_ = 0;
    uint32_t last_transit_time_ms_ = 0;
    float    interarrival_jitter_ = 0.0f;
    
    bool     initialized_ = false;
    AudioStats stats_;

public:
    AdaptiveJitterBuffer(uint32_t initial_delay_ms = 40) 
        : target_delay_ms_(initial_delay_ms) {}

    void Reset() {
        std::lock_guard<std::mutex> lock(mutex_);
        buffer_.clear();
        initialized_ = false;
        last_popped_seq_ = 0;
        interarrival_jitter_ = 0.0f;
        stats_ = AudioStats();
    }

    void PushFrame(uint32_t seq, uint32_t ts_ms, uint32_t local_arrival_ms, const uint8_t* payload, size_t len, bool is_fec = false) {
        std::lock_guard<std::mutex> lock(mutex_);
        stats_.packets_received++;

        // Update jitter calculation (RFC 3550 style EWMA)
        if (last_arrival_time_ms_ != 0) {
            int32_t transit = static_cast<int32_t>(local_arrival_ms - ts_ms);
            int32_t d = transit - static_cast<int32_t>(last_transit_time_ms_);
            if (d < 0) d = -d;
            interarrival_jitter_ += (static_cast<float>(d) - interarrival_jitter_) / 16.0f;
            stats_.jitter_ms = interarrival_jitter_;
            
            // Adapt target buffer depth based on jitter (target = 2 * jitter + 20ms margin)
            float calculated_target = (interarrival_jitter_ * 2.5f) + 20.0f;
            target_delay_ms_ = std::clamp(static_cast<uint32_t>(calculated_target), min_delay_ms_, max_delay_ms_);
            stats_.current_delay_ms = static_cast<float>(target_delay_ms_);
        }
        last_arrival_time_ms_ = local_arrival_ms;
        last_transit_time_ms_ = local_arrival_ms - ts_ms;

        if (initialized_ && seq <= last_popped_seq_ && (last_popped_seq_ - seq) < 1000) {
            stats_.packets_discarded++; // Late packet
            return;
        }

        JitterFrame frame{seq, ts_ms, std::vector<uint8_t>(payload, payload + len), is_fec};
        auto it = std::lower_bound(buffer_.begin(), buffer_.end(), frame,
            [](const JitterFrame& a, const JitterFrame& b) { return a.seq_num < b.seq_num; });
        buffer_.insert(it, frame);

        // Discard oldest if buffer overflows max delay
        while (buffer_.size() > (max_delay_ms_ / 20) + 2) {
            buffer_.pop_front();
            stats_.packets_discarded++;
        }
    }

    // Returns true if valid frame returned, false if PLC (Packet Loss Concealment) must be performed
    bool PopFrame(std::vector<uint8_t>& out_payload, bool& out_is_plc) {
        std::lock_guard<std::mutex> lock(mutex_);

        if (buffer_.empty()) {
            if (initialized_) {
                stats_.plc_frames_generated++;
                stats_.packets_lost++;
                out_is_plc = true;
            }
            return false;
        }

        if (!initialized_) {
            // Require at least 2 frames before starting playback
            if (buffer_.size() < 2) return false;
            initialized_ = true;
            last_popped_seq_ = buffer_.front().seq_num - 1;
        }

        uint32_t expected_seq = last_popped_seq_ + 1;
        if (buffer_.front().seq_num == expected_seq) {
            out_payload = std::move(buffer_.front().opus_data);
            buffer_.pop_front();
            last_popped_seq_ = expected_seq;
            out_is_plc = false;
            return true;
        } else if (buffer_.front().seq_num < expected_seq) {
            buffer_.pop_front();
            return PopFrame(out_payload, out_is_plc);
        } else {
            // Missing sequence gap: trigger Packet Loss Concealment for 1 interval
            last_popped_seq_ = expected_seq;
            stats_.plc_frames_generated++;
            stats_.packets_lost++;
            out_is_plc = true;
            return false;
        }
    }

    AudioStats GetStats() const {
        std::lock_guard<std::mutex> lock(mutex_);
        AudioStats s = stats_;
        uint32_t total = s.packets_received + s.packets_lost;
        s.packet_loss_rate = (total > 0) ? (static_cast<float>(s.packets_lost) / total) * 100.0f : 0.0f;
        return s;
    }
};

} // namespace OfflineMesh
