import { NativeModules, NativeEventEmitter, Platform } from 'react-native';
import { PeerNode, ActiveCallStats, ChatMessage, EmergencySOSAlert } from '../types/protocol';

const { OfflineMeshBridge } = NativeModules;
const meshEventEmitter = new NativeEventEmitter(OfflineMeshBridge);

export class NativeBridgeService {
  private static instance: NativeBridgeService;

  private constructor() {
    this.setupListeners();
  }

  public static getInstance(): NativeBridgeService {
    if (!NativeBridgeService.instance) {
      NativeBridgeService.instance = new NativeBridgeService();
    }
    return NativeBridgeService.instance;
  }

  private setupListeners() {
    if (!OfflineMeshBridge) {
      console.warn('[NativeBridge] OfflineMeshBridge native module is running in mockup/simulated mode.');
    }
  }

  public async startMeshDiscovery(nickname: string): Promise<boolean> {
    if (OfflineMeshBridge) {
      return await OfflineMeshBridge.startDiscovery(nickname);
    }
    return true;
  }

  public async stopMeshDiscovery(): Promise<void> {
    if (OfflineMeshBridge) {
      await OfflineMeshBridge.stopDiscovery();
    }
  }

  public async initiateCall(peerId: string, peerNickname: string): Promise<string> {
    if (OfflineMeshBridge) {
      return await OfflineMeshBridge.initiateCall(peerId, peerNickname);
    }
    return `sess_${Date.now()}`;
  }

  public async acceptIncomingCall(sessionId: string): Promise<void> {
    if (OfflineMeshBridge) {
      await OfflineMeshBridge.acceptCall(sessionId);
    }
  }

  public async endCall(sessionId: string): Promise<void> {
    if (OfflineMeshBridge) {
      await OfflineMeshBridge.endCall(sessionId);
    }
  }

  public async setMute(muted: boolean): Promise<void> {
    if (OfflineMeshBridge) {
      await OfflineMeshBridge.setMute(muted);
    }
  }

  public async setSpeaker(speaker: boolean): Promise<void> {
    if (OfflineMeshBridge) {
      await OfflineMeshBridge.setSpeaker(speaker);
    }
  }

  public async sendTextMessage(recipientId: string, text: string): Promise<string> {
    if (OfflineMeshBridge) {
      return await OfflineMeshBridge.sendMessage(recipientId, text);
    }
    return `msg_${Date.now()}`;
  }

  public async broadcastEmergencySOS(message: string, lat?: number, lon?: number): Promise<string> {
    if (OfflineMeshBridge) {
      return await OfflineMeshBridge.broadcastSOS(message, lat || 0, lon || 0, !!lat);
    }
    return `sos_${Date.now()}`;
  }

  public onPeerDiscovered(callback: (peer: PeerNode) => void) {
    return meshEventEmitter.addListener('onPeerDiscovered', callback);
  }

  public onPeerLost(callback: (peerId: string) => void) {
    return meshEventEmitter.addListener('onPeerLost', callback);
  }

  public onIncomingCall(callback: (session: { sessionId: string; callerId: string; callerName: string }) => void) {
    return meshEventEmitter.addListener('onIncomingCall', callback);
  }

  public onCallStateChanged(callback: (state: { sessionId: string; state: string }) => void) {
    return meshEventEmitter.addListener('onCallStateChanged', callback);
  }

  public onCallStatsUpdated(callback: (stats: ActiveCallStats) => void) {
    return meshEventEmitter.addListener('onCallStatsUpdated', callback);
  }

  public onMessageReceived(callback: (msg: ChatMessage) => void) {
    return meshEventEmitter.addListener('onMessageReceived', callback);
  }

  public onSOSAlertReceived(callback: (alert: EmergencySOSAlert) => void) {
    return meshEventEmitter.addListener('onSOSAlertReceived', callback);
  }
}

export const NativeBridge = NativeBridgeService.getInstance();
