import React, { useState, useEffect } from 'react';
import { View, Text, StyleSheet, TouchableOpacity, ScrollView, SafeAreaView, ActivityIndicator } from 'react-native';
import { NativeBridge } from '../services/NativeBridge';

interface DiagnosticsScreenProps {
  onBack: () => void;
}

export const DiagnosticsScreen: React.FC<DiagnosticsScreenProps> = ({ onBack }) => {
  const [currentHost, setCurrentHost] = useState(NativeBridge.getServerHost());
  const [isConnected, setIsConnected] = useState(NativeBridge.isConnected);
  const [isScanning, setIsScanning] = useState(false);
  const [peers, setPeers] = useState(NativeBridge.activePeers);

  useEffect(() => {
    const unsubConn = NativeBridge.onConnectionStatusChanged((connected) => {
      setIsConnected(connected);
    });
    const unsubHost = NativeBridge.onHostChanged((host) => {
      setCurrentHost(host);
    });
    const unsubPeers = NativeBridge.onPeerListUpdated((p) => {
      setPeers(p);
    });

    return () => {
      unsubConn();
      unsubHost();
      unsubPeers();
    };
  }, []);

  const handleScan = async () => {
    setIsScanning(true);
    await NativeBridge.autoScanMeshServers();
    setIsScanning(false);
  };

  const setPreset = (host: string) => {
    NativeBridge.setServerHost(host);
  };

  return (
    <SafeAreaView style={styles.container}>
      <View style={styles.header}>
        <TouchableOpacity onPress={onBack}>
          <Text style={styles.backText}>← Back</Text>
        </TouchableOpacity>
        <Text style={styles.title}>ENGINE DIAGNOSTICS</Text>
        <View style={{ width: 40 }} />
      </View>

      <ScrollView contentContainerStyle={styles.scroll}>
        {/* Device & Radio Telemetry */}
        <View style={styles.card}>
          <Text style={styles.cardTitle}>LOCAL DEVICE & RADIO STATE</Text>
          <View style={styles.row}>
            <Text style={styles.label}>Node Identity</Text>
            <Text style={styles.value}>{NativeBridge.myId} ({NativeBridge.myNickname})</Text>
          </View>
          <View style={styles.row}>
            <Text style={styles.label}>Protocol Version</Text>
            <Text style={styles.value}>v1.0 Noise_XX (E2EE)</Text>
          </View>
          <View style={styles.row}>
            <Text style={styles.label}>Active Radio Link</Text>
            <Text style={[styles.value, { color: isConnected ? '#34D399' : '#F59E0B' }]}>
              {isConnected ? `● CONNECTED (${currentHost})` : '○ DISCONNECTED / SEARCHING'}
            </Text>
          </View>
          <View style={styles.row}>
            <Text style={styles.label}>Transports Supported</Text>
            <Text style={styles.value}>BLE L2CAP / Bluetooth PAN / Wi-Fi Direct</Text>
          </View>
          <View style={styles.row}>
            <Text style={styles.label}>Crypto Handshake</Text>
            <Text style={styles.value}>Noise_XX_25519_ChaChaPoly_BLAKE2s</Text>
          </View>
          <View style={styles.row}>
            <Text style={styles.label}>Replay Filter Window</Text>
            <Text style={styles.value}>0xFFFFFFFFFFFFFFFF (0 dropped)</Text>
          </View>
        </View>

        {/* Quick Transport Switcher */}
        <View style={styles.card}>
          <Text style={styles.cardTitle}>CONNECTIVITY PRESETS & AUTO-SCAN</Text>
          <View style={styles.buttonRow}>
            <TouchableOpacity 
              style={[styles.presetBtn, currentHost.includes('172.27.180') && styles.presetBtnActive]} 
              onPress={() => setPreset('172.27.180.170:3000')}
            >
              <Text style={styles.presetBtnText}>🔵 Bluetooth PAN (172.27.180.170)</Text>
            </TouchableOpacity>
            <TouchableOpacity 
              style={[styles.presetBtn, currentHost.includes('10.19.238') && styles.presetBtnActive]} 
              onPress={() => setPreset('10.19.238.166:3000')}
            >
              <Text style={styles.presetBtnText}>📶 Wi-Fi LAN (10.19.238.166)</Text>
            </TouchableOpacity>
            <TouchableOpacity 
              style={[styles.presetBtn, (currentHost.includes('127.0.0.1') || currentHost.includes('localhost')) && styles.presetBtnActive]} 
              onPress={() => setPreset('127.0.0.1:3000')}
            >
              <Text style={styles.presetBtnText}>💻 USB / Localhost (127.0.0.1)</Text>
            </TouchableOpacity>
          </View>

          <TouchableOpacity style={styles.scanActionBtn} onPress={handleScan} disabled={isScanning}>
            {isScanning ? (
              <ActivityIndicator color="#FFFFFF" size="small" />
            ) : (
              <Text style={styles.scanActionText}>🔍 Auto-Discover Active Mesh Server</Text>
            )}
          </TouchableOpacity>
        </View>

        {/* Audio DSP Status */}
        <View style={styles.card}>
          <Text style={styles.cardTitle}>AUDIO DSP PIPELINE</Text>
          <View style={styles.row}>
            <Text style={styles.label}>Acoustic Echo Canceler</Text>
            <Text style={styles.valueGreen}>Hardware Acoustic Echo Canceler ON</Text>
          </View>
          <View style={styles.row}>
            <Text style={styles.label}>Noise Suppression</Text>
            <Text style={styles.valueGreen}>WebRTC Noise Suppression Level 3</Text>
          </View>
        </View>

        {/* Live AODV Routing Table */}
        <View style={styles.card}>
          <Text style={styles.cardTitle}>LIVE PEER ROUTING NODES ({peers.length})</Text>
          {peers.length === 0 ? (
            <Text style={styles.noPeersText}>No remote mesh nodes detected yet.</Text>
          ) : (
            peers.map((peer, idx) => (
              <View key={idx} style={styles.routeItem}>
                <View style={styles.routeTop}>
                  <Text style={styles.destText}>{peer.nickname} ({peer.id})</Text>
                  <Text style={styles.hopText}>{peer.hopCount} hop(s)</Text>
                </View>
                <View style={styles.routeBottom}>
                  <Text style={styles.subText}>Transport: {peer.transport}</Text>
                  <Text style={styles.subText}>RSSI: {peer.rssi} dBm</Text>
                  <Text style={styles.qualityText}>Battery: {peer.batteryPercent}%</Text>
                </View>
              </View>
            ))
          )}
        </View>
      </ScrollView>
    </SafeAreaView>
  );
};

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: '#090D16' },
  header: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingHorizontal: 16,
    paddingVertical: 12,
    borderBottomWidth: 1,
    borderBottomColor: '#1E293B',
    backgroundColor: '#0F172A',
  },
  backText: { color: '#38BDF8', fontSize: 14, fontWeight: '600' },
  title: { color: '#F8FAFC', fontSize: 16, fontWeight: '700' },
  scroll: { padding: 16, gap: 16 },
  card: {
    backgroundColor: '#1E293B',
    borderRadius: 12,
    padding: 16,
    borderWidth: 1,
    borderColor: '#334155',
  },
  cardTitle: {
    color: '#94A3B8',
    fontSize: 11,
    fontWeight: '800',
    letterSpacing: 0.5,
    marginBottom: 12,
  },
  row: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    paddingVertical: 6,
    borderBottomWidth: 1,
    borderBottomColor: '#0F172A',
  },
  label: { color: '#64748B', fontSize: 12 },
  value: { color: '#F1F5F9', fontSize: 12, fontWeight: '600', fontFamily: 'monospace' },
  valueGreen: { color: '#34D399', fontSize: 12, fontWeight: '600' },
  buttonRow: {
    gap: 8,
    marginBottom: 12,
  },
  presetBtn: {
    backgroundColor: '#0F172A',
    padding: 10,
    borderRadius: 8,
    borderWidth: 1,
    borderColor: '#334155',
  },
  presetBtnActive: {
    borderColor: '#38BDF8',
    backgroundColor: 'rgba(56, 189, 248, 0.1)',
  },
  presetBtnText: {
    color: '#F8FAFC',
    fontSize: 12,
    fontWeight: '600',
  },
  scanActionBtn: {
    backgroundColor: '#0284C7',
    padding: 12,
    borderRadius: 8,
    alignItems: 'center',
    justifyContent: 'center',
  },
  scanActionText: {
    color: '#FFFFFF',
    fontWeight: 'bold',
    fontSize: 13,
  },
  noPeersText: {
    color: '#64748B',
    fontSize: 12,
    fontStyle: 'italic',
    paddingVertical: 8,
  },
  routeItem: {
    backgroundColor: '#0F172A',
    padding: 10,
    borderRadius: 8,
    marginBottom: 8,
  },
  routeTop: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    marginBottom: 4,
  },
  destText: { color: '#38BDF8', fontSize: 13, fontWeight: '700', fontFamily: 'monospace' },
  hopText: { color: '#A855F7', fontSize: 12, fontWeight: '700' },
  routeBottom: {
    flexDirection: 'row',
    justifyContent: 'space-between',
  },
  subText: { color: '#94A3B8', fontSize: 11 },
  qualityText: { color: '#10B981', fontSize: 11, fontWeight: '700' },
});
