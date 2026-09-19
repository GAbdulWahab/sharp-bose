#include "protocol_frames.h"
#include "crypto_engine.h"
#include "jitter_buffer.h"
#include "mesh_router.h"
#include "store_and_forward.h"
#include "file_chunker.h"
#include "ptt_group_router.h"
#include <iostream>
#include <cassert>
#include <vector>

using namespace OfflineMesh;

void TestProtocolSerialization() {
    std::cout << "[RUNNING] TestProtocolSerialization...\n";
    PacketHeader hdr{};
    hdr.magic = PROTOCOL_MAGIC;
    hdr.proto_version = PROTOCOL_VERSION;
    hdr.packet_type = static_cast<uint8_t>(PacketType::VOICE_FRAME);
    hdr.flags = static_cast<uint8_t>(QoSClass::VOICE_REALTIME);
    hdr.hop_limit = 5;
    hdr.hop_count = 0;
    hdr.seq_num = 1001;
    hdr.sender_prefix = 0xAA112233;
    hdr.dest_prefix = 0xBB445566;

    std::vector<uint8_t> dummy_payload = {0x01, 0x02, 0x03, 0x04, 0x05};
    hdr.payload_len = static_cast<uint16_t>(dummy_payload.size());

    auto serialized = PacketSerializer::Serialize(hdr, dummy_payload.data(), dummy_payload.size());
    assert(serialized.size() == sizeof(PacketHeader) + dummy_payload.size());

    PacketHeader deserialized_hdr{};
    bool ok = PacketSerializer::DeserializeHeader(serialized.data(), serialized.size(), deserialized_hdr);
    assert(ok == true);
    assert(deserialized_hdr.magic == PROTOCOL_MAGIC);
    assert(deserialized_hdr.seq_num == 1001);
    assert(deserialized_hdr.sender_prefix == 0xAA112233);
    std::cout << "[PASSED] TestProtocolSerialization\n";
}

void TestReplayFilter() {
    std::cout << "[RUNNING] TestReplayFilter...\n";
    ReplayFilter filter;
    
    // First packet accepted
    assert(filter.CheckAndAdd(100) == true);
    // Duplicate rejected
    assert(filter.CheckAndAdd(100) == false);
    // In-order advancement accepted
    assert(filter.CheckAndAdd(101) == true);
    assert(filter.CheckAndAdd(105) == true);
    // Out-of-order within 64 window accepted
    assert(filter.CheckAndAdd(103) == true);
    // Duplicate of out-of-order rejected
    assert(filter.CheckAndAdd(103) == false);
    // Packets far behind window rejected
    assert(filter.CheckAndAdd(20) == false);
    std::cout << "[PASSED] TestReplayFilter\n";
}

void TestJitterBufferAndPLC() {
    std::cout << "[RUNNING] TestJitterBufferAndPLC...\n";
    AdaptiveJitterBuffer jb(40);
    
    uint8_t f1[] = {0xAA, 0xBB};
    uint8_t f2[] = {0xCC, 0xDD};
    uint8_t f4[] = {0xEE, 0xFF}; // Frame 3 is dropped (simulated packet loss)

    jb.PushFrame(1, 1000, 1040, f1, 2);
    jb.PushFrame(2, 1020, 1060, f2, 2);
    jb.PushFrame(4, 1060, 1100, f4, 2); // Notice Frame 3 is missing

    std::vector<uint8_t> out;
    bool is_plc = false;

    // Pop Frame 1
    bool ok = jb.PopFrame(out, is_plc);
    assert(ok == true);
    assert(out[0] == 0xAA);
    assert(is_plc == false);

    // Pop Frame 2
    ok = jb.PopFrame(out, is_plc);
    assert(ok == true);
    assert(out[0] == 0xCC);
    assert(is_plc == false);

    // Pop Frame 3 (Missing -> Should trigger PLC)
    ok = jb.PopFrame(out, is_plc);
    assert(ok == false);
    assert(is_plc == true);

    // Pop Frame 4 (Recovered frame)
    ok = jb.PopFrame(out, is_plc);
    assert(ok == true);
    assert(out[0] == 0xEE);
    assert(is_plc == false);

    auto stats = jb.GetStats();
    assert(stats.plc_frames_generated == 1);
    std::cout << "[PASSED] TestJitterBufferAndPLC\n";
}

