import React, { useState } from 'react';
import {
  View,
  Text,
  StyleSheet,
  TouchableOpacity,
  ScrollView,
  FlatList,
} from 'react-native';

interface FileShareScreenProps {
  onBack: () => void;
}

interface TransferItem {
  id: string;
  name: string;
  size: string;
  type: 'IMAGE' | 'VOICE_NOTE' | 'MAP_VECTOR' | 'DOCUMENT';
  sender: string;
  progress: number; // 0 to 100
  speed: string;
  status: 'TRANSFERRING' | 'COMPLETED' | 'PAUSED' | 'VERIFYING';
  crc32: string;
  hops: number;
}

const INITIAL_TRANSFERS: TransferItem[] = [
  {
    id: 'f-1',
    name: 'topographic_sector_9.mbtiles',
    size: '4.2 MB',
    type: 'MAP_VECTOR',
    sender: 'Node-Bravo (Relay)',
    progress: 100,
    speed: '68 kB/s',
    status: 'COMPLETED',
    crc32: '9F4C2E81',
    hops: 2,
  },
  {
    id: 'f-2',
    name: 'emergency_recon_photo.jpg',
    size: '1.8 MB',
    type: 'IMAGE',
    sender: 'Node-Echo (Direct)',
    progress: 74,
    speed: '82 kB/s',
    status: 'TRANSFERRING',
    crc32: '5A11D30C',
    hops: 1,
  },
  {
    id: 'f-3',
    name: 'voice_sitrep_1800.opus',
    size: '340 KB',
    type: 'VOICE_NOTE',
    sender: 'Node-Delta (Multi-Hop)',
    progress: 100,
    speed: '45 kB/s',
    status: 'COMPLETED',
    crc32: 'AC982104',
    hops: 3,
  },
];

