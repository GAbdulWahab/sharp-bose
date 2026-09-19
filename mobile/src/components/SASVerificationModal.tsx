import React from 'react';
import { View, Text, StyleSheet, Modal, TouchableOpacity } from 'react-native';

interface SASVerificationModalProps {
  visible: boolean;
  peerName: string;
  sasNumericCode: string;
  sasWords: string[];
  onConfirmMatch: () => void;
  onRejectMatch: () => void;
}

export const SASVerificationModal: React.FC<SASVerificationModalProps> = ({
  visible,
  peerName,
  sasNumericCode,
  sasWords,
  onConfirmMatch,
  onRejectMatch,
}) => {
  return (
    <Modal visible={visible} transparent animationType="fade">
      <View style={styles.overlay}>
        <View style={styles.card}>
          <Text style={styles.title}>🔐 Verify Secure Connection</Text>
          <Text style={styles.subtitle}>
            Compare the verification code below with <Text style={styles.bold}>{peerName}</Text>'s screen to guarantee no eavesdropping.
          </Text>

          {/* 6-Digit Numeric Code */}
          <View style={styles.codeBox}>
            <Text style={styles.codeText}>{sasNumericCode}</Text>
          </View>

          {/* 4-Word Visual Verification */}
          <View style={styles.wordBox}>
            {sasWords.map((word, index) => (
              <View key={index} style={styles.wordBadge}>
                <Text style={styles.wordText}>{word}</Text>
              </View>
            ))}
          </View>

          <Text style={styles.helperText}>
            Do both screens show identical codes?
          </Text>

          <View style={styles.buttonRow}>
            <TouchableOpacity style={[styles.btn, styles.rejectBtn]} onPress={onRejectMatch}>
              <Text style={styles.btnText}>❌ Do Not Match</Text>
            </TouchableOpacity>

            <TouchableOpacity style={[styles.btn, styles.confirmBtn]} onPress={onConfirmMatch}>
              <Text style={styles.btnText}>✅ Confirm Match</Text>
            </TouchableOpacity>
          </View>
        </View>
      </View>
    </Modal>
  );
};

const styles = StyleSheet.create({
  overlay: {
    flex: 1,
    backgroundColor: 'rgba(0, 0, 0, 0.85)',
    justifyContent: 'center',
    alignItems: 'center',
    padding: 20,
  },
  card: {
    backgroundColor: '#1E293B',
    borderRadius: 16,
    padding: 24,
    width: '100%',
    maxWidth: 380,
    borderWidth: 1,
    borderColor: '#334155',
    alignItems: 'center',
  },
  title: {
    color: '#F8FAFC',
    fontSize: 20,
    fontWeight: '700',
    marginBottom: 8,
  },
  subtitle: {
    color: '#94A3B8',
    fontSize: 13,
    textAlign: 'center',
    lineHeight: 18,
    marginBottom: 20,
  },
  bold: {
    color: '#38BDF8',
    fontWeight: '700',
  },
  codeBox: {
    backgroundColor: '#0F172A',
    paddingHorizontal: 24,
    paddingVertical: 14,
    borderRadius: 10,
    borderWidth: 1,
    borderColor: '#38BDF8',
    marginBottom: 16,
  },
  codeText: {
    color: '#38BDF8',
    fontSize: 32,
    fontWeight: '800',
    letterSpacing: 6,
    fontFamily: 'monospace',
  },
  wordBox: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    justifyContent: 'center',
    gap: 8,
    marginBottom: 20,
  },
  wordBadge: {
    backgroundColor: '#334155',
    paddingHorizontal: 12,
    paddingVertical: 6,
    borderRadius: 6,
  },
  wordText: {
    color: '#E2E8F0',
    fontSize: 13,
    fontWeight: '600',
  },
  helperText: {
    color: '#64748B',
    fontSize: 12,
    marginBottom: 20,
    textAlign: 'center',
  },
  buttonRow: {
    flexDirection: 'row',
    gap: 12,
    width: '100%',
  },
  btn: {
    flex: 1,
    paddingVertical: 14,
    borderRadius: 10,
    alignItems: 'center',
  },
  rejectBtn: {
    backgroundColor: '#EF4444',
  },
  confirmBtn: {
    backgroundColor: '#10B981',
  },
  btnText: {
    color: '#FFFFFF',
    fontSize: 14,
    fontWeight: '700',
  },
});