void TestMeshRouting() {
    std::cout << "[RUNNING] TestMeshRouting...\n";
    MeshRouter router(0x00000001); // Node 1

    // Node 2 directly connected neighbor (RSSI -60 dBm)
    router.RegisterNeighbor(0x00000002, "Node-2", -60, 90, 0x01);

    // Node 2 advertises a route to Node 3 (2 hops away from Node 1)
    router.HandleRouteAdvertisement(0x00000003, 0x00000002, 1, 10, -60);

    uint32_t next_hop = 0;
    uint8_t hop_count = 0;
    bool found = router.ResolveNextHop(0x00000003, next_hop, hop_count);

    assert(found == true);
    assert(next_hop == 0x00000002);
    assert(hop_count == 2);

    // If Node 2 disconnects, route to Node 3 becomes invalid
    router.MarkNeighborDisconnected(0x00000002);
    found = router.ResolveNextHop(0x00000003, next_hop, hop_count);
    assert(found == false);

    std::cout << "[PASSED] TestMeshRouting\n";
}

void TestFileChunkerAndReassembly() {
    std::cout << "[RUNNING] TestFileChunkerAndReassembly...\n";
    std::vector<uint8_t> sample_data(1250);
    for (size_t i = 0; i < sample_data.size(); ++i) {
        sample_data[i] = static_cast<uint8_t>(i % 256);
    }

    uint32_t transfer_id = 9988;
    auto chunks = mesh::transfer::FileChunker::chunk_file(transfer_id, "tactical_map.bin", sample_data, 512);
    assert(chunks.size() == 3); // 512 + 512 + 226 = 1250 bytes

    mesh::transfer::FileReassembler reassembler(transfer_id);
    assert(reassembler.add_chunk(chunks[0]) == true);
    assert(reassembler.is_complete() == false);
    assert(reassembler.add_chunk(chunks[2]) == true); // Out-of-order chunk arrival
    assert(reassembler.is_complete() == false);
    assert(reassembler.add_chunk(chunks[1]) == true);
    assert(reassembler.is_complete() == true);

    auto recovered = reassembler.reassemble();
    assert(recovered.size() == sample_data.size());
    assert(recovered == sample_data);
    std::cout << "[PASSED] TestFileChunkerAndReassembly (1250 bytes verified with CRC32)\n";
}

void TestPTTGroupRouter() {
    std::cout << "[RUNNING] TestPTTGroupRouter...\n";
    mesh::ptt::PTTGroupRouter router(101);

    assert(router.select_channel(2) == true);
    assert(router.get_current_channel() == 2);

    // Acquire floor
    assert(router.request_ptt_talk() == true);
    assert(router.get_state() == mesh::ptt::PTTState::TRANSMITTING);

    // Release floor
    router.release_ptt_talk();
    assert(router.get_state() == mesh::ptt::PTTState::IDLE);

    // Incoming frame from speaker 202
    router.handle_incoming_ptt_frame(2, 202, {0x11, 0x22});
    assert(router.get_state() == mesh::ptt::PTTState::RECEIVING);

    std::cout << "[PASSED] TestPTTGroupRouter\n";
}

int main() {
    std::cout << "=== RUNNING CORE MESH ENGINE AUTOMATED UNIT TESTS ===\n";
    TestProtocolSerialization();
    TestReplayFilter();
    TestJitterBufferAndPLC();
    TestMeshRouting();
    TestFileChunkerAndReassembly();
    TestPTTGroupRouter();
    std::cout << "=== ALL TESTS COMPLETED SUCCESSFULLY (6/6 PASSED) ===\n";
    return 0;
}
