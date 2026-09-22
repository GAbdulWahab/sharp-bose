import { Audio } from 'expo-av';
import { Platform } from 'react-native';
import { NativeBridge } from './NativeBridge';

export class AudioService {
  private static instance: AudioService;
  private recording: Audio.Recording | null = null;
  private isCallActive: boolean = false;
  private isMuted: boolean = false;
  private isSpeaker: boolean = false;
  private intervalTimer: any = null;

  // Web Audio Context for Web browser runtime
  private webAudioCtx: any = null;
  private webStream: any = null;
  private webProcessor: any = null;

  private constructor() {}

  public static getInstance(): AudioService {
    if (!AudioService.instance) {
      AudioService.instance = new AudioService();
    }
    return AudioService.instance;
  }

  public async requestPermissions(): Promise<boolean> {
    try {
      const response = await Audio.requestPermissionsAsync();
      return response.granted;
    } catch (e) {
      console.warn('[AudioService] Permission error:', e);
      return false;
    }
  }

  public async startCallAudio(): Promise<boolean> {
    this.isCallActive = true;
    console.log('[AudioService] Starting call audio...');

    try {
      // 1. Configure Expo Audio Mode for low latency VoIP calling
      await Audio.setAudioModeAsync({
        allowsRecordingIOS: true,
        playsInSilentModeIOS: true,
        staysActiveInBackground: true,
        shouldDuckAndroid: true,
        playThroughEarpieceAndroid: !this.isSpeaker,
      });

      // 2. Request microphone permission
      const hasPermission = await this.requestPermissions();
      if (!hasPermission) {
        console.warn('[AudioService] Microphone permission not granted.');
      }

      // 3. Play connection chime
      await this.playTone(600, 150);

      // 4. Start Web Audio streaming if running on Web
      if (Platform.OS === 'web') {
        this.startWebAudio();
      } else {
        // Native Android / iOS recording session
        await this.startNativeVoipSession();
      }

      return true;
    } catch (err) {
      console.error('[AudioService] Error starting call audio:', err);
      return false;
    }
  }

  public async stopCallAudio(): Promise<void> {
    this.isCallActive = false;
    console.log('[AudioService] Stopping call audio...');

    if (this.intervalTimer) {
      clearInterval(this.intervalTimer);
      this.intervalTimer = null;
    }

    // Stop Native Recording
    if (this.recording) {
      try {
        await this.recording.stopAndUnloadAsync();
      } catch (e) {}
      this.recording = null;
    }

    // Stop Web Audio Stream
    if (this.webStream) {
      try {
        this.webStream.getTracks().forEach((t: any) => t.stop());
      } catch (e) {}
      this.webStream = null;
    }
    if (this.webProcessor) {
      try {
        this.webProcessor.disconnect();
      } catch (e) {}
      this.webProcessor = null;
    }

    // Play hangup chime
    await this.playTone(350, 200);
  }

  public async setMute(muted: boolean) {
    this.isMuted = muted;
    if (this.webStream) {
      this.webStream.getAudioTracks().forEach((t: any) => {
        t.enabled = !muted;
      });
    }
  }

  public async setSpeaker(speaker: boolean) {
    this.isSpeaker = speaker;
    try {
      await Audio.setAudioModeAsync({
        allowsRecordingIOS: true,
        playsInSilentModeIOS: true,
        staysActiveInBackground: true,
        shouldDuckAndroid: true,
        playThroughEarpieceAndroid: !speaker,
      });
    } catch (e) {}
  }

  // Play audio frequency tone through Audio synthesizer
  private async playTone(frequency: number, durationMs: number) {
    try {
      const g = globalThis as any;
      if (Platform.OS === 'web' && g.window) {
        const ctx = this.webAudioCtx || new (g.window.AudioContext || g.window.webkitAudioContext)();
        if (ctx.state === 'suspended') await ctx.resume();
        const osc = ctx.createOscillator();
        const gain = ctx.createGain();
        osc.frequency.value = frequency;
        gain.gain.setValueAtTime(0.15, ctx.currentTime);
        gain.gain.exponentialRampToValueAtTime(0.001, ctx.currentTime + durationMs / 1000);
        osc.connect(gain);
        gain.connect(ctx.destination);
        osc.start();
        osc.stop(ctx.currentTime + durationMs / 1000);
      }
    } catch (e) {}
  }

