import React, { useState } from 'react';
import { View, Text, StyleSheet, TextInput, TouchableOpacity, ScrollView, SafeAreaView } from 'react-native';
import { ChatMessage, PeerNode } from '../types/protocol';

interface ChatScreenProps {
  peer: PeerNode;
  onBack: () => void;
  onStartCall: () => void;
}

export const ChatScreen: React.FC<ChatScreenProps> = ({ peer, onBack, onStartCall }) => {
  const [inputText, setInputText] = useState('');
  const [messages, setMessages] = useState<ChatMessage[]>([
    {
      id: 'm1',
      senderId: peer.id,
      senderName: peer.nickname,
      recipientId: '0xME',
      text: 'Hey! Are you in range for voice calling?',
      timestamp: Date.now() - 60000,
      status: 'READ',
      hopCount: 1,
    },
    {
      id: 'm2',
      senderId: '0xME',
      senderName: 'Me',
      recipientId: peer.id,
      text: 'Yes, Bluetooth L2CAP connection looks solid (-58 dBm).',
      timestamp: Date.now() - 30000,
      status: 'DELIVERED',
      hopCount: 1,
    },
  ]);

  const handleSend = () => {
    if (!inputText.trim()) return;
    const newMsg: ChatMessage = {
      id: `m_${Date.now()}`,
      senderId: '0xME',
      senderName: 'Me',
      recipientId: peer.id,
      text: inputText.trim(),
      timestamp: Date.now(),
      status: 'SENT',
      hopCount: peer.hopCount,
    };
    setMessages((prev) => [...prev, newMsg]);
    setInputText('');
  };

  const getStatusIcon = (status: ChatMessage['status']) => {
    switch (status) {
      case 'QUEUED': return '⏳ Queued (DTN)';
      case 'SENT': return '↗️ Sent';
      case 'RELAYED': return '🔄 Relaying (Mesh)';
      case 'DELIVERED': return '✓✓ Delivered';
      case 'READ': return '✓✓ Read';
      case 'FAILED': return '⚠️ Failed';
      default: return '';
    }
  };

  return (
    <SafeAreaView style={styles.container}>
      {/* Top Header */}
      <View style={styles.header}>
        <TouchableOpacity style={styles.backBtn} onPress={onBack}>
          <Text style={styles.backText}>← Back</Text>
        </TouchableOpacity>
        <View style={styles.peerHeaderInfo}>
          <Text style={styles.peerName}>{peer.nickname}</Text>
          <Text style={styles.peerStatus}>
            {peer.hopCount === 1 ? '● Direct Link' : `● Relayed (${peer.hopCount} hops)`}
          </Text>
        </View>
        <TouchableOpacity style={styles.callHeaderBtn} onPress={onStartCall}>
          <Text style={styles.callHeaderText}>📞 Call</Text>
        </TouchableOpacity>
      </View>

      {/* Message List */}
      <ScrollView style={styles.messageList} contentContainerStyle={styles.messageScroll}>
        {messages.map((msg) => {
          const isMe = msg.senderId === '0xME';
          return (
            <View key={msg.id} style={[styles.bubbleWrapper, isMe ? styles.myWrapper : styles.theirWrapper]}>
              <View style={[styles.bubble, isMe ? styles.myBubble : styles.theirBubble]}>
                <Text style={styles.messageText}>{msg.text}</Text>
                <View style={styles.metaRow}>
                  <Text style={styles.timeText}>
                    {new Date(msg.timestamp).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}
                  </Text>
                  {isMe && <Text style={styles.statusText}>{getStatusIcon(msg.status)}</Text>}
                </View>
              </View>
            </View>
          );
        })}
      </ScrollView>

      {/* Input Bar */}
      <View style={styles.inputContainer}>
        <TextInput
          style={styles.textInput}
          placeholder="Send offline encrypted message..."
          placeholderTextColor="#64748B"
          value={inputText}
          onChangeText={setInputText}
          multiline
        />
        <TouchableOpacity style={styles.sendBtn} onPress={handleSend}>
          <Text style={styles.sendText}>Send</Text>
        </TouchableOpacity>
      </View>
    </SafeAreaView>
  );
};

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: '#090D16',
  },
  header: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingHorizontal: 16,
    paddingVertical: 12,
    backgroundColor: '#0F172A',
    borderBottomWidth: 1,
    borderBottomColor: '#1E293B',
  },
  backBtn: {
    padding: 6,
  },
  backText: {
    color: '#38BDF8',
    fontSize: 14,
    fontWeight: '600',
  },
  peerHeaderInfo: {
    alignItems: 'center',
  },
  peerName: {
    color: '#F8FAFC',
    fontSize: 16,
    fontWeight: '700',
  },
  peerStatus: {
    color: '#10B981',
    fontSize: 11,
    marginTop: 2,
  },
  callHeaderBtn: {
    backgroundColor: '#059669',
    paddingHorizontal: 12,
    paddingVertical: 6,
    borderRadius: 8,
  },
  callHeaderText: {
    color: '#FFFFFF',
    fontSize: 13,
    fontWeight: '700',
  },
  messageList: {
    flex: 1,
  },
  messageScroll: {
    padding: 16,
  },
  bubbleWrapper: {
    marginVertical: 6,
    flexDirection: 'row',
  },
  myWrapper: {
    justifyContent: 'flex-end',
  },
  theirWrapper: {
    justifyContent: 'flex-start',
  },
  bubble: {
    maxWidth: '80%',
    borderRadius: 14,
    paddingHorizontal: 14,
    paddingVertical: 10,
  },
  myBubble: {
    backgroundColor: '#1D4ED8',
    borderBottomRightRadius: 2,
  },
  theirBubble: {
    backgroundColor: '#1E293B',
    borderBottomLeftRadius: 2,
    borderWidth: 1,
    borderColor: '#334155',
  },
  messageText: {
    color: '#F8FAFC',
    fontSize: 15,
    lineHeight: 20,
  },
  metaRow: {
    flexDirection: 'row',
    justifyContent: 'flex-end',
    alignItems: 'center',
    marginTop: 4,
    gap: 6,
  },
  timeText: {
    color: '#93C5FD',
    fontSize: 10,
  },
  statusText: {
    color: '#BFDBFE',
    fontSize: 10,
    fontWeight: '600',
  },
  inputContainer: {
    flexDirection: 'row',
    padding: 12,
    backgroundColor: '#0F172A',
    borderTopWidth: 1,
    borderTopColor: '#1E293B',
    alignItems: 'center',
  },
  textInput: {
    flex: 1,
    backgroundColor: '#1E293B',
    borderRadius: 20,
    paddingHorizontal: 16,
    paddingVertical: 10,
    color: '#F8FAFC',
    fontSize: 14,
    maxHeight: 100,
  },
  sendBtn: {
    backgroundColor: '#2563EB',
    paddingHorizontal: 16,
    paddingVertical: 10,
    borderRadius: 20,
    marginLeft: 8,
  },
  sendText: {
    color: '#FFFFFF',
    fontWeight: '700',
    fontSize: 14,
  },
});
