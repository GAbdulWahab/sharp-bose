import React from 'react';
import { View, Text, StyleSheet } from 'react-native';

interface AudioQualityMeterProps {
  latencyMs: number;
  packetLossPercent: number;
  jitterMs: number;
  bitrateKbps: number;
  codec: string;
}

export const AudioQualityMeter: React.FC<AudioQualityMeterProps> = ({
  latencyMs,
  packetLossPercent,
  jitterMs,
  bitrateKbps,
  codec,
}) => {
  const getQualityBadge = () => {
    if (latencyMs < 60 && packetLossPercent < 1.0) {
      return { label: 'EXCELLENT', color: '#10B981', bg: '#064E3B' };
    }
    if (latencyMs < 120 && packetLossPercent < 3.0) {
      return { label: 'GOOD', color: '#38BDF8', bg: '#0C4A6E' };
    }
    if (latencyMs < 200 || packetLossPercent < 7.0) {
      return { label: 'FAIR', color: '#F59E0B', bg: '#78350F' };
    }
    return { label: 'POOR / PACKET LOSS', color: '#EF4444', bg: '#7F1D1D' };
  };

  const badge = getQualityBadge();

  return (
    <View style={styles.container}>
      <View style={styles.topRow}>
        <Text style={styles.title}>AUDIO LINK METRICS</Text>
        <View style={[styles.badgeContainer, { backgroundColor: badge.bg }]}>
          <Text style={[styles.badgeText, { color: badge.color }]}>{badge.label}</Text>
        </View>
      </View>

      <View style={styles.grid}>
        <View style={styles.statBox}>
          <Text style={styles.statLabel}>RTT Latency</Text>
          <Text style={styles.statValue}>{latencyMs.toFixed(0)} ms</Text>
        </View>
        <View style={styles.statBox}>
          <Text style={styles.statLabel}>Packet Loss</Text>
          <Text style={[styles.statValue, packetLossPercent > 2.0 ? styles.warnText : null]}>
            {packetLossPercent.toFixed(1)}%
          </Text>
        </View>
        <View style={styles.statBox}>
          <Text style={styles.statLabel}>Jitter</Text>
          <Text style={styles.statValue}>{jitterMs.toFixed(1)} ms</Text>
        </View>
        <View style={styles.statBox}>
          <Text style={styles.statLabel}>Bitrate</Text>
          <Text style={styles.statValue}>{bitrateKbps.toFixed(1)} kbps</Text>
        </View>
      </View>

      <Text style={styles.codecInfo}>Codec: {codec} (Adaptive VBR + In-Band FEC)</Text>
    </View>
  );
};

const styles = StyleSheet.create({
  container: {
    backgroundColor: '#1E293B',
    borderRadius: 12,
    padding: 14,
    marginVertical: 10,
    borderWidth: 1,
    borderColor: '#334155',
  },
  topRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    marginBottom: 10,
  },
  title: {
    color: '#94A3B8',
    fontSize: 11,
    fontWeight: '700',
    letterSpacing: 1,
  },
  badgeContainer: {
    paddingHorizontal: 8,
    paddingVertical: 3,
    borderRadius: 6,
  },
  badgeText: {
    fontSize: 10,
    fontWeight: '800',
  },
  grid: {
    flexDirection: 'row',
    justifyContent: 'space-between',
  },
  statBox: {
    alignItems: 'center',
  },
  statLabel: {
    color: '#64748B',
    fontSize: 10,
    marginBottom: 2,
  },
  statValue: {
    color: '#F8FAFC',
    fontSize: 14,
    fontWeight: '700',
    fontFamily: 'monospace',
  },
  warnText: {
    color: '#EF4444',
  },
  codecInfo: {
    marginTop: 8,
    color: '#64748B',
    fontSize: 10,
    textAlign: 'center',
  },
});
