#pragma once
#include "protocol_frames.h"
#include <unordered_map>
#include <vector>
#include <chrono>
#include <string>
#include <mutex>
#include <functional>

namespace OfflineMesh {

struct RouteInfo {
    uint32_t dest_id;
    uint32_t next_hop_neighbor;
    uint8_t  hop_count;
    uint32_t dest_seq_num;
    int8_t   rssi;             // Received signal strength in dBm (-30 to -95)
    uint8_t  link_quality;     // 0-100 score based on packet success & RSSI
    std::chrono::steady_clock::time_point last_seen;
    bool     is_active;
};

struct NeighborNode {
    uint32_t node_id;
    std::string nickname;
    int8_t   rssi;
    uint8_t  battery_level;
    uint8_t  transport_flags;  // 0x01 = BLE L2CAP, 0x02 = Wi-Fi P2P
    std::chrono::steady_clock::time_point last_beacon;
    bool     is_direct_connected;
};

class MeshRouter {
private:
    uint32_t local_node_id_;
    uint32_t current_seq_num_ = 1;
    std::unordered_map<uint32_t, RouteInfo> routing_table_;
    std::unordered_map<uint32_t, NeighborNode> neighbors_;
    mutable std::mutex router_mutex_;

    const std::chrono::seconds ROUTE_EXPIRATION_TIME{20};
    const std::chrono::seconds NEIGHBOR_TIMEOUT{10};

public:
    MeshRouter(uint32_t local_id) : local_node_id_(local_id) {}

    uint32_t GetLocalNodeId() const { return local_node_id_; }

    void RegisterNeighbor(uint32_t neighbor_id, const std::string& nickname, int8_t rssi, uint8_t battery, uint8_t transport) {
        std::lock_guard<std::mutex> lock(router_mutex_);
        auto now = std::chrono::steady_clock::now();
        neighbors_[neighbor_id] = {neighbor_id, nickname, rssi, battery, transport, now, true};
        
        // Direct link is 1 hop
        routing_table_[neighbor_id] = {
            neighbor_id, neighbor_id, 1, 0, rssi, CalculateLinkScore(rssi), now, true
        };
    }

    void HandleRouteAdvertisement(uint32_t dest_id, uint32_t advertiser_id, uint8_t hop_count, uint32_t dest_seq, int8_t rssi) {
        std::lock_guard<std::mutex> lock(router_mutex_);
        if (dest_id == local_node_id_) return; // Ignore routes to ourselves

        auto now = std::chrono::steady_clock::now();
        uint8_t new_hop_count = hop_count + 1;
        if (new_hop_count > 6) return; // Enforce max hop count

        auto it = routing_table_.find(dest_id);
        if (it == routing_table_.end()) {
            routing_table_[dest_id] = {
                dest_id, advertiser_id, new_hop_count, dest_seq, rssi, CalculateLinkScore(rssi), now, true
            };
        } else {
            // AODV sequence rule: higher seq_num wins; if equal, lower hop_count wins
            if (dest_seq > it->second.dest_seq_num ||
                (dest_seq == it->second.dest_seq_num && new_hop_count < it->second.hop_count)) {
                it->second = {
                    dest_id, advertiser_id, new_hop_count, dest_seq, rssi, CalculateLinkScore(rssi), now, true
                };
            }
        }
    }

    bool ResolveNextHop(uint32_t dest_id, uint32_t& out_next_hop, uint8_t& out_hop_count) {
        std::lock_guard<std::mutex> lock(router_mutex_);
        CleanupExpired();

        auto it = routing_table_.find(dest_id);
        if (it != routing_table_.end() && it->second.is_active) {
            out_next_hop = it->second.next_hop_neighbor;
            out_hop_count = it->second.hop_count;
            return true;
        }
        return false;
    }

    void MarkNeighborDisconnected(uint32_t neighbor_id) {
        std::lock_guard<std::mutex> lock(router_mutex_);
        neighbors_.erase(neighbor_id);
        for (auto& pair : routing_table_) {
            if (pair.second.next_hop_neighbor == neighbor_id) {
                pair.second.is_active = false;
            }
        }
    }

    std::vector<NeighborNode> GetActiveNeighbors() const {
        std::lock_guard<std::mutex> lock(router_mutex_);
        std::vector<NeighborNode> list;
        auto now = std::chrono::steady_clock::now();
        for (const auto& pair : neighbors_) {
            if (now - pair.second.last_beacon <= NEIGHBOR_TIMEOUT) {
                list.push_back(pair.second);
            }
        }
        return list;
    }

    std::vector<RouteInfo> GetActiveRoutes() const {
        std::lock_guard<std::mutex> lock(router_mutex_);
        std::vector<RouteInfo> list;
        auto now = std::chrono::steady_clock::now();
        for (const auto& pair : routing_table_) {
            if (pair.second.is_active && (now - pair.second.last_seen <= ROUTE_EXPIRATION_TIME)) {
                list.push_back(pair.second);
            }
        }
        return list;
    }

private:
    uint8_t CalculateLinkScore(int8_t rssi) {
        if (rssi >= -50) return 100;
        if (rssi <= -95) return 10;
        return static_cast<uint8_t>(((rssi + 95) * 90) / 45 + 10);
    }

    void CleanupExpired() {
        auto now = std::chrono::steady_clock::now();
        for (auto& pair : routing_table_) {
            if (pair.second.is_active && (now - pair.second.last_seen > ROUTE_EXPIRATION_TIME)) {
                pair.second.is_active = false;
            }
        }
    }
};

} // namespace OfflineMesh
