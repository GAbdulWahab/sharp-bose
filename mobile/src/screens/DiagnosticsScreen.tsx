import React, { useState } from 'react';
import { View, Text, StyleSheet, TouchableOpacity, ScrollView, SafeAreaView } from 'react-native';

interface DiagnosticsScreenProps {
  onBack: () => void;
}

export const DiagnosticsScreen: React.FC<DiagnosticsScreenProps> = ({ onBack }) => {
  const [radioStats, setRadioStats] = useState({
    deviceId: '0x4D457F4A21B9',
    protocolVersion: 'v1.0 (0x4D45)',
    activeTransport: 'BLE L2CAP CoC (PSM 0x1001)',
    phyRate: '2 Mbps (LE 2M PHY)',
    txPower: '+4 dBm',
    batteryLevel: '86%',
    noiseProtocol: 'Noise_XX_25519_ChaChaPoly_BLAKE2s',
    slidingWindowBitmask: '0xFFFFFFFFFFFFFFFF (0 dropped)',
    audioAEC: 'Hardware Acoustic Echo Canceler ON',
    audioNS: 'WebRTC Noise Suppression Level 3',
  });

  const [routingTable, setRoutingTable] = useState([
    { dest: '0x7F4A21B9', nextHop: 'Direct', hops: 1, rssi: -58, quality: '98%', seq: 1042 },
    { dest: '0x99C2E8A1', nextHop: 'Direct', hops: 1, rssi: -72, quality: '82%', seq: 894 },
    { dest: '0x1B44DD20', nextHop: '0x7F4A21B9', hops: 2, rssi: -84, quality: '64%', seq: 412 },
  ]);

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
            <Text style={styles.value}>{radioStats.deviceId}</Text>
          </View>
          <View style={styles.row}>
            <Text style={styles.label}>Protocol Version</Text>
            <Text style={styles.value}>{radioStats.protocolVersion}</Text>
          </View>
          <View style={styles.row}>
            <Text style={styles.label}>Active Radio Transport</Text>
            <Text style={styles.value}>{radioStats.activeTransport}</Text>
          </View>
          <View style={styles.row}>
            <Text style={styles.label}>PHY Layer / TX Power</Text>
            <Text style={styles.value}>{radioStats.phyRate} / {radioStats.txPower}</Text>
          </View>
          <View style={styles.row}>
            <Text style={styles.label}>Crypto Handshake</Text>
            <Text style={styles.value}>{radioStats.noiseProtocol}</Text>
          </View>
          <View style={styles.row}>
            <Text style={styles.label}>Replay Filter Window</Text>
            <Text style={styles.value}>{radioStats.slidingWindowBitmask}</Text>
          </View>
        </View>

        {/* Audio DSP Status */}
        <View style={styles.card}>
          <Text style={styles.cardTitle}>AUDIO DSP PIPELINE</Text>
          <View style={styles.row}>
            <Text style={styles.label}>Acoustic Echo Canceler</Text>
            <Text style={styles.valueGreen}>{radioStats.audioAEC}</Text>
          </View>
          <View style={styles.row}>
            <Text style={styles.label}>Noise Suppression</Text>
            <Text style={styles.valueGreen}>{radioStats.audioNS}</Text>
          </View>
        </View>

        {/* Live AODV Routing Table */}
        <View style={styles.card}>
          <Text style={styles.cardTitle}>AODV-LITE MESH ROUTING TABLE</Text>
          {routingTable.map((route, idx) => (
            <View key={idx} style={styles.routeItem}>
              <View style={styles.routeTop}>
                <Text style={styles.destText}>Dest: {route.dest}</Text>
                <Text style={styles.hopText}>{route.hops} hop(s)</Text>
              </View>
              <View style={styles.routeBottom}>
                <Text style={styles.subText}>Next: {route.nextHop}</Text>
                <Text style={styles.subText}>RSSI: {route.rssi} dBm</Text>
                <Text style={styles.qualityText}>Quality: {route.quality}</Text>
              </View>
            </View>
          ))}
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