export const FileShareScreen: React.FC<FileShareScreenProps> = ({ onBack }) => {
  const [transfers, setTransfers] = useState<TransferItem[]>(INITIAL_TRANSFERS);

  const simulateNewSend = (type: 'IMAGE' | 'MAP_VECTOR' | 'VOICE_NOTE') => {
    const newId = 'f-' + (transfers.length + 1);
    const newItem: TransferItem = {
      id: newId,
      name: type === 'IMAGE' ? `camera_snap_${Date.now().toString().slice(-4)}.jpg` : `rally_point_vector_${Date.now().toString().slice(-4)}.geojson`,
      size: type === 'IMAGE' ? '1.2 MB' : '450 KB',
      type: type,
      sender: 'YOU (Broadcasting to Mesh)',
      progress: 15,
      speed: '75 kB/s',
      status: 'TRANSFERRING',
      crc32: 'E3B0C442',
      hops: 1,
    };
    setTransfers([newItem, ...transfers]);

    // Simulate transfer completion
    let p = 15;
    const interval = setInterval(() => {
      p += 25;
      if (p >= 100) {
        clearInterval(interval);
        setTransfers((prev) =>
          prev.map((it) => (it.id === newId ? { ...it, progress: 100, status: 'COMPLETED' } : it))
        );
      } else {
        setTransfers((prev) =>
          prev.map((it) => (it.id === newId ? { ...it, progress: p } : it))
        );
      }
    }, 800);
  };

  return (
    <View style={styles.container}>
      {/* Header */}
      <View style={styles.header}>
        <TouchableOpacity style={styles.backButton} onPress={onBack}>
          <Text style={styles.backButtonText}>← Back</Text>
        </TouchableOpacity>
        <Text style={styles.headerTitle}>P2P Mesh File Transfer</Text>
        <View style={styles.headerBadge}>
          <Text style={styles.headerBadgeText}>BLE L2CAP</Text>
        </View>
      </View>

      {/* Quick Action Toolbar */}
      <View style={styles.actionCard}>
        <Text style={styles.actionCardTitle}>DISPATCH OFFLINE MEDIA</Text>
        <Text style={styles.actionCardSub}>Transfer images, vector maps, and voice logs over local mesh</Text>

        <View style={styles.buttonRow}>
          <TouchableOpacity style={styles.sendButton} onPress={() => simulateNewSend('IMAGE')}>
            <Text style={styles.sendButtonIcon}>📷</Text>
            <Text style={styles.sendButtonText}>Send Photo</Text>
          </TouchableOpacity>

          <TouchableOpacity style={styles.sendButton} onPress={() => simulateNewSend('MAP_VECTOR')}>
            <Text style={styles.sendButtonIcon}>🗺️</Text>
            <Text style={styles.sendButtonText}>Send Map</Text>
          </TouchableOpacity>

          <TouchableOpacity style={styles.sendButton} onPress={() => simulateNewSend('VOICE_NOTE')}>
            <Text style={styles.sendButtonIcon}>🎙️</Text>
            <Text style={styles.sendButtonText}>Voice Log</Text>
          </TouchableOpacity>
        </View>
      </View>

      {/* Transfer List Header */}
      <View style={styles.sectionHeader}>
        <Text style={styles.sectionTitle}>ACTIVE &amp; RECEIVED TRANSFERS</Text>
        <Text style={styles.sectionCount}>{transfers.length} Files</Text>
      </View>

      {/* Transfers List */}
      <FlatList
        data={transfers}
        keyExtractor={(item) => item.id}
        contentContainerStyle={styles.listContent}
        renderItem={({ item }) => (
          <View style={styles.transferCard}>
            <View style={styles.transferHeaderRow}>
              <Text style={styles.fileIcon}>
                {item.type === 'IMAGE' ? '🖼️' : item.type === 'MAP_VECTOR' ? '🗺️' : '🎙️'}
              </Text>
              <View style={styles.fileInfo}>
                <Text style={styles.fileName}>{item.name}</Text>
                <Text style={styles.fileMeta}>
                  {item.size} • From: {item.sender} • {item.hops} {item.hops === 1 ? 'hop' : 'hops'}
                </Text>
              </View>
              <View
                style={[
                  styles.badge,
                  item.status === 'COMPLETED' ? styles.badgeComplete : styles.badgeTransferring,
                ]}
              >
                <Text style={styles.badgeText}>{item.status}</Text>
              </View>
            </View>

            {/* Progress Bar */}
            <View style={styles.progressTrack}>
              <View
                style={[
                  styles.progressFill,
                  { width: `${item.progress}%` },
                  item.status === 'COMPLETED' ? styles.progressFillComplete : styles.progressFillActive,
                ]}
              />
            </View>

            <View style={styles.transferFooter}>
              <Text style={styles.footerText}>CRC32: {item.crc32} • Verified</Text>
              <Text style={styles.footerSpeed}>
                {item.status === 'COMPLETED' ? '100% Reassembled' : `${item.progress}% @ ${item.speed}`}
              </Text>
            </View>
          </View>
        )}
      />
    </View>
  );
};

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: '#090D16', paddingHorizontal: 16, paddingTop: 48 },
  header: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', marginBottom: 16 },
  backButton: { paddingVertical: 8, paddingHorizontal: 12, backgroundColor: '#1E293B', borderRadius: 8 },
  backButtonText: { color: '#94A3B8', fontSize: 13, fontWeight: '600' },
  headerTitle: { color: '#F8FAFC', fontSize: 17, fontWeight: 'bold' },
  headerBadge: { paddingVertical: 4, paddingHorizontal: 8, backgroundColor: '#083344', borderRadius: 6, borderWidth: 1, borderColor: '#06B6D4' },
  headerBadgeText: { color: '#06B6D4', fontSize: 10, fontWeight: 'bold' },
  actionCard: { backgroundColor: '#131D2E', borderRadius: 16, padding: 16, marginBottom: 16, borderWidth: 1, borderColor: '#1E293B' },
  actionCardTitle: { color: '#64748B', fontSize: 10, fontWeight: 'bold', letterSpacing: 1 },
  actionCardSub: { color: '#94A3B8', fontSize: 12, marginTop: 2, marginBottom: 12 },
  buttonRow: { flexDirection: 'row', gap: 10 },
  sendButton: { flex: 1, backgroundColor: '#1E293B', borderRadius: 12, padding: 12, alignItems: 'center', borderWidth: 1, borderColor: '#334155' },
  sendButtonIcon: { fontSize: 20, marginBottom: 4 },
  sendButtonText: { color: '#F8FAFC', fontSize: 12, fontWeight: 'bold' },
  sectionHeader: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', marginBottom: 12 },
  sectionTitle: { color: '#64748B', fontSize: 11, fontWeight: 'bold', letterSpacing: 1 },
  sectionCount: { color: '#94A3B8', fontSize: 12 },
  listContent: { paddingBottom: 24 },
  transferCard: { backgroundColor: '#131D2E', borderRadius: 14, padding: 14, marginBottom: 12, borderWidth: 1, borderColor: '#1E293B' },
  transferHeaderRow: { flexDirection: 'row', alignItems: 'center' },
  fileIcon: { fontSize: 24, marginRight: 10 },
  fileInfo: { flex: 1 },
  fileName: { color: '#F8FAFC', fontSize: 13, fontWeight: 'bold' },
  fileMeta: { color: '#64748B', fontSize: 11, marginTop: 2 },
  badge: { paddingHorizontal: 8, paddingVertical: 4, borderRadius: 6 },
  badgeComplete: { backgroundColor: '#064E3B' },
  badgeTransferring: { backgroundColor: '#083344' },
  badgeText: { color: '#F8FAFC', fontSize: 10, fontWeight: 'bold' },
  progressTrack: { height: 6, backgroundColor: '#1E293B', borderRadius: 3, marginTop: 12, overflow: 'hidden' },
  progressFill: { height: '100%', borderRadius: 3 },
  progressFillComplete: { backgroundColor: '#10B981' },
  progressFillActive: { backgroundColor: '#06B6D4' },
  transferFooter: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', marginTop: 8 },
  footerText: { color: '#64748B', fontSize: 10, fontFamily: 'monospace' },
  footerSpeed: { color: '#06B6D4', fontSize: 11, fontWeight: '600' },
});
