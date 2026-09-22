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
import { NativeBridge } from '../services/NativeBridge';

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
    // Listen for live PTT events from Laptop or other phones
    const unsubscribePTT = NativeBridge.onPTTStateChanged((data) => {
      setIsReceiving(data.active);
      setActiveSpeaker(data.active ? `${data.senderName} (Broadcasting)` : null);
      if (data.active) {
        Vibration.vibrate([0, 40, 60, 40]);
      }
    });

    return () => {
      unsubscribePTT();
    };
  }, []);

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
    NativeBridge.startPTT();
  };

  const handlePressOut = () => {
    Vibration.vibrate(30);
    setIsTransmitting(false);
    setActiveSpeaker(null);
    NativeBridge.stopPTT();
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
              <Text
                style={[
                  styles.channelBadgeText,
                  selectedChannel.id === ch.id && styles.channelBadgeTextSelected,
                ]}
              >
                {ch.name}
              </Text>
            </TouchableOpacity>
          ))}
        </ScrollView>
      </View>

      {/* Frequency / Status Bar */}
      <View style={styles.statusBox}>
        <View style={styles.statusRow}>
          <Text style={styles.statusLabel}>CH FREQUENCY</Text>
          <Text style={styles.statusValue}>{selectedChannel.frequency}</Text>
        </View>
        <View style={styles.statusRow}>
          <Text style={styles.statusLabel}>ACTIVE PEERS IN REACH</Text>
          <Text style={[styles.statusValue, { color: '#10B981' }]}>
            {NativeBridge.activePeers.length + 1} Radios Connected
          </Text>
        </View>
        <View style={styles.statusRow}>
          <Text style={styles.statusLabel}>RADIO TRAFFIC STATE</Text>
          <Text
            style={[
              styles.statusValue,
              isTransmitting
                ? { color: '#EF4444' }
                : isReceiving
                ? { color: '#38BDF8' }
                : { color: '#94A3B8' },
            ]}
          >
            {isTransmitting
              ? '🔴 TX: YOU ARE LIVE'
              : isReceiving
              ? `🔵 RX: ${activeSpeaker || 'INBOUND AUDIO'}`
              : 'IDLE (CHANNEL CLEAR)'}
          </Text>
        </View>
      </View>

      {/* Center Waveform & Speaker Status */}
      <View style={styles.speakerCenter}>
        <Text style={styles.speakerTitle}>
          {isTransmitting
            ? 'Transmitting HD Audio...'
            : isReceiving
            ? `Receiving from ${activeSpeaker}`
            : 'Hold Button to Broadcast'}
        </Text>
        <Text style={styles.speakerSub}>
          {isTransmitting
            ? 'All mesh nodes listening on this channel'
            : isReceiving
            ? 'Audio decoded via Opus 16kHz Squelch Filter'
            : 'Press and hold big button below to talk'}
        </Text>
      </View>

      {/* Large PTT Push Button */}
      <View style={styles.pttButtonContainer}>
        <Animated.View
          style={[
            styles.pulseRing,
            (isTransmitting || isReceiving) && styles.pulseRingActive,
            isReceiving && { borderColor: '#38BDF8', backgroundColor: 'rgba(56, 189, 248, 0.15)' },
            { transform: [{ scale: pulseAnim }] },
          ]}
        />
        <TouchableOpacity
          style={[
            styles.pttButton,
            isTransmitting && styles.pttButtonTransmitting,
            isReceiving && styles.pttButtonReceiving,
          ]}
          activeOpacity={0.8}
          onPressIn={handlePressIn}
          onPressOut={handlePressOut}
        >
          <Text style={styles.pttIcon}>
            {isTransmitting ? '🎙️' : isReceiving ? '🔊' : '📻'}
          </Text>
          <Text style={styles.pttMainText}>
            {isTransmitting ? 'TALKING' : isReceiving ? 'RECEIVING' : 'PUSH TO TALK'}
          </Text>
          <Text style={styles.pttSubText}>
            {isTransmitting ? 'Release to listen' : 'Hold to broadcast'}
          </Text>
        </TouchableOpacity>
      </View>

      {/* Footer Info */}
      <View style={styles.footer}>
        <Text style={styles.footerText}>
          🔒 End-to-End Encrypted Group Broadcast • Half-Duplex RF Protocol
        </Text>
      </View>
    </View>
  );
};

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: '#090D16',
    padding: 16,
    justifyContent: 'space-between',
  },
  header: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    marginBottom: 12,
  },
  backButton: {
    paddingVertical: 6,
    paddingHorizontal: 12,
    backgroundColor: '#1E293B',
    borderRadius: 8,
  },
  backButtonText: {
    color: '#94A3B8',
    fontWeight: '700',
    fontSize: 13,
  },
  headerTitle: {
    color: '#F8FAFC',
    fontSize: 17,
    fontWeight: '800',
  },
  squelchButton: {
    paddingVertical: 6,
    paddingHorizontal: 10,
    backgroundColor: '#1E293B',
    borderRadius: 8,
    borderWidth: 1,
    borderColor: '#334155',
  },
  squelchButtonActive: {
    backgroundColor: '#065F46',
    borderColor: '#10B981',
  },
  squelchText: {
    color: '#34D399',
    fontSize: 11,
    fontWeight: '700',
  },
  channelScrollContainer: {
    marginBottom: 12,
  },
  channelList: {
    gap: 8,
  },
  channelBadge: {
    backgroundColor: '#1E293B',
    paddingVertical: 8,
    paddingHorizontal: 14,
    borderRadius: 20,
    borderWidth: 1,
    borderColor: '#334155',
  },
  channelBadgeSelected: {
    backgroundColor: '#0284C7',
    borderColor: '#38BDF8',
  },
  channelBadgeEmergency: {
    borderColor: '#EF4444',
  },
  channelBadgeText: {
    color: '#94A3B8',
    fontSize: 12,
    fontWeight: '700',
  },
  channelBadgeTextSelected: {
    color: '#FFFFFF',
  },
  statusBox: {
    backgroundColor: '#1E293B',
    borderRadius: 12,
    padding: 12,
    gap: 8,
    borderWidth: 1,
    borderColor: '#334155',
  },
  statusRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
  },
  statusLabel: {
    color: '#64748B',
    fontSize: 10,
    fontWeight: '700',
    letterSpacing: 0.5,
  },
  statusValue: {
    color: '#F8FAFC',
    fontSize: 12,
    fontWeight: '700',
  },
  speakerCenter: {
    alignItems: 'center',
    marginVertical: 10,
  },
  speakerTitle: {
    color: '#F8FAFC',
    fontSize: 18,
    fontWeight: '800',
    marginBottom: 4,
  },
  speakerSub: {
    color: '#94A3B8',
    fontSize: 12,
    textAlign: 'center',
  },
  pttButtonContainer: {
    alignItems: 'center',
    justifyContent: 'center',
    position: 'relative',
    height: 240,
  },
  pulseRing: {
    position: 'absolute',
    width: 220,
    height: 220,
    borderRadius: 110,
    borderWidth: 2,
    borderColor: 'transparent',
  },
  pulseRingActive: {
    borderColor: '#EF4444',
    backgroundColor: 'rgba(239, 68, 68, 0.15)',
  },
  pttButton: {
    width: 190,
    height: 190,
    borderRadius: 95,
    backgroundColor: '#1E293B',
    borderWidth: 4,
    borderColor: '#0284C7',
    alignItems: 'center',
    justifyContent: 'center',
    shadowColor: '#000',
    shadowOpacity: 0.4,
    shadowRadius: 10,
    elevation: 8,
  },
  pttButtonTransmitting: {
    backgroundColor: '#B91C1C',
    borderColor: '#EF4444',
  },
  pttButtonReceiving: {
    backgroundColor: '#0369A1',
    borderColor: '#38BDF8',
  },
  pttIcon: {
    fontSize: 40,
    marginBottom: 6,
  },
  pttMainText: {
    color: '#F8FAFC',
    fontSize: 15,
    fontWeight: '900',
    letterSpacing: 1,
  },
  pttSubText: {
    color: '#94A3B8',
    fontSize: 10,
    marginTop: 4,
  },
  footer: {
    alignItems: 'center',
    marginBottom: 8,
  },
  footerText: {
    color: '#64748B',
    fontSize: 10,
    textAlign: 'center',
  },
});
