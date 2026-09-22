import React, { useState, useEffect } from 'react';
import { View, Text, StyleSheet, TouchableOpacity, ScrollView, SafeAreaView } from 'react-native';
import { ConnectionRadar } from '../components/ConnectionRadar';
import { ConnectionSettingsModal } from '../components/ConnectionSettingsModal';
import { PeerNode } from '../types/protocol';
import { ThemeColors } from '../types/theme';
import { NativeBridge } from '../services/NativeBridge';

interface HomeScreenProps {
  onStartCall: (peer: PeerNode) => void;
  onOpenChat: (peer: PeerNode) => void;
  onOpenSOS: () => void;
  onOpenDiagnostics: () => void;
  onOpenContacts: () => void;
  onOpenPTT: () => void;
  onOpenFileShare: () => void;
  onOpenTacticalMap: () => void;
  onOpenConnectedPeers: () => void;
  onSimulateIncomingCall: () => void;
  isDarkMode: boolean;
  onToggleTheme: () => void;
  theme: ThemeColors;
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
  onOpenConnectedPeers,
  onSimulateIncomingCall,
  isDarkMode,
  onToggleTheme,
  theme,
}) => {
  const [nearbyPeers, setNearbyPeers] = useState<PeerNode[]>([]);
  const [isMeshConnected, setIsMeshConnected] = useState(NativeBridge.isConnected);
  const [showSettingsModal, setShowSettingsModal] = useState(false);
  const [currentHost, setCurrentHost] = useState(NativeBridge.getServerHost());

  useEffect(() => {
    const unsubPeers = NativeBridge.onPeerListUpdated((peers) => {
      setNearbyPeers(peers);
    });

    const unsubConn = NativeBridge.onConnectionStatusChanged((connected) => {
      setIsMeshConnected(connected);
    });

    const unsubHost = NativeBridge.onHostChanged((host) => {
      setCurrentHost(host);
    });

    return () => {
      unsubPeers();
      unsubConn();
      unsubHost();
    };
  }, []);

  return (
    <SafeAreaView style={[styles.container, { backgroundColor: theme.bg }]}>
      <ScrollView contentContainerStyle={styles.scrollContent}>
        {/* Connection Settings Modal */}
        <ConnectionSettingsModal
          visible={showSettingsModal}
          onClose={() => setShowSettingsModal(false)}
          theme={theme}
        />

        {/* Top Header with Theme Switcher & Connection Settings */}
        <View style={styles.header}>
          <TouchableOpacity onPress={() => setShowSettingsModal(true)}>
            <Text style={[styles.title, { color: theme.textPrimary }]}>OFFLINE MESH</Text>
            <Text style={[styles.statusOnline, { color: isMeshConnected ? theme.accentEmerald : '#F59E0B' }]}>
              {isMeshConnected ? `● Live (${currentHost})` : '○ Searching Mesh... (Tap to Config)'}
            </Text>
          </TouchableOpacity>
          <View style={styles.headerActions}>
            <TouchableOpacity
              style={[styles.iconButton, { backgroundColor: theme.card, borderColor: theme.accentCyan }]}
              onPress={() => setShowSettingsModal(true)}
            >
              <Text style={styles.iconButtonText}>📶</Text>
            </TouchableOpacity>
            <TouchableOpacity
              style={[styles.iconButton, { backgroundColor: theme.card, borderColor: theme.cardBorder }]}
              onPress={onToggleTheme}
            >
              <Text style={styles.iconButtonText}>{isDarkMode ? '☀️' : '🌙'}</Text>
            </TouchableOpacity>
            <TouchableOpacity
              style={[styles.diagBadge, { backgroundColor: theme.card, borderColor: theme.cardBorder }]}
              onPress={onOpenDiagnostics}
            >
              <Text style={[styles.diagText, { color: theme.accentBlue }]}>⚙️ STATS</Text>
            </TouchableOpacity>
          </View>
        </View>

        {/* Connected Users Live Bar */}
        <TouchableOpacity
          style={[styles.connectedBanner, { backgroundColor: theme.card, borderColor: theme.cardBorder }]}
          onPress={onOpenConnectedPeers}
        >
          <View style={styles.connectedLeft}>
            <View style={[styles.pulseDot, { backgroundColor: theme.accentEmerald }]} />
            <View>
              <Text style={[styles.connectedTitle, { color: theme.textPrimary }]}>
                {nearbyPeers.length + 1} Connected Mesh Users
              </Text>
              <Text style={[styles.connectedSub, { color: theme.textMuted }]}>
                Tap to inspect active peer signals & hops
              </Text>
            </View>
          </View>
          <Text style={[styles.connectedArrow, { color: theme.accentCyan }]}>View →</Text>
        </TouchableOpacity>

        {/* Feature Navigation Grid */}
        <View style={styles.featureGrid}>
          <TouchableOpacity
            style={[styles.featureCard, { backgroundColor: isDarkMode ? '#082538' : '#E0F2FE', borderColor: theme.accentCyan }]}
            onPress={onOpenPTT}
          >
            <Text style={styles.featureIcon}>📻</Text>
            <Text style={[styles.featureTitle, { color: theme.textPrimary }]}>Walkie-Talkie (PTT)</Text>
            <Text style={[styles.featureSub, { color: theme.textSecondary }]}>Half-duplex group audio</Text>
          </TouchableOpacity>

          <TouchableOpacity
            style={[styles.featureCard, { backgroundColor: isDarkMode ? '#063327' : '#DCFCE7', borderColor: theme.accentEmerald }]}
            onPress={onOpenTacticalMap}
          >
            <Text style={styles.featureIcon}>🎯</Text>
            <Text style={[styles.featureTitle, { color: theme.textPrimary }]}>Tactical Radar</Text>
            <Text style={[styles.featureSub, { color: theme.textSecondary }]}>360° Node distance &amp; pins</Text>
          </TouchableOpacity>

          <TouchableOpacity
            style={[styles.featureCard, { backgroundColor: isDarkMode ? '#1E1B4B' : '#EEF2FF', borderColor: theme.accentPurple }]}
            onPress={onOpenFileShare}
          >
            <Text style={styles.featureIcon}>📁</Text>
            <Text style={[styles.featureTitle, { color: theme.textPrimary }]}>Offline Files</Text>
            <Text style={[styles.featureSub, { color: theme.textSecondary }]}>P2P photos, vectors, logs</Text>
          </TouchableOpacity>

          <TouchableOpacity
            style={[styles.featureCard, { backgroundColor: isDarkMode ? '#3B0712' : '#FFE4E6', borderColor: theme.accentRose }]}
            onPress={onOpenSOS}
          >
            <Text style={styles.featureIcon}>🚨</Text>
            <Text style={[styles.featureTitle, { color: theme.textPrimary }]}>Emergency SOS</Text>
            <Text style={[styles.featureSub, { color: theme.textSecondary }]}>Multi-hop distress alert</Text>
          </TouchableOpacity>
        </View>

        {/* Range & Network Info Card */}
        <View style={[styles.rangeBanner, { backgroundColor: theme.card, borderColor: theme.cardBorder }]}>
          <View style={styles.rangeRow}>
            <Text style={[styles.rangeLabel, { color: theme.textSecondary }]}>Direct Wireless Range</Text>
            <Text style={[styles.rangeValue, { color: theme.accentBlue }]}>~25m Direct / Multi-hop</Text>
          </View>
          <View style={[styles.progressBarBg, { backgroundColor: theme.bgSecondary }]}>
            <View style={[styles.progressBarFill, { backgroundColor: theme.accentBlue }]} />
          </View>
          <Text style={[styles.rangeDisclaimer, { color: theme.textMuted }]}>
            Peer-to-peer transmission via Bluetooth &amp; Wi-Fi Direct. Zero internet or cell tower required.
          </Text>
        </View>

        {/* Incoming Call Simulation Banner */}
        <TouchableOpacity
          style={[styles.simCallCard, { backgroundColor: theme.card, borderColor: theme.accentEmerald }]}
          onPress={onSimulateIncomingCall}
        >
          <Text style={styles.simCallIcon}>📲</Text>
          <View style={styles.simCallInfo}>
            <Text style={[styles.simCallTitle, { color: theme.textPrimary }]}>
              Receive Call (Incoming Demo)
            </Text>
            <Text style={[styles.simCallSub, { color: theme.textSecondary }]}>
              Simulate incoming encrypted voice call from peer
            </Text>
          </View>
          <View style={[styles.simCallTag, { backgroundColor: theme.accentEmerald }]}>
            <Text style={styles.simCallTagText}>TRIGGER</Text>
          </View>
        </TouchableOpacity>

        {/* Verified Contacts Button */}
        <TouchableOpacity
          style={[styles.contactsRow, { backgroundColor: theme.card, borderColor: theme.cardBorder }]}
          onPress={onOpenContacts}
        >
          <Text style={styles.contactsIcon}>👥</Text>
          <View style={styles.contactsInfo}>
            <Text style={[styles.contactsTitle, { color: theme.textPrimary }]}>Verified Mesh Contacts</Text>
            <Text style={[styles.contactsSub, { color: theme.textMuted }]}>Manage offline Noise_XX encryption keys</Text>
          </View>
          <Text style={[styles.contactsArrow, { color: theme.textMuted }]}>→</Text>
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
    fontSize: 22,
    fontWeight: '800',
    letterSpacing: 0.5,
  },
  statusOnline: {
    fontSize: 12,
    fontWeight: '600',
    marginTop: 2,
  },
  headerActions: {
    flexDirection: 'row',
    gap: 8,
    alignItems: 'center',
  },
  iconButton: {
    width: 36,
    height: 36,
    borderRadius: 10,
    borderWidth: 1,
    justifyContent: 'center',
    alignItems: 'center',
  },
  iconButtonText: {
    fontSize: 16,
  },
  diagBadge: {
    paddingHorizontal: 10,
    paddingVertical: 8,
    borderRadius: 10,
    borderWidth: 1,
  },
  diagText: {
    fontSize: 11,
    fontWeight: '700',
  },
  connectedBanner: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    borderRadius: 14,
    borderWidth: 1,
    padding: 12,
    marginBottom: 14,
  },
  connectedLeft: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 10,
  },
  pulseDot: {
    width: 10,
    height: 10,
    borderRadius: 5,
  },
  connectedTitle: {
    fontSize: 13,
    fontWeight: 'bold',
  },
  connectedSub: {
    fontSize: 10,
    marginTop: 2,
  },
  connectedArrow: {
    fontSize: 12,
    fontWeight: 'bold',
  },
  featureGrid: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: 10,
    marginBottom: 14,
  },
  featureCard: {
    width: '48%',
    borderRadius: 14,
    padding: 12,
    borderWidth: 1,
  },
  featureIcon: {
    fontSize: 20,
    marginBottom: 4,
  },
  featureTitle: {
    fontSize: 13,
    fontWeight: 'bold',
  },
  featureSub: {
    fontSize: 10,
    marginTop: 2,
  },
  rangeBanner: {
    borderRadius: 14,
    padding: 14,
    marginBottom: 14,
    borderWidth: 1,
  },
  rangeRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    marginBottom: 8,
  },
  rangeLabel: {
    fontSize: 12,
    fontWeight: '600',
  },
  rangeValue: {
    fontSize: 12,
    fontWeight: '700',
  },
  progressBarBg: {
    height: 6,
    borderRadius: 3,
    overflow: 'hidden',
    marginBottom: 8,
  },
  progressBarFill: {
    width: '85%',
    height: '100%',
  },
  rangeDisclaimer: {
    fontSize: 11,
    lineHeight: 15,
  },
  simCallCard: {
    flexDirection: 'row',
    alignItems: 'center',
    borderRadius: 14,
    borderWidth: 1.5,
    padding: 12,
    marginBottom: 14,
  },
  simCallIcon: {
    fontSize: 22,
    marginRight: 10,
  },
  simCallInfo: {
    flex: 1,
  },
  simCallTitle: {
    fontSize: 13,
    fontWeight: 'bold',
  },
  simCallSub: {
    fontSize: 10,
    marginTop: 2,
  },
  simCallTag: {
    paddingHorizontal: 8,
    paddingVertical: 4,
    borderRadius: 6,
  },
  simCallTagText: {
    color: '#FFFFFF',
    fontSize: 10,
    fontWeight: '900',
  },
  contactsRow: {
    flexDirection: 'row',
    alignItems: 'center',
    borderRadius: 14,
    padding: 12,
    marginBottom: 16,
    borderWidth: 1,
  },
  contactsIcon: {
    fontSize: 20,
    marginRight: 10,
  },
  contactsInfo: {
    flex: 1,
  },
  contactsTitle: {
    fontSize: 13,
    fontWeight: 'bold',
  },
  contactsSub: {
    fontSize: 11,
  },
  contactsArrow: {
    fontSize: 16,
    fontWeight: 'bold',
  },
});
