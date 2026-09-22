import React, { useState, useEffect } from 'react';
import {
  View,
  Text,
  StyleSheet,
  Modal,
  TouchableOpacity,
  TextInput,
  ActivityIndicator,
} from 'react-native';
import { ThemeColors } from '../types/theme';
import { NativeBridge, CANDIDATE_MESH_HOSTS } from '../services/NativeBridge';

interface ConnectionSettingsModalProps {
  visible: boolean;
  onClose: () => void;
  theme: ThemeColors;
}

export const ConnectionSettingsModal: React.FC<ConnectionSettingsModalProps> = ({
  visible,
  onClose,
  theme,
}) => {
  const [currentHost, setCurrentHost] = useState(NativeBridge.getServerHost());
  const [customIp, setCustomIp] = useState(NativeBridge.getServerHost());
  const [isConnected, setIsConnected] = useState(NativeBridge.isConnected);
  const [isScanning, setIsScanning] = useState(false);

  useEffect(() => {
    const unsubConn = NativeBridge.onConnectionStatusChanged((connected) => {
      setIsConnected(connected);
    });
    const unsubHost = NativeBridge.onHostChanged((host) => {
      setCurrentHost(host);
      setCustomIp(host);
    });

    return () => {
      unsubConn();
      unsubHost();
    };
  }, []);

  const handleSelectPreset = (presetHost: string) => {
    setCustomIp(presetHost);
    NativeBridge.setServerHost(presetHost);
  };

  const handleCustomConnect = () => {
    if (customIp.trim()) {
      NativeBridge.setServerHost(customIp.trim());
    }
  };

  const handleAutoScan = async () => {
    setIsScanning(true);
    await NativeBridge.autoScanMeshServers();
    setIsScanning(false);
  };

  return (
    <Modal
      visible={visible}
      transparent
      animationType="fade"
      onRequestClose={onClose}
    >
      <View style={styles.overlay}>
        <View style={[styles.card, { backgroundColor: theme.card, borderColor: theme.cardBorder }]}>
          {/* Header */}
          <View style={styles.header}>
            <View>
              <Text style={[styles.title, { color: theme.textPrimary }]}>
                Mesh Connection Settings
              </Text>
              <Text style={[styles.subtitle, { color: theme.textSecondary }]}>
                Bluetooth PAN, Wi-Fi & Offline P2P Link
              </Text>
            </View>
            <TouchableOpacity onPress={onClose} style={styles.closeBtn}>
              <Text style={[styles.closeText, { color: theme.textMuted }]}>✕</Text>
            </TouchableOpacity>
          </View>

          {/* Current Status Badge */}
          <View style={[styles.statusBox, { backgroundColor: theme.bgSecondary }]}>
            <View style={[styles.statusDot, { backgroundColor: isConnected ? theme.accentEmerald : '#F59E0B' }]} />
            <View style={styles.statusTextContainer}>
              <Text style={[styles.statusLabel, { color: theme.textPrimary }]}>
                {isConnected ? 'Connected to Mesh Server' : 'Disconnected / Searching'}
              </Text>
              <Text style={[styles.activeHostText, { color: theme.accentCyan }]}>
                Active Host: {currentHost}
              </Text>
            </View>
          </View>

          {/* Preset Buttons */}
          <Text style={[styles.sectionTitle, { color: theme.textSecondary }]}>
            QUICK CONNECTION PRESETS
          </Text>

          <View style={styles.presetContainer}>
            <TouchableOpacity
              style={[
                styles.presetButton,
                currentHost === '172.27.180.170:3000' && styles.presetButtonActive,
                { borderColor: theme.accentCyan }
              ]}
              onPress={() => handleSelectPreset('172.27.180.170:3000')}
            >
              <Text style={styles.presetIcon}>🔵</Text>
              <View style={styles.presetInfo}>
                <Text style={[styles.presetTitle, { color: theme.textPrimary }]}>Laptop Bluetooth PAN</Text>
                <Text style={[styles.presetSubtitle, { color: theme.textMuted }]}>172.27.180.170:3000</Text>
              </View>
            </TouchableOpacity>

            <TouchableOpacity
              style={[
                styles.presetButton,
                currentHost === '10.19.238.166:3000' && styles.presetButtonActive,
                { borderColor: theme.accentEmerald }
              ]}
              onPress={() => handleSelectPreset('10.19.238.166:3000')}
            >
              <Text style={styles.presetIcon}>📶</Text>
              <View style={styles.presetInfo}>
                <Text style={[styles.presetTitle, { color: theme.textPrimary }]}>Laptop Wi-Fi LAN</Text>
                <Text style={[styles.presetSubtitle, { color: theme.textMuted }]}>10.19.238.166:3000</Text>
              </View>
            </TouchableOpacity>

            <TouchableOpacity
              style={[
                styles.presetButton,
                (currentHost === '127.0.0.1:3000' || currentHost === 'localhost:3000') && styles.presetButtonActive,
                { borderColor: theme.accentPurple }
              ]}
              onPress={() => handleSelectPreset('127.0.0.1:3000')}
            >
              <Text style={styles.presetIcon}>💻</Text>
              <View style={styles.presetInfo}>
                <Text style={[styles.presetTitle, { color: theme.textPrimary }]}>Localhost / USB Reverse</Text>
                <Text style={[styles.presetSubtitle, { color: theme.textMuted }]}>127.0.0.1:3000</Text>
              </View>
            </TouchableOpacity>
          </View>

          {/* Custom IP Input */}
          <Text style={[styles.sectionTitle, { color: theme.textSecondary }]}>
            CUSTOM LAPTOP / PEER IP ADDRESS
          </Text>
          <View style={styles.inputRow}>
            <TextInput
              style={[
                styles.textInput,
                {
                  backgroundColor: theme.bgSecondary,
                  borderColor: theme.cardBorder,
                  color: theme.textPrimary
                }
              ]}
              placeholder="e.g. 172.27.180.170:3000"
              placeholderTextColor={theme.textMuted}
              value={customIp}
              onChangeText={setCustomIp}
              autoCapitalize="none"
              autoCorrect={false}
            />
            <TouchableOpacity
              style={[styles.connectBtn, { backgroundColor: theme.accentCyan }]}
              onPress={handleCustomConnect}
            >
              <Text style={styles.connectBtnText}>Connect</Text>
            </TouchableOpacity>
          </View>

          {/* Auto-Scan Button */}
          <TouchableOpacity
            style={[styles.scanBtn, { backgroundColor: theme.accentBlue }]}
            onPress={handleAutoScan}
            disabled={isScanning}
          >
            {isScanning ? (
              <ActivityIndicator color="#FFFFFF" size="small" />
            ) : (
              <Text style={styles.scanBtnText}>🔍 Auto-Scan All Networks</Text>
            )}
          </TouchableOpacity>
        </View>
      </View>
    </Modal>
  );
};

