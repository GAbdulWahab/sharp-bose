#pragma once

#include <vector>
#include <string>
#include <cstdint>
#include <map>
#include <cstring>
#include <cmath>
#include <algorithm>

namespace mesh::transfer {

constexpr size_t DEFAULT_CHUNK_SIZE = 512; // MTU-friendly BLE L2CAP payload

#pragma pack(push, 1)
struct FileChunkHeader {
    uint32_t transfer_id;
    uint16_t total_chunks;
    uint16_t chunk_index;
    uint32_t file_size;
    uint32_t chunk_crc32;
    char file_name[32];
};
#pragma pack(pop)

struct ChunkPacket {
    FileChunkHeader header;
    std::vector<uint8_t> payload;
};

// Simple CRC32 for offline packet integrity
inline uint32_t calculate_crc32(const uint8_t* data, size_t length) {
    uint32_t crc = 0xFFFFFFFF;
    for (size_t i = 0; i < length; ++i) {
        crc ^= data[i];
        for (int j = 0; j < 8; ++j) {
            crc = (crc >> 1) ^ (0xEDB88320 & -(crc & 1));
        }
    }
    return ~crc;
}

class FileChunker {
public:
    static std::vector<ChunkPacket> chunk_file(uint32_t transfer_id,
                                              const std::string& name,
                                              const std::vector<uint8_t>& data,
                                              size_t chunk_size = DEFAULT_CHUNK_SIZE) {
        std::vector<ChunkPacket> packets;
        if (data.empty()) return packets;

        uint16_t total_chunks = static_cast<uint16_t>(std::ceil(static_cast<double>(data.size()) / chunk_size));

        for (uint16_t i = 0; i < total_chunks; ++i) {
            size_t offset = i * chunk_size;
            size_t len = std::min(chunk_size, data.size() - offset);

            ChunkPacket packet;
            packet.header.transfer_id = transfer_id;
            packet.header.total_chunks = total_chunks;
            packet.header.chunk_index = i;
            packet.header.file_size = static_cast<uint32_t>(data.size());
            packet.header.chunk_crc32 = calculate_crc32(&data[offset], len);

            std::memset(packet.header.file_name, 0, sizeof(packet.header.file_name));
            std::strncpy(packet.header.file_name, name.c_str(), sizeof(packet.header.file_name) - 1);

            packet.payload.assign(data.begin() + offset, data.begin() + offset + len);
            packets.push_back(packet);
        }

        return packets;
    }
};

class FileReassembler {
public:
    explicit FileReassembler(uint32_t transfer_id) : transfer_id_(transfer_id), total_chunks_(0), file_size_(0) {}

    bool add_chunk(const ChunkPacket& packet) {
        if (packet.header.transfer_id != transfer_id_) return false;

        // Verify CRC32
        uint32_t calc_crc = calculate_crc32(packet.payload.data(), packet.payload.size());
        if (calc_crc != packet.header.chunk_crc32) {
            return false; // Corrupt chunk
        }

        if (total_chunks_ == 0) {
            total_chunks_ = packet.header.total_chunks;
            file_size_ = packet.header.file_size;
            file_name_ = std::string(packet.header.file_name);
        }

        received_chunks_[packet.header.chunk_index] = packet.payload;
        return true;
    }

    bool is_complete() const {
        return total_chunks_ > 0 && received_chunks_.size() == total_chunks_;
    }

    double get_progress() const {
        if (total_chunks_ == 0) return 0.0;
        return static_cast<double>(received_chunks_.size()) / total_chunks_;
    }

    std::vector<uint8_t> reassemble() const {
        if (!is_complete()) return {};

        std::vector<uint8_t> complete_file;
        complete_file.reserve(file_size_);

        for (uint16_t i = 0; i < total_chunks_; ++i) {
            auto it = received_chunks_.find(i);
            if (it == received_chunks_.end()) return {};
            complete_file.insert(complete_file.end(), it->second.begin(), it->second.end());
        }

        return complete_file;
    }

    const std::string& get_file_name() const { return file_name_; }
    size_t get_received_count() const { return received_chunks_.size(); }
    size_t get_total_count() const { return total_chunks_; }

private:
    uint32_t transfer_id_;
    uint16_t total_chunks_;
    uint32_t file_size_;
    std::string file_name_;
    std::map<uint16_t, std::vector<uint8_t>> received_chunks_;
};

} // namespace mesh::transfer
