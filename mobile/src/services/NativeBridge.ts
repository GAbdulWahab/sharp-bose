import { Platform } from 'react-native';
import { PeerNode, ActiveCallStats, ChatMessage, EmergencySOSAlert } from '../types/protocol';

// Candidate endpoints for Bluetooth PAN, Wi-Fi, USB reverse, Hotspot, and Localhost
export const CANDIDATE_MESH_HOSTS: string[] = [
  // 1. Bluetooth PAN Laptop & Gateway IPs
  '172.27.180.170:3000',
  '172.27.180.37:3000',
  '172.27.180.1:3000',

  // 2. Wi-Fi Laptop & Gateway IPs
  '10.19.238.166:3000',
  '10.19.238.104:3000',

  // 3. Localhost & USB Reverse / Web
  Platform.OS === 'web' ? 'localhost:3000' : '127.0.0.1:3000',
  '10.0.2.2:3000', // Android Studio Emulator

  // 4. USB Tethering (RNDIS Host)
  '192.168.42.129:3000',
  '192.168.42.1:3000',

  // 5. Mobile Hotspot Gateways
  '192.168.43.1:3000',
  '192.168.44.1:3000',
  '192.168.137.1:3000',
];

export const DEFAULT_MESH_HOST = Platform.OS === 'web' 
  ? 'localhost:3000' 
  : '172.27.180.170:3000'; // Default to Bluetooth PAN or Wi-Fi

type Listener<T> = (data: T) => void;

export class NativeBridgeService {
  private static instance: NativeBridgeService;
  private ws: WebSocket | null = null;
  private serverHost: string = DEFAULT_MESH_HOST;
  private reconnectTimer: any = null;
  private pingTimer: any = null;
  private isScanning: boolean = false;
  
  public myId: string = 'node-' + Math.random().toString(36).substring(2, 7);
  public myNickname: string = Platform.OS === 'android' ? 'Android Phone' : Platform.OS === 'ios' ? 'iPhone' : 'Laptop Client';
  public isConnected: boolean = false;
  public activePeers: PeerNode[] = [];
  public currentCallSession: string | null = null;

  // Event listener registries
  private peerListListeners: Set<Listener<PeerNode[]>> = new Set();
  private incomingCallListeners: Set<Listener<{ sessionId: string; callerId: string; callerName: string; peer: PeerNode }>> = new Set();
  private callStateListeners: Set<Listener<{ sessionId: string; state: string }>> = new Set();
  private callStatsListeners: Set<Listener<ActiveCallStats>> = new Set();
  private messageListeners: Set<Listener<ChatMessage>> = new Set();
  private sosListeners: Set<Listener<EmergencySOSAlert>> = new Set();
  private pttListeners: Set<Listener<{ senderName: string; active: boolean }>> = new Set();
  private connectionStatusListeners: Set<Listener<boolean>> = new Set();
  private hostChangeListeners: Set<Listener<string>> = new Set();

  private constructor() {
    this.connectWebSocket(this.serverHost);
  }

  public static getInstance(): NativeBridgeService {
    if (!NativeBridgeService.instance) {
      NativeBridgeService.instance = new NativeBridgeService();
    }
    return NativeBridgeService.instance;
  }

