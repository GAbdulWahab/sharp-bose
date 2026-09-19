import React, { useState } from 'react';
import { View, Text, StyleSheet, TouchableOpacity, Switch, SafeAreaView, Alert } from 'react-native';

interface SOSBroadcastScreenProps {
  onBack: () => void;
}

export const SOSBroadcastScreen: React.FC<SOSBroadcastScreenProps> = ({ onBack }) => {
  const [includeLocation, setIncludeLocation] = useState(false);
  const [isBroadcasting, setIsBroadcasting] = useState(false);
  const [selectedEmergency, setSelectedEmergency] = useState('MEDICAL EMERGENCY');

  const emergencyTypes = [
    'MEDICAL EMERGENCY',
    'SEARCH & RESCUE',
    'NATURAL DISASTER',
    'GENERAL DISTRESS',
  ];

  const handleBroadcast = () => {
    setIsBroadcasting(true);
    Alert.alert(
      '🚨 SOS BROADCAST ACTIVE',
      `Relaying "${selectedEmergency}" to all participating mesh nodes within radio range.`,
      [{ text: 'OK' }]
    );
  };

  const handleStopBroadcast = () => {
    setIsBroadcasting(false);
  };

  return (
    <SafeAreaView style={styles.container}>
      <View style={styles.header}>
        <TouchableOpacity onPress={onBack}>
          <Text style={styles.backText}>← Back</Text>
        </TouchableOpacity>
        <Text style={styles.title}>EMERGENCY SOS MODE</Text>
        <View style={{ width: 40 }} />
      </View>

      <View style={styles.content}>
        {/* Critical Disclaimer Banner */}
        <View style={styles.disclaimerBanner}>
          <Text style={styles.disclaimerTitle}>⚠️ IMPORTANT LIMITATION</Text>
          <Text style={styles.disclaimerBody}>
            This alert is transmitted directly over local Bluetooth & Wi-Fi mesh networks. It relies on nearby devices with this app installed. It does NOT dial central emergency services (911/112) without cellular service.
          </Text>
        </View>

        {/* Emergency Type Selection */}
        <Text style={styles.sectionHeader}>SELECT DISTRESS TYPE</Text>
        <View style={styles.typeGrid}>
          {emergencyTypes.map((type) => (
            <TouchableOpacity
              key={type}
              style={[styles.typeBtn, selectedEmergency === type && styles.typeBtnActive]}
              onPress={() => setSelectedEmergency(type)}>
              <Text style={[styles.typeText, selectedEmergency === type && styles.typeTextActive]}>
                {type}
              </Text>
            </TouchableOpacity>
          ))}
        </View>

        {/* Location Opt-in Toggle */}
        <View style={styles.toggleCard}>
          <View style={styles.toggleInfo}>
            <Text style={styles.toggleTitle}>Include GPS Coordinates</Text>
            <Text style={styles.toggleSub}>
              {includeLocation
                ? 'Approx: Lat 37.7749, Lon -122.4194 (Encrypted in flood packet)'
                : 'Location sharing is strictly OFF'}
            </Text>
          </View>
          <Switch
            value={includeLocation}
            onValueChange={setIncludeLocation}
            trackColor={{ false: '#334155', true: '#DC2626' }}
            thumbColor="#FFFFFF"
          />
        </View>

        {/* Broadcast Trigger Button */}
        <View style={styles.broadcastSection}>
          {isBroadcasting ? (
            <TouchableOpacity style={[styles.bigBtn, styles.stopBtn]} onPress={handleStopBroadcast}>
              <Text style={styles.bigBtnEmoji}>🛑</Text>
              <Text style={styles.bigBtnText}>STOP BROADCASTING</Text>
              <Text style={styles.broadcastingPulse}>● Transmitting Priority Flood Packet...</Text>
            </TouchableOpacity>
          ) : (
            <TouchableOpacity style={[styles.bigBtn, styles.startBtn]} onPress={handleBroadcast}>
              <Text style={styles.bigBtnEmoji}>🚨</Text>
              <Text style={styles.bigBtnText}>BROADCAST SOS ALERT</Text>
              <Text style={styles.bigBtnSub}>Tap to flood emergency message to nearby mesh peers</Text>
            </TouchableOpacity>
          )}
        </View>
      </View>
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
  title: { color: '#EF4444', fontSize: 16, fontWeight: '800' },
  content: { padding: 16, flex: 1, justifyContent: 'space-between' },
  disclaimerBanner: {
    backgroundColor: '#450A0A',
    borderWidth: 1,
    borderColor: '#991B1B',
    borderRadius: 10,
    padding: 14,
    marginBottom: 16,
  },
  disclaimerTitle: { color: '#F87171', fontSize: 12, fontWeight: '800', marginBottom: 4 },
  disclaimerBody: { color: '#FECACA', fontSize: 12, lineHeight: 16 },
  sectionHeader: { color: '#94A3B8', fontSize: 11, fontWeight: '700', marginBottom: 10 },
  typeGrid: { gap: 8, marginBottom: 16 },
  typeBtn: {
    backgroundColor: '#1E293B',
    paddingVertical: 12,
    paddingHorizontal: 16,
    borderRadius: 8,
    borderWidth: 1,
    borderColor: '#334155',
  },
  typeBtnActive: {
    backgroundColor: '#7F1D1D',
    borderColor: '#DC2626',
  },
  typeText: { color: '#E2E8F0', fontSize: 13, fontWeight: '600' },
  typeTextActive: { color: '#FFFFFF', fontWeight: '800' },
  toggleCard: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    backgroundColor: '#1E293B',
    padding: 14,
    borderRadius: 10,
    marginBottom: 20,
    borderWidth: 1,
    borderColor: '#334155',
  },
  toggleInfo: { flex: 1, marginRight: 12 },
  toggleTitle: { color: '#F8FAFC', fontSize: 14, fontWeight: '700' },
  toggleSub: { color: '#94A3B8', fontSize: 11, marginTop: 2 },
  broadcastSection: { marginBottom: 20 },
  bigBtn: {
    paddingVertical: 20,
    borderRadius: 16,
    alignItems: 'center',
    justifyContent: 'center',
  },
  startBtn: {
    backgroundColor: '#DC2626',
  },
  stopBtn: {
    backgroundColor: '#1E293B',
    borderWidth: 2,
    borderColor: '#DC2626',
  },
  bigBtnEmoji: { fontSize: 32, marginBottom: 6 },
  bigBtnText: { color: '#FFFFFF', fontSize: 18, fontWeight: '800', letterSpacing: 0.5 },
  bigBtnSub: { color: '#FEE2E2', fontSize: 12, marginTop: 4 },
  broadcastingPulse: { color: '#EF4444', fontSize: 12, fontWeight: '700', marginTop: 6 },
});
