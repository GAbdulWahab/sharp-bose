import React, { useState } from 'react';
import {
  View,
  Text,
  StyleSheet,
  TouchableOpacity,
  ScrollView,
} from 'react-native';

interface TacticalMapScreenProps {
  onBack: () => void;
  onCallNode: (nodeName: string, nodeId: string) => void;
}

interface MeshNodeLocation {
  id: string;
  name: string;
  distanceMeters: number;
  bearingDeg: number;
  rssi: number;
  hops: number;
  battery: number;
  role: 'COORDINATOR' | 'RELAY' | 'END_NODE';
  status: 'ACTIVE' | 'RELAYING' | 'DISTRESS';
}

const NODES: MeshNodeLocation[] = [
  { id: 'n-1', name: 'Node-Bravo (Base Camp)', distanceMeters: 45, bearingDeg: 35, rssi: -58, hops: 1, battery: 94, role: 'RELAY', status: 'ACTIVE' },
  { id: 'n-2', name: 'Node-Echo (Field Recon)', distanceMeters: 120, bearingDeg: 140, rssi: -78, hops: 2, battery: 68, role: 'END_NODE', status: 'ACTIVE' },
  { id: 'n-3', name: 'Node-Delta (Outpost 3)', distanceMeters: 280, bearingDeg: 260, rssi: -88, hops: 3, battery: 42, role: 'RELAY', status: 'ACTIVE' },
];

