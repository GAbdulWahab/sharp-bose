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
  onOpenPTT: () => void;
  onOpenFileShare: () => void;
  onOpenTacticalMap: () => void;
}

export const HomeScreen: React.FC<HomeScreenProps> = ({
  onStartCall,
  onOpenChat,
  onOpenSOS,
  onOpenDiagnostics,
  onOpenContacts,
  onOpenPTT,
  onOpenFileShare,
  onOpenTacticalMap,
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
            <Text style={styles.title}>OFFLINE MESH</Text>
            <Text style={styles.statusOnline}>● Radios Active • BLE L2CAP &amp; Wi-Fi Direct</Text>
          </View>
          <TouchableOpacity style={styles.diagBadge} onPress={onOpenDiagnostics}>
            <Text style={styles.diagText}>⚙️ DEV STATS</Text>
          </TouchableOpacity>
        </View>

        {/* Feature Navigation Cards */}
        <View style={styles.featureGrid}>
          <TouchableOpacity style={[styles.featureCard, styles.pttCard]} onPress={onOpenPTT}>
            <Text style={styles.featureIcon}>📻</Text>
            <Text style={styles.featureTitle}>Walkie-Talkie (PTT)</Text>
            <Text style={styles.featureSub}>Half-duplex group audio</Text>
          </TouchableOpacity>

          <TouchableOpacity style={[styles.featureCard, styles.radarCard]} onPress={onOpenTacticalMap}>
            <Text style={styles.featureIcon}>🎯</Text>
            <Text style={styles.featureTitle}>Tactical Radar</Text>
            <Text style={styles.featureSub}>360° Node distance &amp; pins</Text>
          </TouchableOpacity>

          <TouchableOpacity style={[styles.featureCard, styles.fileCard]} onPress={onOpenFileShare}>
            <Text style={styles.featureIcon}>📁</Text>
            <Text style={styles.featureTitle}>Offline Files</Text>
            <Text style={styles.featureSub}>P2P photos, vectors, logs</Text>
          </TouchableOpacity>

          <TouchableOpacity style={[styles.featureCard, styles.sosCard]} onPress={onOpenSOS}>
            <Text style={styles.featureIcon}>🚨</Text>
            <Text style={styles.featureTitle}>Emergency SOS</Text>
            <Text style={styles.featureSub}>Multi-hop distress broadcast</Text>
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
            Peer-to-peer transmission via Bluetooth &amp; Wi-Fi Direct. Zero internet or cell tower requirement.
          </Text>
        </View>

        {/* Contacts Button */}
        <TouchableOpacity style={styles.contactsRow} onPress={onOpenContacts}>
          <Text style={styles.contactsIcon}>👥</Text>
          <View style={styles.contactsInfo}>
            <Text style={styles.contactsTitle}>Verified Mesh Contacts</Text>
            <Text style={styles.contactsSub}>Manage offline Noise_XX cryptographic keys</Text>
          </View>
          <Text style={styles.contactsArrow}>→</Text>
        </TouchableOpacity>

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
  featureGrid: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: 10,
    marginBottom: 16,
  },
  featureCard: {
    width: '48%',
    backgroundColor: '#1E293B',
    borderRadius: 14,
    padding: 12,
    borderWidth: 1,
    borderColor: '#334155',
  },
  pttCard: {
    borderColor: '#06B6D4',
    backgroundColor: '#082538',
  },
  radarCard: {
    borderColor: '#10B981',
    backgroundColor: '#063327',
  },
  fileCard: {
    borderColor: '#6366F1',
    backgroundColor: '#1E1B4B',
  },
  sosCard: {
    borderColor: '#F43F5E',
    backgroundColor: '#3B0712',
  },
  featureIcon: {
    fontSize: 20,
    marginBottom: 4,
  },
  featureTitle: {
    color: '#F8FAFC',
    fontSize: 13,
    fontWeight: 'bold',
  },
  featureSub: {
    color: '#94A3B8',
    fontSize: 10,
    marginTop: 2,
  },
  rangeBanner: {
    backgroundColor: '#1E293B',
    borderRadius: 12,
    padding: 14,
    marginBottom: 14,
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
    width: '85%',
    height: '100%',
    backgroundColor: '#38BDF8',
  },
  rangeDisclaimer: {
    color: '#64748B',
    fontSize: 11,
    lineHeight: 15,
  },
  contactsRow: {
    flexDirection: 'row',
    alignItems: 'center',
    backgroundColor: '#1E293B',
    borderRadius: 12,
    padding: 12,
    marginBottom: 16,
    borderWidth: 1,
    borderColor: '#334155',
  },
  contactsIcon: {
    fontSize: 20,
    marginRight: 10,
  },
  contactsInfo: {
    flex: 1,
  },
  contactsTitle: {
    color: '#F8FAFC',
    fontSize: 13,
    fontWeight: 'bold',
  },
  contactsSub: {
    color: '#94A3B8',
    fontSize: 11,
  },
  contactsArrow: {
    color: '#94A3B8',
    fontSize: 16,
    fontWeight: 'bold',
  },
});
