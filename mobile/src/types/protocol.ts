export enum PacketType {
  DISCOVERY_BEACON  = 0x01,
  PAIRING_HANDSHAKE = 0x02,
  CALL_CONTROL      = 0x03,
  VOICE_FRAME       = 0x04,
  TEXT_MESSAGE      = 0x05,
  ROUTING_CONTROL   = 0x06,
  EMERGENCY_SOS     = 0x07,
  ACK_RECEIPT       = 0x08,
}

export enum CallCommand {
  INIT     = 0x01,
  RINGING  = 0x02,
  ACCEPT   = 0x03,
  REJECT   = 0x04,
  HANGUP   = 0x05,
  BUSY     = 0x06,
}

export enum CallState {
  IDLE = 'IDLE',
  OUTGOING_RINGING = 'OUTGOING_RINGING',
  INCOMING_RINGING = 'INCOMING_RINGING',
  CONNECTED = 'CONNECTED',
  TERMINATING = 'TERMINATING',
}

export interface PeerNode {
  id: string;              // 32-bit hex node id e.g. "0x7F4A21B9"
  nickname: string;
  rssi: number;            // dBm e.g. -65
  batteryPercent: number;  // 0-100
  hopCount: number;        // 1 = direct, >1 = mesh relay
  transport: 'BLE_L2CAP' | 'WIFI_DIRECT' | 'MESH_RELAY';
  isPaired: boolean;
  lastSeenMs: number;
}

export interface ActiveCallStats {
  sessionId: string;
  peerId: string;
  peerName: string;
  durationSeconds: number;
  rttLatencyMs: number;
  packetLossPercent: number;
  jitterMs: number;
  routeType: string;
  codec: string;
  bitrateKbps: number;
  isMuted: boolean;
  isSpeaker: boolean;
}

export interface ChatMessage {
  id: string;
  senderId: string;
  senderName: string;
  recipientId: string;
  text: string;
  timestamp: number;
  status: 'QUEUED' | 'SENT' | 'RELAYED' | 'DELIVERED' | 'READ' | 'FAILED';
  hopCount: number;
}

export interface EmergencySOSAlert {
  id: string;
  senderId: string;
  senderName: string;
  timestamp: number;
  message: string;
  latitude?: number;
  longitude?: number;
  hasLocation: boolean;
  hopCount: number;
}
