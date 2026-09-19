#pragma once
#include <cstdint>
#include <vector>
#include <string>
#include <deque>
#include <mutex>
#include <chrono>

namespace OfflineMesh {

enum class MessageStatus : uint8_t {
    QUEUED    = 0x01,
    RELAYED   = 0x02,
    DELIVERED = 0x03,
    READ      = 0x04,
    FAILED    = 0x05,
    EXPIRED   = 0x06
};

struct OfflineMessage {
    uint32_t message_id;
    uint32_t sender_id;
    uint32_t recipient_id;
    uint64_t timestamp_epoch;
    uint32_t ttl_seconds;
    std::vector<uint8_t> encrypted_payload;
    MessageStatus status;
    uint8_t retry_count;
    std::chrono::steady_clock::time_point queued_at;
};

class StoreAndForwardQueue {
private:
    std::deque<OfflineMessage> queue_;
    mutable std::mutex queue_mutex_;
    const uint32_t MAX_RETRIES = 5;

public:
    void EnqueueMessage(uint32_t msg_id, uint32_t sender, uint32_t recipient, uint32_t ttl, const uint8_t* payload, size_t len) {
        std::lock_guard<std::mutex> lock(queue_mutex_);
        auto now = std::chrono::steady_clock::now();
        uint64_t epoch = std::chrono::duration_cast<std::chrono::milliseconds>(
            std::chrono::system_clock::now().time_since_epoch()).count();
        
        OfflineMessage msg{
            msg_id, sender, recipient, epoch, ttl,
            std::vector<uint8_t>(payload, payload + len),
            MessageStatus::QUEUED, 0, now
        };
        queue_.push_back(msg);
    }

    std::vector<OfflineMessage> GetPendingForRecipient(uint32_t recipient_id) {
        std::lock_guard<std::mutex> lock(queue_mutex_);
        std::vector<OfflineMessage> result;
        auto now = std::chrono::steady_clock::now();

        for (auto& msg : queue_) {
            if (msg.recipient_id == recipient_id && (msg.status == MessageStatus::QUEUED || msg.status == MessageStatus::RELAYED)) {
                auto elapsed_sec = std::chrono::duration_cast<std::chrono::seconds>(now - msg.queued_at).count();
                if (elapsed_sec > msg.ttl_seconds) {
                    msg.status = MessageStatus::EXPIRED;
                    continue;
                }
                result.push_back(msg);
                msg.retry_count++;
                if (msg.retry_count > MAX_RETRIES) {
                    msg.status = MessageStatus::FAILED;
                }
            }
        }
        return result;
    }

    void MarkDelivered(uint32_t msg_id) {
        std::lock_guard<std::mutex> lock(queue_mutex_);
        for (auto& msg : queue_) {
            if (msg.message_id == msg_id) {
                msg.status = MessageStatus::DELIVERED;
                break;
            }
        }
    }
};

} // namespace OfflineMesh