const styles = StyleSheet.create({
  overlay: {
    flex: 1,
    backgroundColor: 'rgba(0,0,0,0.7)',
    justifyContent: 'center',
    alignItems: 'center',
    padding: 16,
  },
  card: {
    width: '100%',
    maxWidth: 420,
    borderRadius: 16,
    borderWidth: 1,
    padding: 20,
  },
  header: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'flex-start',
    marginBottom: 16,
  },
  title: {
    fontSize: 18,
    fontWeight: 'bold',
  },
  subtitle: {
    fontSize: 12,
    marginTop: 2,
  },
  closeBtn: {
    padding: 4,
  },
  closeText: {
    fontSize: 20,
    fontWeight: 'bold',
  },
  statusBox: {
    flexDirection: 'row',
    alignItems: 'center',
    borderRadius: 10,
    padding: 12,
    marginBottom: 16,
  },
  statusDot: {
    width: 12,
    height: 12,
    borderRadius: 6,
    marginRight: 10,
  },
  statusTextContainer: {
    flex: 1,
  },
  statusLabel: {
    fontSize: 13,
    fontWeight: 'bold',
  },
  activeHostText: {
    fontSize: 11,
    marginTop: 2,
    fontWeight: '600',
  },
  sectionTitle: {
    fontSize: 10,
    fontWeight: '800',
    letterSpacing: 0.5,
    marginBottom: 8,
  },
  presetContainer: {
    gap: 8,
    marginBottom: 16,
  },
  presetButton: {
    flexDirection: 'row',
    alignItems: 'center',
    padding: 10,
    borderRadius: 10,
    borderWidth: 1,
    backgroundColor: 'rgba(255,255,255,0.03)',
  },
  presetButtonActive: {
    backgroundColor: 'rgba(56, 189, 248, 0.15)',
  },
  presetIcon: {
    fontSize: 18,
    marginRight: 10,
  },
  presetInfo: {
    flex: 1,
  },
  presetTitle: {
    fontSize: 12,
    fontWeight: 'bold',
  },
  presetSubtitle: {
    fontSize: 10,
    marginTop: 1,
  },
  inputRow: {
    flexDirection: 'row',
    gap: 8,
    marginBottom: 16,
  },
  textInput: {
    flex: 1,
    height: 40,
    borderRadius: 8,
    borderWidth: 1,
    paddingHorizontal: 12,
    fontSize: 13,
  },
  connectBtn: {
    paddingHorizontal: 16,
    justifyContent: 'center',
    alignItems: 'center',
    borderRadius: 8,
  },
  connectBtnText: {
    color: '#0B0F19',
    fontWeight: 'bold',
    fontSize: 12,
  },
  scanBtn: {
    height: 42,
    borderRadius: 10,
    justifyContent: 'center',
    alignItems: 'center',
  },
  scanBtnText: {
    color: '#FFFFFF',
    fontWeight: 'bold',
    fontSize: 13,
  },
});
