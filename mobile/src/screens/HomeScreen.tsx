import React, { useState } from 'react';
import { View, Text, StyleSheet, TouchableOpacity, ScrollView, SafeAreaView } from 'react-native';
import { ConnectionRadar } from '../components/ConnectionRadar';
import { PeerNode } from '../types/protocol';

interface HomeScreenProps {
  onStartCall: (peer: PeerNode) => void;
  onOpenChat: (peer: PeerNode) => void;
  onOpenSOS: () => void;
  onOpenDiagnostics: () => void;
  onOpenContacts: () => void;
}

export const HomeScreen: React.FC<HomeScreenProps> = ({
  onStartCall,
  onOpenChat,
  onOpenSOS,
  onOpenDiagnostics,
  onOpenContacts,
}) => {
  const [nickname, setNickname] = useState('Alex-Phone');
  const [nearbyPeers, setNearbyPeers] = useState<PeerNode[]>([
    {
      id: '0x7F4A21B9',
      nickname: 'Sarah-iPhone',
      rssi: -58,
      batteryPercent: 88,
      hopCount: 1,
      transport: 'BLE_L2CAP',
      isPaired: true,
      lastSeenMs: Date.now(),
    },
    {
      id: '0x99C2E8A1',
      nickname: 'David-Pixel',
      rssi: -72,
      batteryPercent: 64,
      hopCount: 1,
      transport: 'WIFI_DIRECT',
      isPaired: false,
      lastSeenMs: Date.now(),
    },
    {
      id: '0x1B44DD20',
      nickname: 'Relay-Node-C',
      rssi: -84,
      batteryPercent: 42,
      hopCount: 2,
      transport: 'MESH_RELAY',
      isPaired: true,
      lastSeenMs: Date.now(),
    },
  ]);

  return (
    <SafeAreaView style={styles.container}>
      <ScrollView contentContainerStyle={styles.scrollContent}>
        {/* Top Header */}
        <View style={styles.header}>
          <View>
            <Text style={styles.title}>OFFLINE CALLING</Text>
            <Text style={styles.statusOnline}>● Local Radios Active (Zero Data Used)</Text>
          </View>
          <TouchableOpacity style={styles.diagBadge} onPress={onOpenDiagnostics}>
            <Text style={styles.diagText}>⚙️ DEV STATS</Text>
          </TouchableOpacity>
        </View>

        {/* Range & Network Banner */}
        <View style={styles.rangeBanner}>
          <View style={styles.rangeRow}>
            <Text style={styles.rangeLabel}>Direct Wireless Range</Text>
            <Text style={styles.rangeValue}>~25m Direct / Multi-hop Mesh</Text>
          </View>
          <View style={styles.progressBarBg}>
            <View style={styles.progressBarFill} />
          </View>
          <Text style={styles.rangeDisclaimer}>
            Calls transmit directly via Bluetooth & Wi-Fi peer-to-peer. No internet or cell towers required.
          </Text>
        </View>

        {/* Quick Action Grid */}
        <View style={styles.quickGrid}>
          <TouchableOpacity style={[styles.card, styles.sosCard]} onPress={onOpenSOS}>
            <Text style={styles.cardEmoji}>🚨</Text>
            <Text style={styles.cardTitle}>EMERGENCY SOS</Text>
            <Text style={styles.cardSub}>Flood alert to nearby nodes</Text>
          </TouchableOpacity>

          <TouchableOpacity style={styles.card} onPress={onOpenContacts}>
            <Text style={styles.cardEmoji}>👥</Text>
            <Text style={styles.cardTitle}>CONTACTS</Text>
            <Text style={styles.cardSub}>Verified offline keys</Text>
          </TouchableOpacity>
        </View>

        {/* Nearby Discovery Radar */}
        <ConnectionRadar
          peers={nearbyPeers}
          onSelectPeer={(p) => console.log('Selected peer', p)}
          onCallPeer={onStartCall}
          onMessagePeer={onOpenChat}
        />
      </ScrollView>
    </SafeAreaView>
  );
};

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: '#090D16',
  },
  scrollContent: {
    padding: 16,
  },
  header: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    marginBottom: 16,
  },
  title: {
    color: '#F8FAFC',
    fontSize: 22,
    fontWeight: '800',
    letterSpacing: 0.5,
  },
  statusOnline: {
    color: '#10B981',
    fontSize: 12,
    fontWeight: '600',
    marginTop: 2,
  },
  diagBadge: {
    backgroundColor: '#1E293B',
    paddingHorizontal: 10,
    paddingVertical: 6,
    borderRadius: 8,
    borderWidth: 1,
    borderColor: '#334155',
  },
  diagText: {
    color: '#94A3B8',
    fontSize: 11,
    fontWeight: '700',
  },
  rangeBanner: {
    backgroundColor: '#1E293B',
    borderRadius: 12,
    padding: 14,
    marginBottom: 16,
    borderWidth: 1,
    borderColor: '#334155',
  },
  rangeRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    marginBottom: 8,
  },
  rangeLabel: {
    color: '#94A3B8',
    fontSize: 12,
    fontWeight: '600',
  },
  rangeValue: {
    color: '#38BDF8',
    fontSize: 12,
    fontWeight: '700',
  },
  progressBarBg: {
    height: 6,
    backgroundColor: '#0F172A',
    borderRadius: 3,
    overflow: 'hidden',
    marginBottom: 8,
  },
  progressBarFill: {
    width: '75%',
    height: '100%',
    backgroundColor: '#38BDF8',
  },
  rangeDisclaimer: {
    color: '#64748B',
    fontSize: 11,
    lineHeight: 15,
  },
  quickGrid: {
    flexDirection: 'row',
    gap: 12,
    marginBottom: 16,
  },
  card: {
    flex: 1,
    backgroundColor: '#1E293B',
    borderRadius: 12,
    padding: 14,
    borderWidth: 1,
    borderColor: '#334155',
  },
  sosCard: {
    backgroundColor: '#3B0712',
    borderColor: '#7F1D1D',
  },
  cardEmoji: {
    fontSize: 22,
    marginBottom: 6,
  },
  cardTitle: {
    color: '#F8FAFC',
    fontSize: 13,
    fontWeight: '700',
  },
  cardSub: {
    color: '#94A3B8',
    fontSize: 11,
    marginTop: 2,
  },
});