  // Web Browser Audio Streaming (WebRTC / PCM frames)
  private async startWebAudio() {
    const g = globalThis as any;
    if (!g.window || !g.navigator || !g.navigator.mediaDevices) return;

    try {
      this.webAudioCtx = new (g.window.AudioContext || g.window.webkitAudioContext)();
      if (this.webAudioCtx.state === 'suspended') {
        await this.webAudioCtx.resume();
      }

      this.webStream = await g.navigator.mediaDevices.getUserMedia({
        audio: {
          echoCancellation: true,
          noiseSuppression: true,
          autoGainControl: true,
          channelCount: 1,
        },
      });

      const source = this.webAudioCtx.createMediaStreamSource(this.webStream);
      this.webProcessor = this.webAudioCtx.createScriptProcessor(1024, 1, 1);

      this.webProcessor.onaudioprocess = (e: any) => {
        if (!this.isCallActive || this.isMuted) return;
        const inputData = e.inputBuffer.getChannelData(0);

        let sum = 0;
        for (let i = 0; i < inputData.length; i++) {
          sum += Math.abs(inputData[i]);
        }
        if (sum / inputData.length < 0.012) return; // noise gate

        const sampleRate = this.webAudioCtx.sampleRate || 48000;
        const packetBuffer = new ArrayBuffer(4 + inputData.length * 2);
        const header = new DataView(packetBuffer);
        header.setUint8(0, 0xAA);
        header.setUint8(1, 0x55);
        header.setUint16(2, sampleRate, false);

        const pcm16 = new Int16Array(packetBuffer, 4, inputData.length);
        for (let i = 0; i < inputData.length; i++) {
          let s = Math.max(-1, Math.min(1, inputData[i]));
          pcm16[i] = s < 0 ? s * 0x8000 : s * 0x7FFF;
        }

        // Send via NativeBridge WebSocket
        NativeBridge.sendRawAudioFrame(packetBuffer);
      };

      const silentGain = this.webAudioCtx.createGain();
      silentGain.gain.value = 0;
      source.connect(this.webProcessor);
      this.webProcessor.connect(silentGain);
      silentGain.connect(this.webAudioCtx.destination);
    } catch (e) {
      console.warn('[AudioService] Web Audio error:', e);
    }
  }

  // Native Mobile VoIP Audio Session
  private async startNativeVoipSession() {
    try {
      const recordingOptions = {
        android: {
          extension: '.m4a',
          outputFormat: Audio.AndroidOutputFormat.MPEG_4,
          audioEncoder: Audio.AndroidAudioEncoder.AAC,
          sampleRate: 44100,
          numberOfChannels: 1,
          bitRate: 64000,
        },
        ios: {
          extension: '.m4a',
          audioQuality: Audio.IOSAudioQuality.HIGH,
          sampleRate: 44100,
          numberOfChannels: 1,
          bitRate: 64000,
          linearPCMBitDepth: 16,
          linearPCMIsBigEndian: false,
          linearPCMIsFloat: false,
        },
        web: {
          mimeType: 'audio/webm',
          bitsPerSecond: 64000,
        },
      };

      const { recording } = await Audio.Recording.createAsync(
        recordingOptions,
        (status) => {
          if (status.canRecord && !status.isRecording && this.isCallActive) {
            // Active recording stream telemetry
          }
        },
        100
      );
      this.recording = recording;
      console.log('[AudioService] Native Audio recording session established.');
    } catch (err) {
      console.warn('[AudioService] Native Recording notice:', err);
    }
  }
}

export const AppAudio = AudioService.getInstance();
