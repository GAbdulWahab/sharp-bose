# Offline Mesh Wire Protocol Specification (v1.0)

## 1. Physical Framing & Header Format
All datagrams across BLE L2CAP, GATT, or Wi-Fi Direct sockets share a 20-byte fixed binary header:

```
 0                   1                   2                   3
 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
| Magic (0x4D45) | ProtoVer (0x01) | PacketType  | Flags (QoS)  |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
| HopLimit (8b)  | HopCount (8b)   | Reserved    | Payload Length|
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                      Sequence Number (32-bit)                 |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|           Sender Node ID Prefix / Ephemeral (32-bit)          |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|           Recipient Node ID / Broadcast (32-bit)              |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                 Encrypted Payload (Variable Length)           |
|                               ...                             |
|          + 16-byte Poly1305 AEAD Authentication Tag           |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
```

## 2. Packet Types
* `0x01` (`DISCOVERY_BEACON`): Ephemeral Node ID, link capabilities, neighbor metrics.
* `0x02` (`PAIRING_HANDSHAKE`): `Noise_XX` handshake messages (`msg1`, `msg2`, `msg3`).
* `0x03` (`CALL_CONTROL`): `INIT`, `RINGING`, `ACCEPT`, `REJECT`, `HANGUP`, `SWITCH_TRANSPORT`.
* `0x04` (`VOICE_FRAME`): Timestamp (32-bit ms), Opus frame (10/20ms), in-band FEC flag.
* `0x05` (`TEXT_MESSAGE`): Encrypted payload, message ID, timestamp, TTL, delivery receipt flag.
* `0x06` (`ROUTING_CONTROL`): AODV-Lite `RREQ` (Route Request), `RREP` (Route Reply), `RERR` (Route Error).
* `0x07` (`EMERGENCY_SOS`): Flooded priority packet with signature, timestamp, SOS message, and optional coordinates.
* `0x08` (`ACK_RECEIPT`): Acknowledgment for reliable message delivery and routing updates.

## 3. Quality of Service (QoS) & Prioritization
* **P0 (`VOICE_REALTIME` / `EMERGENCY_SOS`)**: Scheduled ahead of all buffers; discarded immediately if latency exceeds 150ms. No packet retransmission.
* **P1 (`CONTROL_P1`)**: Handshakes, call signaling, routing topology updates.
* **P2 (`MESSAGE_P2`)**: Store-and-forward text messages with exponential backoff retransmission.
