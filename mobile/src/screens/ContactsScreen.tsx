import React, { useState } from 'react';
import { View, Text, StyleSheet, TouchableOpacity, ScrollView, SafeAreaView } from 'react-native';
import { SASVerificationModal } from '../components/SASVerificationModal';

interface ContactItem {
  id: string;
  name: string;
  publicKeyHex: string;
  isVerified: boolean;
  lastCallTimestamp?: number;
}

interface ContactsScreenProps {
  onBack: () => void;
  onCallContact: (contact: ContactItem) => void;
}

export const ContactsScreen: React.FC<ContactsScreenProps> = ({ onBack, onCallContact }) => {
  const [contacts, setContacts] = useState<ContactItem[]>([
    {
      id: '0x7F4A21B9',
      name: 'Sarah Jenkins',
      publicKeyHex: 'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855',
      isVerified: true,
      lastCallTimestamp: Date.now() - 3600000,
    },
    {
      id: '0x99C2E8A1',
      name: 'David Miller',
      publicKeyHex: 'ca978112ca1bbdcafac231b39a23dc4da786eff8147c4e72b9807785afee48bb',
      isVerified: false,
      lastCallTimestamp: Date.now() - 86400000,
    },
  ]);

  const [verifyingContact, setVerifyingContact] = useState<ContactItem | null>(null);

  const handleVerify = (contact: ContactItem) => {
    setVerifyingContact(contact);
  };

  const confirmVerification = () => {
    if (verifyingContact) {
      setContacts((prev) =>
        prev.map((c) => (c.id === verifyingContact.id ? { ...c, isVerified: true } : c))
      );
      setVerifyingContact(null);
    }
  };

  return (
    <SafeAreaView style={styles.container}>
      <View style={styles.header}>
        <TouchableOpacity onPress={onBack}>
          <Text style={styles.backText}>← Back</Text>
        </TouchableOpacity>
        <Text style={styles.title}>SAVED CONTACTS</Text>
        <View style={{ width: 40 }} />
      </View>

      <ScrollView contentContainerStyle={styles.scroll}>
        <Text style={styles.subHeader}>OFFLINE CRYPTOGRAPHIC CONTACTS ({contacts.length})</Text>

        {contacts.map((contact) => (
          <View key={contact.id} style={styles.contactCard}>
            <View style={styles.contactInfo}>
              <View style={styles.nameRow}>
                <Text style={styles.contactName}>{contact.name}</Text>
                {contact.isVerified ? (
                  <Text style={styles.verifiedBadge}>✅ Verified Key</Text>
                ) : (
                  <TouchableOpacity onPress={() => handleVerify(contact)}>
                    <Text style={styles.unverifiedBadge}>⚠️ Verify SAS</Text>
                  </TouchableOpacity>
                )}
              </View>
              <Text style={styles.keyText}>ID: {contact.id}</Text>
              <Text style={styles.keyFingerprint} numberOfLines={1}>
                Key: {contact.publicKeyHex.substring(0, 16)}...
              </Text>
            </View>

            <TouchableOpacity
              style={styles.callBtn}
              onPress={() => onCallContact(contact)}
              accessibilityLabel={`Call ${contact.name}`}>
              <Text style={styles.callBtnText}>📞</Text>
            </TouchableOpacity>
          </View>
        ))}
      </ScrollView>

      {verifyingContact && (
        <SASVerificationModal
          visible={!!verifyingContact}
          peerName={verifyingContact.name}
          sasNumericCode="841920"
          sasWords={['falcon', 'river', 'beacon', 'summit']}
          onConfirmMatch={confirmVerification}
          onRejectMatch={() => setVerifyingContact(null)}
        />
      )}
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
  scroll: { padding: 16 },
  subHeader: { color: '#94A3B8', fontSize: 11, fontWeight: '700', marginBottom: 12 },
  contactCard: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    backgroundColor: '#1E293B',
    padding: 14,
    borderRadius: 12,
    marginBottom: 10,
    borderWidth: 1,
    borderColor: '#334155',
  },
  contactInfo: { flex: 1 },
  nameRow: { flexDirection: 'row', alignItems: 'center', gap: 8, marginBottom: 4 },
  contactName: { color: '#F8FAFC', fontSize: 15, fontWeight: '700' },
  verifiedBadge: { color: '#10B981', fontSize: 11, fontWeight: '700' },
  unverifiedBadge: {
    color: '#F59E0B',
    backgroundColor: '#78350F',
    paddingHorizontal: 6,
    paddingVertical: 2,
    borderRadius: 4,
    fontSize: 10,
    fontWeight: '800',
  },
  keyText: { color: '#38BDF8', fontSize: 11, fontFamily: 'monospace' },
  keyFingerprint: { color: '#64748B', fontSize: 11, fontFamily: 'monospace', marginTop: 2 },
  callBtn: {
    width: 44,
    height: 44,
    borderRadius: 22,
    backgroundColor: '#059669',
    justifyContent: 'center',
    alignItems: 'center',
    marginLeft: 12,
  },
  callBtnText: { fontSize: 18 },
});