  public setServerHost(host: string) {
    let cleanHost = host.trim().replace(/^https?:\/\//i, '').replace(/^wss?:\/\//i, '');
    if (!cleanHost.includes(':')) {
      cleanHost = `${cleanHost}:3000`;
    }
    this.serverHost = cleanHost;
    this.notifyHostChanged(this.serverHost);

    if (this.ws) {
      try { this.ws.close(); } catch (e) {}
      this.ws = null;
    }
    this.connectWebSocket(this.serverHost);
  }

  public getServerHost(): string {
    return this.serverHost;
  }

  /**
   * Fast auto-discovery: tests candidate IP endpoints and connects to the first responding server
   */
  public async autoScanMeshServers(): Promise<boolean> {
    if (this.isScanning) return false;
    this.isScanning = true;
    console.log('[Mesh Radio] Starting auto-scan across Bluetooth PAN & Wi-Fi candidates...');

    // Prioritize current host, Bluetooth PAN, Wi-Fi, USB
    const candidates = [
      this.serverHost,
      ...CANDIDATE_MESH_HOSTS
    ].filter((v, i, a) => a.indexOf(v) === i);

    for (const host of candidates) {
      if (this.isConnected) {
        this.isScanning = false;
        return true;
      }

      const reached = await this.testHostConnection(host);
      if (reached) {
        console.log(`[Mesh Radio] Auto-discovery succeeded on ${host}`);
        this.serverHost = host;
        this.notifyHostChanged(this.serverHost);
        this.isScanning = false;
        return true;
      }
    }

    this.isScanning = false;
    console.log('[Mesh Radio] Auto-scan completed. Retrying current host...');
    this.connectWebSocket(this.serverHost);
    return false;
  }

  private testHostConnection(host: string): Promise<boolean> {
    return new Promise((resolve) => {
      let settled = false;
      try {
        const testWs = new WebSocket(`ws://${host}`);
        const timeout = setTimeout(() => {
          if (!settled) {
            settled = true;
            try { testWs.close(); } catch (e) {}
            resolve(false);
          }
        }, 1200);

        testWs.onopen = () => {
          if (!settled) {
            settled = true;
            clearTimeout(timeout);
            try { testWs.close(); } catch (e) {}
            // Now establish regular connection on this verified host
            this.serverHost = host;
            this.connectWebSocket(host);
            resolve(true);
          }
        };

        testWs.onerror = () => {
          if (!settled) {
            settled = true;
            clearTimeout(timeout);
            resolve(false);
          }
        };
      } catch (e) {
        if (!settled) {
          settled = true;
          resolve(false);
        }
      }
    });
  }

  public connectWebSocket(targetHost?: string) {
    if (this.reconnectTimer) clearTimeout(this.reconnectTimer);
    if (this.pingTimer) clearInterval(this.pingTimer);

    const host = targetHost || this.serverHost;

    try {
      const wsUrl = `ws://${host}`;
      console.log(`[Mesh Radio] Connecting to ${wsUrl}...`);
      this.ws = new WebSocket(wsUrl);

      this.ws.onopen = () => {
        console.log(`[Mesh Radio] Connected to Mesh Server at ${host}`);
        this.isConnected = true;
        this.serverHost = host;
        this.notifyHostChanged(host);
        this.notifyConnectionStatus(true);

        // Join room and set nickname
        this.sendJson({
          type: 'SET_NICKNAME',
          nickname: this.myNickname,
          deviceType: Platform.OS === 'android' ? 'Android' : Platform.OS === 'ios' ? 'iOS' : 'Laptop',
          room: 'INDIA-MAIN',
        });

        // Keepalive heartbeat
        this.pingTimer = setInterval(() => {
          if (this.ws && this.ws.readyState === WebSocket.OPEN) {
            this.sendJson({ type: 'PING' });
          }
        }, 5000);
      };

      this.ws.onmessage = (event) => {
        try {
          if (typeof event.data !== 'string') return;
          const data = JSON.parse(event.data);

          if (data.type === 'PONG') return;

          if (data.type === 'ASSIGN_ID') {
            this.myId = data.id;
            if (data.nickname) this.myNickname = data.nickname;
            console.log(`[Mesh Radio] Assigned node ID: ${this.myId} (${this.myNickname})`);
          } else if (data.type === 'PEER_LIST') {
            const peers: PeerNode[] = (data.peers || [])
              .filter((p: any) => p.id !== this.myId)
              .map((p: any) => ({
                id: p.id,
                nickname: p.nickname || 'Unknown Node',
                rssi: p.rssi || -55,
                batteryPercent: p.batteryPercent || 85,
                hopCount: p.hopCount || 1,
                transport: p.deviceType === 'Android' ? 'WIFI_DIRECT' : 'BLE_L2CAP',
                isPaired: true,
                lastSeenMs: Date.now(),
              }));
            this.activePeers = peers;
            this.notifyPeerList(peers);
          } else if (data.type === 'CALL_INVITE') {
            // Incoming call from laptop or another phone
            const callerPeer: PeerNode = {
              id: data.senderId,
              nickname: data.senderName || 'Mesh User',
              rssi: -50,
              batteryPercent: 90,
              hopCount: 1,
              transport: 'WIFI_DIRECT',
              isPaired: true,
              lastSeenMs: Date.now(),
            };
            this.notifyIncomingCall({
              sessionId: `call_${Date.now()}`,
              callerId: data.senderId,
              callerName: data.senderName || 'Mesh User',
              peer: callerPeer,
            });
          } else if (data.type === 'CALL_ACCEPT') {
            this.notifyCallState({ sessionId: this.currentCallSession || 'active', state: 'CONNECTED' });
          } else if (data.type === 'CALL_DECLINE' || data.type === 'CALL_HANGUP') {
            this.currentCallSession = null;
            this.notifyCallState({ sessionId: 'ended', state: 'ENDED' });
          } else if (data.type === 'PTT_START') {
            this.notifyPTT({ senderName: data.senderName || 'Mesh Peer', active: true });
          } else if (data.type === 'PTT_STOP') {
            this.notifyPTT({ senderName: data.senderName || 'Mesh Peer', active: false });
          } else if (data.type === 'CHAT_MSG') {
            const chatMsg: ChatMessage = {
              id: `msg_${Date.now()}`,
              senderId: data.senderId,
              senderName: data.senderName || 'Mesh Peer',
              recipientId: this.myId,
              text: data.text,
              timestamp: Date.now(),
              status: 'DELIVERED',
              hopCount: 1,
            };
            this.notifyMessage(chatMsg);
          } else if (data.type === 'SOS_ALERT') {
            const sosAlert: EmergencySOSAlert = {
              id: `sos_${Date.now()}`,
              senderId: data.senderId,
              senderName: data.senderName || 'Mesh User',
              timestamp: Date.now(),
              message: data.message || 'EMERGENCY SOS',
              latitude: data.latitude,
              longitude: data.longitude,
              hasLocation: !!data.latitude,
              hopCount: 1,
            };
            this.notifySOS(sosAlert);
          }
        } catch (e) {
          console.error('[Mesh Radio] Error parsing packet:', e);
        }
      };

      this.ws.onerror = (err) => {
        console.warn('[Mesh Radio] WebSocket connection error on', host, err);
      };

      this.ws.onclose = () => {
        this.isConnected = false;
        this.notifyConnectionStatus(false);
        if (this.pingTimer) clearInterval(this.pingTimer);

        // Attempt fallback / reconnect
        this.reconnectTimer = setTimeout(() => {
          if (!this.isConnected && !this.isScanning) {
            this.autoScanMeshServers();
          }
        }, 2500);
      };
    } catch (e) {
      console.warn('[Mesh Radio] Socket initialization error:', e);
      this.reconnectTimer = setTimeout(() => {
        if (!this.isConnected && !this.isScanning) {
          this.autoScanMeshServers();
        }
      }, 2500);
    }
  }

  public sendRawAudioFrame(arrayBuffer: ArrayBuffer) {
    if (this.ws && this.ws.readyState === WebSocket.OPEN) {
      this.ws.send(arrayBuffer);
    }
  }

  private sendJson(payload: any) {
    if (this.ws && this.ws.readyState === WebSocket.OPEN) {
      this.ws.send(JSON.stringify(payload));
    }
  }

  public async initiateCall(peerId: string, peerNickname: string): Promise<string> {
    const sessionId = `call_${Date.now()}`;
    this.currentCallSession = sessionId;
    this.sendJson({
      type: 'CALL_INVITE',
      targetId: peerId,
    });
    return sessionId;
  }

  public async acceptIncomingCall(sessionId: string, targetId?: string): Promise<void> {
    this.currentCallSession = sessionId;
    this.sendJson({
      type: 'CALL_ACCEPT',
      targetId: targetId,
    });
  }

  public async declineIncomingCall(targetId?: string): Promise<void> {
    this.currentCallSession = null;
    this.sendJson({
      type: 'CALL_DECLINE',
      targetId: targetId,
    });
  }

  public async endCall(sessionId?: string): Promise<void> {
    this.currentCallSession = null;
    this.sendJson({
      type: 'CALL_HANGUP',
    });
  }

  public async startPTT(): Promise<void> {
    this.sendJson({
      type: 'PTT_START',
    });
  }

  public async stopPTT(): Promise<void> {
    this.sendJson({
      type: 'PTT_STOP',
    });
  }

  public async sendTextMessage(recipientId: string, text: string): Promise<string> {
    const msgId = `msg_${Date.now()}`;
    this.sendJson({
      type: 'CHAT_MSG',
      recipientId,
      text,
    });
    return msgId;
  }

  public async broadcastEmergencySOS(message: string, lat?: number, lon?: number): Promise<string> {
    const sosId = `sos_${Date.now()}`;
    this.sendJson({
      type: 'SOS_ALERT',
      message,
      latitude: lat || 0,
      longitude: lon || 0,
      hasLocation: !!lat,
    });
    return sosId;
  }

  public sendLocationUpdate(lat: number, lon: number, altitude: number = 0) {
    this.sendJson({
      type: 'LOCATION_UPDATE',
      latitude: lat,
      longitude: lon,
      altitude,
      accuracy: 5,
    });
  }

  // Event Subscription Helpers
  public onPeerListUpdated(callback: Listener<PeerNode[]>) {
    this.peerListListeners.add(callback);
    callback(this.activePeers);
    return () => { this.peerListListeners.delete(callback); };
  }

  public onIncomingCall(callback: Listener<{ sessionId: string; callerId: string; callerName: string; peer: PeerNode }>) {
    this.incomingCallListeners.add(callback);
    return () => { this.incomingCallListeners.delete(callback); };
  }

  public onCallStateChanged(callback: Listener<{ sessionId: string; state: string }>) {
    this.callStateListeners.add(callback);
    return () => { this.callStateListeners.delete(callback); };
  }

  public onCallStatsUpdated(callback: Listener<ActiveCallStats>) {
    this.callStatsListeners.add(callback);
    return () => { this.callStatsListeners.delete(callback); };
  }

  public onMessageReceived(callback: Listener<ChatMessage>) {
    this.messageListeners.add(callback);
    return () => { this.messageListeners.delete(callback); };
  }

  public onSOSAlertReceived(callback: Listener<EmergencySOSAlert>) {
    this.sosListeners.add(callback);
    return () => { this.sosListeners.delete(callback); };
  }

  public onPTTStateChanged(callback: Listener<{ senderName: string; active: boolean }>) {
    this.pttListeners.add(callback);
    return () => { this.pttListeners.delete(callback); };
  }

  public onConnectionStatusChanged(callback: Listener<boolean>) {
    this.connectionStatusListeners.add(callback);
    callback(this.isConnected);
    return () => { this.connectionStatusListeners.delete(callback); };
  }

  public onHostChanged(callback: Listener<string>) {
    this.hostChangeListeners.add(callback);
    callback(this.serverHost);
    return () => { this.hostChangeListeners.delete(callback); };
  }

  // Notifiers
  private notifyPeerList(peers: PeerNode[]) {
    this.peerListListeners.forEach((l) => l(peers));
  }
  private notifyIncomingCall(data: any) {
    this.incomingCallListeners.forEach((l) => l(data));
  }
  private notifyCallState(data: any) {
    this.callStateListeners.forEach((l) => l(data));
  }
  private notifyMessage(msg: ChatMessage) {
    this.messageListeners.forEach((l) => l(msg));
  }
  private notifySOS(alert: EmergencySOSAlert) {
    this.sosListeners.forEach((l) => l(alert));
  }
  private notifyPTT(data: { senderName: string; active: boolean }) {
    this.pttListeners.forEach((l) => l(data));
  }
  private notifyConnectionStatus(status: boolean) {
    this.connectionStatusListeners.forEach((l) => l(status));
  }
  private notifyHostChanged(host: string) {
    this.hostChangeListeners.forEach((l) => l(host));
  }
}

export const NativeBridge = NativeBridgeService.getInstance();