export const TacticalMapScreen: React.FC<TacticalMapScreenProps> = ({ onBack, onCallNode }) => {
  const [selectedNode, setSelectedNode] = useState<MeshNodeLocation | null>(NODES[0]);
  const [droppedPins, setDroppedPins] = useState<string[]>(['Rally Point Alpha (40m NE)']);

  const addRallyPin = () => {
    const pinName = `Safe Zone ${String.fromCharCode(65 + droppedPins.length)} (Broadcasted)`;
    setDroppedPins([...droppedPins, pinName]);
  };

  return (
    <View style={styles.container}>
      {/* Header */}
      <View style={styles.header}>
        <TouchableOpacity style={styles.backButton} onPress={onBack}>
          <Text style={styles.backButtonText}>← Back</Text>
        </TouchableOpacity>
        <Text style={styles.headerTitle}>Tactical Mesh Radar</Text>
        <TouchableOpacity style={styles.pinButton} onPress={addRallyPin}>
          <Text style={styles.pinButtonText}>+ Drop Rally</Text>
        </TouchableOpacity>
      </View>

      {/* Radar Coordinate Compass View */}
      <View style={styles.radarCard}>
        <View style={styles.radarCircleOuter}>
          <View style={styles.radarCircleMid}>
            <View style={styles.radarCircleInner}>
              <View style={styles.radarCenterDot} />
            </View>
          </View>

          {/* Compass labels */}
          <Text style={[styles.compassText, { top: 8, alignSelf: 'center' }]}>000° N</Text>
          <Text style={[styles.compassText, { right: 8, top: '46%' }]}>090° E</Text>
          <Text style={[styles.compassText, { bottom: 8, alignSelf: 'center' }]}>180° S</Text>
          <Text style={[styles.compassText, { left: 8, top: '46%' }]}>270° W</Text>

          {/* Render Mesh Nodes as Radar Blips */}
          {NODES.map((node) => {
            // Convert bearing and normalized distance to radar X/Y
            const rad = (node.bearingDeg - 90) * (Math.PI / 180);
            const normDist = Math.min(110, (node.distanceMeters / 300) * 110);
            const blipX = 130 + normDist * Math.cos(rad) - 8;
            const blipY = 130 + normDist * Math.sin(rad) - 8;

            const isSelected = selectedNode?.id === node.id;

            return (
              <TouchableOpacity
                key={node.id}
                onPress={() => setSelectedNode(node)}
                style={[
                  styles.radarBlip,
                  { left: blipX, top: blipY },
                  isSelected && styles.radarBlipSelected,
                ]}
              >
                <View style={styles.radarBlipDot} />
              </TouchableOpacity>
            );
          })}
        </View>

        <View style={styles.radarLegendRow}>
          <Text style={styles.legendText}>🎯 Inner: &lt;50m</Text>
          <Text style={styles.legendText}>Mid: &lt;150m</Text>
          <Text style={styles.legendText}>Outer: &lt;300m</Text>
        </View>
      </View>

      {/* Selected Node Details Card */}
      {selectedNode && (
        <View style={styles.detailCard}>
          <View style={styles.detailHeader}>
            <View>
              <Text style={styles.nodeTitle}>{selectedNode.name}</Text>
              <Text style={styles.nodeSub}>
                Est. Distance: ~{selectedNode.distanceMeters}m • Bearing: {selectedNode.bearingDeg}°
              </Text>
            </View>
            <TouchableOpacity
              style={styles.callActionButton}
              onPress={() => onCallNode(selectedNode.name, selectedNode.id)}
            >
              <Text style={styles.callActionText}>📞 Voice</Text>
            </TouchableOpacity>
          </View>

          <View style={styles.metricGrid}>
            <View style={styles.metricItem}>
              <Text style={styles.metricLabel}>SIGNAL RSSI</Text>
              <Text style={styles.metricValue}>{selectedNode.rssi} dBm</Text>
            </View>
            <View style={styles.metricItem}>
              <Text style={styles.metricLabel}>MESH HOPS</Text>
              <Text style={styles.metricValue}>{selectedNode.hops} {selectedNode.hops === 1 ? 'Hop (Direct)' : 'Hops'}</Text>
            </View>
            <View style={styles.metricItem}>
              <Text style={styles.metricLabel}>BATTERY</Text>
              <Text style={styles.metricValue}>{selectedNode.battery}%</Text>
            </View>
          </View>
        </View>
      )}

      {/* Dropped Offline Rally Points */}
      <View style={styles.rallyCard}>
        <Text style={styles.rallyHeader}>BROADCASTED RALLY POINTS &amp; SAFE ZONES</Text>
        <ScrollView style={styles.rallyList}>
          {droppedPins.map((pin, idx) => (
            <View key={idx} style={styles.rallyItem}>
              <Text style={styles.rallyIcon}>📍</Text>
              <Text style={styles.rallyText}>{pin}</Text>
            </View>
          ))}
        </ScrollView>
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
  pinButton: { paddingVertical: 6, paddingHorizontal: 12, backgroundColor: '#083344', borderRadius: 8, borderWidth: 1, borderColor: '#06B6D4' },
  pinButtonText: { color: '#06B6D4', fontSize: 12, fontWeight: 'bold' },
  radarCard: { backgroundColor: '#131D2E', borderRadius: 20, padding: 16, alignItems: 'center', borderWidth: 1, borderColor: '#1E293B', marginBottom: 16 },
  radarCircleOuter: {
    width: 260,
    height: 260,
    borderRadius: 130,
    borderWidth: 1.5,
    borderColor: '#06B6D4',
    justifyContent: 'center',
    alignItems: 'center',
    position: 'relative',
    backgroundColor: '#0B1322',
  },
  radarCircleMid: { width: 170, height: 170, borderRadius: 85, borderWidth: 1, borderColor: '#1E293B', justifyContent: 'center', alignItems: 'center' },
  radarCircleInner: { width: 90, height: 90, borderRadius: 45, borderWidth: 1, borderColor: '#1E293B', justifyContent: 'center', alignItems: 'center' },
  radarCenterDot: { width: 8, height: 8, borderRadius: 4, backgroundColor: '#10B981' },
  compassText: { position: 'absolute', color: '#64748B', fontSize: 10, fontFamily: 'monospace', fontWeight: 'bold' },
  radarBlip: { position: 'absolute', width: 20, height: 20, borderRadius: 10, justifyContent: 'center', alignItems: 'center', zIndex: 10 },
  radarBlipSelected: { backgroundColor: 'rgba(6, 182, 212, 0.3)', borderWidth: 1, borderColor: '#06B6D4' },
  radarBlipDot: { width: 10, height: 10, borderRadius: 5, backgroundColor: '#06B6D4' },
  radarLegendRow: { flexDirection: 'row', justifyContent: 'space-between', width: '100%', marginTop: 12, paddingHorizontal: 10 },
  legendText: { color: '#64748B', fontSize: 10, fontFamily: 'monospace' },
  detailCard: { backgroundColor: '#131D2E', borderRadius: 16, padding: 14, marginBottom: 12, borderWidth: 1, borderColor: '#1E293B' },
  detailHeader: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center' },
  nodeTitle: { color: '#F8FAFC', fontSize: 14, fontWeight: 'bold' },
  nodeSub: { color: '#94A3B8', fontSize: 11, marginTop: 2 },
  callActionButton: { paddingHorizontal: 14, paddingVertical: 8, backgroundColor: '#10B981', borderRadius: 8 },
  callActionText: { color: '#FFFFFF', fontSize: 12, fontWeight: 'bold' },
  metricGrid: { flexDirection: 'row', justifyContent: 'space-between', marginTop: 12, paddingTop: 10, borderTopWidth: 1, borderTopColor: '#1E293B' },
  metricItem: { flex: 1, alignItems: 'center' },
  metricLabel: { color: '#64748B', fontSize: 9, fontWeight: 'bold' },
  metricValue: { color: '#06B6D4', fontSize: 12, fontWeight: 'bold', marginTop: 2 },
  rallyCard: { backgroundColor: '#131D2E', borderRadius: 16, padding: 14, borderWidth: 1, borderColor: '#1E293B', maxHeight: 110 },
  rallyHeader: { color: '#64748B', fontSize: 10, fontWeight: 'bold', letterSpacing: 1, marginBottom: 8 },
  rallyList: { maxHeight: 70 },
  rallyItem: { flexDirection: 'row', alignItems: 'center', marginBottom: 6 },
  rallyIcon: { fontSize: 12, marginRight: 6 },
  rallyText: { color: '#CBD5E1', fontSize: 12 },
});
