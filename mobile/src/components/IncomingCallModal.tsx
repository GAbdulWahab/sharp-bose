import React, { useEffect, useState } from 'react';
import {
  View,
  Text,
  StyleSheet,
  TouchableOpacity,
  Modal,
  Animated,
  Vibration,
} from 'react-native';
import { PeerNode } from '../types/protocol';
import { ThemeColors } from '../types/theme';

interface IncomingCallModalProps {
  visible: boolean;
  caller: PeerNode | null;
  onAccept: () => void;
  onDecline: () => void;
  theme: ThemeColors;
}

export const IncomingCallModal: React.FC<IncomingCallModalProps> = ({
  visible,
  caller,
  onAccept,
  onDecline,
  theme,
}) => {
  const [pulseAnim] = useState(new Animated.Value(1));

  useEffect(() => {
    if (visible) {
      Vibration.vibrate([0, 500, 500, 500], true);
      const loop = Animated.loop(
        Animated.sequence([
          Animated.timing(pulseAnim, { toValue: 1.3, duration: 600, useNativeDriver: true }),
          Animated.timing(pulseAnim, { toValue: 1.0, duration: 600, useNativeDriver: true }),
        ])
      );
      loop.start();
      return () => {
        Vibration.cancel();
        loop.stop();
      };
    } else {
      Vibration.cancel();
    }
  }, [visible]);

  if (!visible || !caller) return null;

  return (
    <Modal visible={visible} transparent animationType="slide">
      <View style={styles.overlay}>
        <View style={[styles.dialogCard, { backgroundColor: theme.card, borderColor: theme.cardBorder }]}>
          {/* Security Badge */}
          <View style={[styles.secBadge, { backgroundColor: theme.cardHighlight }]}>
            <Text style={[styles.secBadgeText, { color: theme.accentCyan }]}>
              🔒 OFFLINE E2EE CALL (Noise_XX)
            </Text>
          </View>

          {/* Caller Avatar with Pulse */}
          <View style={styles.avatarContainer}>
            <Animated.View
              style={[
                styles.pulseRing,
                {
                  borderColor: theme.accentEmerald,
                  transform: [{ scale: pulseAnim }],
                },
              ]}
            />
            <View style={[styles.avatarCircle, { backgroundColor: theme.accentEmerald }]}>
              <Text style={styles.avatarText}>
                {caller.nickname.substring(0, 2).toUpperCase()}
              </Text>
            </View>
          </View>

          {/* Caller Details */}
          <Text style={[styles.callerName, { color: theme.textPrimary }]}>{caller.nickname}</Text>
          <Text style={[styles.callerRoute, { color: theme.accentBlue }]}>
            Incoming Peer-to-Peer Voice Call
          </Text>
          <Text style={[styles.callerMeta, { color: theme.textSecondary }]}>
            ID: {caller.id} • {caller.rssi} dBm • {caller.hopCount === 1 ? 'Direct Link' : `${caller.hopCount} Hops Mesh`}
          </Text>

          {/* Action Buttons */}
          <View style={styles.actionRow}>
            {/* Decline Button */}
            <TouchableOpacity
              style={[styles.btnAction, styles.btnDecline]}
              onPress={onDecline}
            >
              <Text style={styles.btnIcon}>✕</Text>
              <Text style={styles.btnText}>Decline</Text>
            </TouchableOpacity>

            {/* Accept Button */}
            <TouchableOpacity
              style={[styles.btnAction, styles.btnAccept]}
              onPress={onAccept}
            >
              <Text style={styles.btnIcon}>📞</Text>
              <Text style={styles.btnText}>Accept</Text>
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
    padding: 24,
  },
  dialogCard: {
    width: '100%',
    borderRadius: 28,
    borderWidth: 1.5,
    padding: 24,
    alignItems: 'center',
    elevation: 20,
    shadowColor: '#000',
    shadowOpacity: 0.5,
    shadowRadius: 20,
  },
  secBadge: {
    paddingHorizontal: 12,
    paddingVertical: 6,
    borderRadius: 12,
    marginBottom: 20,
  },
  secBadgeText: {
    fontSize: 11,
    fontWeight: 'bold',
    letterSpacing: 0.5,
  },
  avatarContainer: {
    width: 100,
    height: 100,
    justifyContent: 'center',
    alignItems: 'center',
    marginBottom: 16,
  },
  pulseRing: {
    position: 'absolute',
    width: 100,
    height: 100,
    borderRadius: 50,
    borderWidth: 3,
  },
  avatarCircle: {
    width: 80,
    height: 80,
    borderRadius: 40,
    justifyContent: 'center',
    alignItems: 'center',
  },
  avatarText: {
    color: '#FFFFFF',
    fontSize: 28,
    fontWeight: '900',
  },
  callerName: {
    fontSize: 24,
    fontWeight: '800',
    marginBottom: 4,
  },
  callerRoute: {
    fontSize: 14,
    fontWeight: '600',
    marginBottom: 4,
  },
  callerMeta: {
    fontSize: 12,
    marginBottom: 28,
    fontFamily: 'monospace',
  },
  actionRow: {
    flexDirection: 'row',
    width: '100%',
    gap: 16,
  },
  btnAction: {
    flex: 1,
    height: 60,
    borderRadius: 20,
    justifyContent: 'center',
    alignItems: 'center',
    flexDirection: 'row',
    gap: 8,
  },
  btnDecline: {
    backgroundColor: '#DC2626',
  },
  btnAccept: {
    backgroundColor: '#059669',
  },
  btnIcon: {
    fontSize: 20,
    color: '#FFFFFF',
  },
  btnText: {
    color: '#FFFFFF',
    fontSize: 16,
    fontWeight: 'bold',
  },
});
