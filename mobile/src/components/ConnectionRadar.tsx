import React from 'react';
import { View, Text, StyleSheet, TouchableOpacity, ScrollView } from 'react-native';
import { PeerNode } from '../types/protocol';

interface ConnectionRadarProps {
  peers: PeerNode[];
  onSelectPeer: (peer: PeerNode) => void;
  onCallPeer: (peer: PeerNode) => void;
  onMessagePeer: (peer: PeerNode) => void;
}

export const ConnectionRadar: React.FC<ConnectionRadarProps> = ({
  peers,
  onSelectPeer,
  onCallPeer,
  onMessagePeer,
}) => {
  const getSignalStatus = (rssi: number, hopCount: number) => {
    if (hopCount > 1) {
      return { label: `Mesh (${hopCount} hops)`, color: '#A855F7', dot: '🟣' };
    }
    if (rssi >= -60) return { label: 'Excellent connection', color: '#10B981', dot: '🟢' };
    if (rssi >= -80) return { label: 'Good connection', color: '#38BDF8', dot: '🔵' };
    return { label: 'Weak connection', color: '#F59E0B', dot: '🟡' };
  };

  return (
    <View style={styles.container}>
      <View style={styles.headerRow}>
        <Text style={styles.title}>NEARBY DISCOVERED DEVICES ({peers.length})</Text>
        <Text style={styles.badge}>OFFLINE P2P</Text>
      </View>

      {peers.length === 0 ? (
        <View style={styles.emptyBox}>
          <Text style={styles.emptyText}>Scanning local Bluetooth & Wi-Fi radios...</Text>
          <Text style={styles.subEmptyText}>
            Ensure other devices have the app open within range (approx. 10–30 meters).
          </Text>
        </View>
      ) : (
        <ScrollView style={styles.list}>
          {peers.map((peer) => {
            const status = getSignalStatus(peer.rssi, peer.hopCount);
            return (
              <View key={peer.id} style={styles.peerCard}>
                <TouchableOpacity style={styles.peerInfo} onPress={() => onSelectPeer(peer)}>
                  <Text style={styles.dot}>{status.dot}</Text>
                  <View style={styles.nameBlock}>
                    <Text style={styles.peerName}>{peer.nickname}</Text>
                    <Text style={[styles.signalStatus, { color: status.color }]}>
                      {status.label} • {peer.rssi} dBm
                    </Text>
                  </View>
                </TouchableOpacity>

                <View style={styles.actions}>
                  <TouchableOpacity
                    style={[styles.actionBtn, styles.callBtn]}
                    onPress={() => onCallPeer(peer)}
                    accessibilityLabel={`Call ${peer.nickname}`}>
                    <Text style={styles.btnText}>📞 Call</Text>
                  </TouchableOpacity>

                  <TouchableOpacity
                    style={[styles.actionBtn, styles.msgBtn]}
                    onPress={() => onMessagePeer(peer)}
                    accessibilityLabel={`Message ${peer.nickname}`}>
                    <Text style={styles.btnText}>💬 Chat</Text>
                  </TouchableOpacity>
                </View>
              </View>
            );
          })}
        </ScrollView>
      )}
    </View>
  );
};

const styles = StyleSheet.create({
  container: {
    backgroundColor: '#0F172A',
    borderRadius: 14,
    padding: 16,
    borderWidth: 1,
    borderColor: '#1E293B',
  },
  headerRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    marginBottom: 12,
  },
  title: {
    color: '#94A3B8',
    fontSize: 12,
    fontWeight: '700',
    letterSpacing: 0.5,
  },
  badge: {
    color: '#10B981',
    backgroundColor: '#064E3B',
    fontSize: 10,
    fontWeight: '800',
    paddingHorizontal: 8,
    paddingVertical: 2,
    borderRadius: 4,
  },
  emptyBox: {
    padding: 24,
    alignItems: 'center',
  },
  emptyText: {
    color: '#94A3B8',
    fontSize: 14,
    fontWeight: '600',
    textAlign: 'center',
  },
  subEmptyText: {
    color: '#64748B',
    fontSize: 12,
    textAlign: 'center',
    marginTop: 6,
  },
  list: {
    maxHeight: 280,
  },
  peerCard: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    backgroundColor: '#1E293B',
    padding: 12,
    borderRadius: 10,
    marginBottom: 8,
  },
  peerInfo: {
    flexDirection: 'row',
    alignItems: 'center',
    flex: 1,
  },
  dot: {
    fontSize: 14,
    marginRight: 10,
  },
  nameBlock: {
    flex: 1,
  },
  peerName: {
    color: '#F8FAFC',
    fontSize: 15,
    fontWeight: '700',
  },
  signalStatus: {
    fontSize: 12,
    fontWeight: '500',
    marginTop: 2,
  },
  actions: {
    flexDirection: 'row',
    gap: 8,
  },
  actionBtn: {
    paddingHorizontal: 12,
    paddingVertical: 8,
    borderRadius: 8,
  },
  callBtn: {
    backgroundColor: '#059669',
  },
  msgBtn: {
    backgroundColor: '#2563EB',
  },
  btnText: {
    color: '#FFFFFF',
    fontSize: 12,
    fontWeight: '700',
  },
});
