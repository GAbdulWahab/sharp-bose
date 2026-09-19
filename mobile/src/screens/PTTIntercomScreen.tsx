import React, { useState, useEffect } from 'react';
import {
  View,
  Text,
  StyleSheet,
  TouchableOpacity,
  ScrollView,
  Vibration,
  Animated,
} from 'react-native';

interface PTTIntercomScreenProps {
  onBack: () => void;
}

interface ChannelInfo {
  id: number;
  name: string;
  isEmergency: boolean;
  frequency: string;
  activePeers: number;
}

const CHANNELS: ChannelInfo[] = [
  { id: 1, name: 'EMERGENCY & SOS', isEmergency: true, frequency: 'Ch 1 • 2.402 GHz', activePeers: 12 },
  { id: 2, name: 'General Mesh Broadcast', isEmergency: false, frequency: 'Ch 2 • 2.426 GHz', activePeers: 7 },
  { id: 3, name: 'Team Alpha Recon', isEmergency: false, frequency: 'Ch 3 • 2.450 GHz', activePeers: 4 },
  { id: 4, name: 'Tactical Logistics', isEmergency: false, frequency: 'Ch 4 • 2.474 GHz', activePeers: 3 },
];

export const PTTIntercomScreen: React.FC<PTTIntercomScreenProps> = ({ onBack }) => {
  const [selectedChannel, setSelectedChannel] = useState<ChannelInfo>(CHANNELS[0]);
  const [isTransmitting, setIsTransmitting] = useState(false);
  const [isReceiving, setIsReceiving] = useState(false);
  const [activeSpeaker, setActiveSpeaker] = useState<string | null>(null);
  const [squelchMuted, setSquelchMuted] = useState(false);

  // Pulse animation for PTT transmission ring
  const [pulseAnim] = useState(new Animated.Value(1));

  useEffect(() => {
    if (isTransmitting) {
      Animated.loop(
        Animated.sequence([
          Animated.timing(pulseAnim, { toValue: 1.25, duration: 400, useNativeDriver: true }),
          Animated.timing(pulseAnim, { toValue: 1.0, duration: 400, useNativeDriver: true }),
        ])
      ).start();
    } else {
      pulseAnim.setValue(1);
    }
  }, [isTransmitting]);

  const handlePressIn = () => {
    Vibration.vibrate(50);
    setIsTransmitting(true);
    setActiveSpeaker('YOU (Broadcasting)');
  };

  const handlePressOut = () => {
    Vibration.vibrate(30);
    setIsTransmitting(false);
    setActiveSpeaker(null);
  };

  const simulateIncomingTransmission = (speakerName: string) => {
    if (isTransmitting) return;
    setIsReceiving(true);
    setActiveSpeaker(speakerName);
    setTimeout(() => {
      setIsReceiving(false);
      setActiveSpeaker(null);
    }, 4000);
  };

  return (
    <View style={styles.container}>
      {/* Header */}
      <View style={styles.header}>
        <TouchableOpacity style={styles.backButton} onPress={onBack}>
          <Text style={styles.backButtonText}>← Back</Text>
        </TouchableOpacity>
        <Text style={styles.headerTitle}>Walkie-Talkie Intercom</Text>
        <TouchableOpacity
          style={[styles.squelchButton, squelchMuted && styles.squelchButtonActive]}
          onPress={() => setSquelchMuted(!squelchMuted)}
        >
          <Text style={styles.squelchText}>{squelchMuted ? 'SQL: OFF' : 'SQL: ON'}</Text>
        </TouchableOpacity>
      </View>

      {/* Channel Selector */}
      <View style={styles.channelScrollContainer}>
        <ScrollView horizontal showsHorizontalScrollIndicator={false} contentContainerStyle={styles.channelList}>
          {CHANNELS.map((ch) => (
            <TouchableOpacity
              key={ch.id}
              style={[
                styles.channelBadge,
                selectedChannel.id === ch.id && styles.channelBadgeSelected,
                ch.isEmergency && styles.channelBadgeEmergency,
              ]}
              onPress={() => setSelectedChannel(ch)}
            >
              <Text style={[styles.channelBadgeTitle, selectedChannel.id === ch.id && styles.channelBadgeTitleSelected]}>
                {ch.name}
              </Text>
              <Text style={styles.channelBadgeSub}>{ch.activePeers} Nodes Reachable</Text>
            </TouchableOpacity>
          ))}
        </ScrollView>
      </View>

      {/* Active Channel Display */}
      <View style={styles.channelCard}>
        <View style={styles.channelHeaderRow}>
          <Text style={styles.channelLabel}>CURRENT INTERCOM CHANNEL</Text>
          <View style={[styles.statusTag, isTransmitting ? styles.statusTagTx : isReceiving ? styles.statusTagRx : styles.statusTagIdle]}>
            <Text style={styles.statusTagText}>
              {isTransmitting ? '● TX TRANSMITTING' : isReceiving ? '▲ RX RECEIVING' : 'STANDBY'}
            </Text>
          </View>
        </View>

        <Text style={styles.channelNameText}>{selectedChannel.name}</Text>
        <Text style={styles.channelFreqText}>{selectedChannel.frequency}</Text>

        {activeSpeaker ? (
          <View style={styles.speakerBanner}>
            <Text style={styles.speakerBannerText}>🎙️ Floor: {activeSpeaker}</Text>
          </View>
        ) : (
          <View style={styles.idleBanner}>
            <Text style={styles.idleBannerText}>Channel clear • Hold PTT to transmit</Text>
          </View>
        )}
      </View>

      {/* Audio Spectrogram Waveform Simulation */}
      <View style={styles.waveformBox}>
        <View style={styles.waveBarGroup}>
          {[40, 80, 20, 95, 60, 30, 85, 100, 45, 75, 90, 35, 70, 50, 90, 60, 30, 80].map((height, idx) => (
            <View
              key={idx}
              style={[
                styles.waveBar,
                {
                  height: isTransmitting || isReceiving ? height * 0.7 + 10 : 8,
                  backgroundColor: isTransmitting ? '#F43F5E' : isReceiving ? '#06B6D4' : '#334155',
                },
              ]}
            />
          ))}
        </View>
      </View>

      {/* Big Push-To-Talk Button */}
      <View style={styles.pttContainer}>
        <Animated.View
          style={[
            styles.pttRing,
            {
              transform: [{ scale: pulseAnim }],
              borderColor: isTransmitting ? '#F43F5E' : '#06B6D4',
            },
          ]}
        />
        <TouchableOpacity
          activeOpacity={0.85}
          onPressIn={handlePressIn}
          onPressOut={handlePressOut}
          style={[styles.pttButton, isTransmitting && styles.pttButtonActive, isReceiving && styles.pttButtonReceiving]}
        >
          <Text style={styles.pttButtonTitle}>{isTransmitting ? 'TRANSMITTING' : 'HOLD TO TALK'}</Text>
          <Text style={styles.pttButtonSub}>Half-Duplex Mesh Audio</Text>
        </TouchableOpacity>
      </View>

      {/* Simulation Controls */}
      <View style={styles.simRow}>
        <TouchableOpacity
          style={styles.simButton}
          onPress={() => simulateIncomingTransmission('Node-Bravo (Base Camp)')}
        >
          <Text style={styles.simButtonText}>Simulate Inbound Transmission</Text>
        </TouchableOpacity>
      </View>
    </View>
  );
};

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: '#090D16', paddingHorizontal: 16, paddingTop: 48 },
  header: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', marginBottom: 16 },
  backButton: { paddingVertical: 8, paddingHorizontal: 12, backgroundColor: '#1E293B', borderRadius: 8 },
  backButtonText: { color: '#94A3B8', fontSize: 13, fontWeight: '600' },
  headerTitle: { color: '#F8FAFC', fontSize: 17, fontWeight: 'bold' },
  squelchButton: { paddingVertical: 6, paddingHorizontal: 10, backgroundColor: '#1E293B', borderRadius: 8 },
  squelchButtonActive: { backgroundColor: '#06B6D4' },
  squelchText: { color: '#F8FAFC', fontSize: 11, fontWeight: 'bold' },
  channelScrollContainer: { marginBottom: 16 },
  channelList: { gap: 10 },
  channelBadge: { padding: 12, backgroundColor: '#1E293B', borderRadius: 12, minWidth: 140, borderWidth: 1, borderColor: '#334155' },
  channelBadgeSelected: { borderColor: '#06B6D4', backgroundColor: '#0F283D' },
  channelBadgeEmergency: { borderColor: '#F43F5E' },
  channelBadgeTitle: { color: '#E2E8F0', fontSize: 13, fontWeight: 'bold' },
  channelBadgeTitleSelected: { color: '#06B6D4' },
  channelBadgeSub: { color: '#64748B', fontSize: 11, marginTop: 4 },
  channelCard: { backgroundColor: '#131D2E', borderRadius: 16, padding: 16, borderWidth: 1, borderColor: '#1E293B' },
  channelHeaderRow: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center' },
  channelLabel: { color: '#64748B', fontSize: 10, fontWeight: 'bold', letterSpacing: 1 },
  statusTag: { paddingHorizontal: 8, paddingVertical: 4, borderRadius: 6 },
  statusTagIdle: { backgroundColor: '#1E293B' },
  statusTagTx: { backgroundColor: '#7F1D1D' },
  statusTagRx: { backgroundColor: '#083344' },
  statusTagText: { color: '#F8FAFC', fontSize: 10, fontWeight: 'bold' },
  channelNameText: { color: '#F8FAFC', fontSize: 20, fontWeight: 'bold', marginTop: 8 },
  channelFreqText: { color: '#94A3B8', fontSize: 12, marginTop: 2, fontFamily: 'monospace' },
  speakerBanner: { marginTop: 12, padding: 10, backgroundColor: '#083344', borderRadius: 8, borderWidth: 1, borderColor: '#06B6D4' },
  speakerBannerText: { color: '#06B6D4', fontWeight: 'bold', fontSize: 13 },
  idleBanner: { marginTop: 12, padding: 8, backgroundColor: '#1E293B', borderRadius: 8 },
  idleBannerText: { color: '#64748B', fontSize: 12, textAlign: 'center' },
  waveformBox: { height: 70, justifyContent: 'center', alignItems: 'center', marginVertical: 20 },
  waveBarGroup: { flexDirection: 'row', alignItems: 'center', gap: 6 },
  waveBar: { width: 5, borderRadius: 3 },
  pttContainer: { alignItems: 'center', justifyContent: 'center', marginTop: 10, marginBottom: 20 },
  pttRing: { position: 'absolute', width: 220, height: 220, borderRadius: 110, borderWidth: 2 },
  pttButton: {
    width: 180,
    height: 180,
    borderRadius: 90,
    backgroundColor: '#0F172A',
    borderWidth: 4,
    borderColor: '#06B6D4',
    justifyContent: 'center',
    alignItems: 'center',
    elevation: 8,
    shadowColor: '#06B6D4',
    shadowOpacity: 0.4,
    shadowRadius: 16,
  },
  pttButtonActive: { backgroundColor: '#E11D48', borderColor: '#FFE4E6' },
  pttButtonReceiving: { backgroundColor: '#0E7490', borderColor: '#67E8F9' },
  pttButtonTitle: { color: '#FFFFFF', fontSize: 17, fontWeight: '900', letterSpacing: 1 },
  pttButtonSub: { color: '#94A3B8', fontSize: 11, marginTop: 4 },
  simRow: { marginTop: 10, alignItems: 'center' },
  simButton: { paddingVertical: 10, paddingHorizontal: 16, backgroundColor: '#1E293B', borderRadius: 10 },
  simButtonText: { color: '#94A3B8', fontSize: 12, fontWeight: '600' },
});
