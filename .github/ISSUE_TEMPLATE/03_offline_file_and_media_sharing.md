---
name: "Feature: Offline File & Media Sharing"
about: Offline peer-to-peer file & media sharing with CRC32 chunking and multi-hop reassembly
title: "feat(transfer): implement offline peer-to-peer file & media sharing with CRC32 chunking and multi-hop reassembly"
labels: ["enhancement", "file-sharing", "mesh", "p2p"]
assignees: []
---

### Summary
Implemented decentralized offline media and file transfer across the mesh network, enabling peers to exchange vector maps, tactical imagery, sensor dumps, and voice memos without internet access.

### Technical Implementation
- **Binary Chunker (`file_chunker.h`)**: Splits arbitrary binary payloads into MTU-friendly segments (default 512 bytes for BLE L2CAP / RFCOMM).
- **CRC32 Integrity Verification**: Computes per-chunk 32-bit polynomial CRC (`0xEDB88320`) embedded into the binary header (`FileChunkHeader`) to detect radio transmission corruption.
- **Store-and-Forward Reassembler (`FileReassembler`)**: Tracks in-flight chunks by `transfer_id` using sparse index maps, validates payload checksums upon arrival, and reassembles ordered byte streams upon 100% completion.
- **Multi-Hop Mesh Propagation**: Chunks route over multi-hop AODV-Lite topology with intermediate relay caching and selective retransmission of missing chunk indices.

### Acceptance Criteria
- [x] Large files split cleanly into numbered 512B frames with embedded metadata and CRC32 tags.
- [x] Corrupted or dropped frames trigger individual chunk resend requests without restarting the entire file transfer.
- [x] Successfully reassembles and saves images, vector maps, and voice memos across multi-hop peer topologies.
