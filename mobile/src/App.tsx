import React, { useState } from 'react';
import { StatusBar } from 'react-native';
import { HomeScreen } from './screens/HomeScreen';
import { ActiveCallScreen } from './screens/ActiveCallScreen';
import { ChatScreen } from './screens/ChatScreen';
import { DiagnosticsScreen } from './screens/DiagnosticsScreen';
import { SOSBroadcastScreen } from './screens/SOSBroadcastScreen';
import { ContactsScreen } from './screens/ContactsScreen';
import { PeerNode } from './types/protocol';

type CurrentScreen =
  | { name: 'HOME' }
  | { name: 'CALL'; peer: PeerNode }
  | { name: 'CHAT'; peer: PeerNode }
  | { name: 'DIAGNOSTICS' }
  | { name: 'SOS' }
  | { name: 'CONTACTS' };

export default function App() {
  const [currentScreen, setCurrentScreen] = useState<CurrentScreen>({ name: 'HOME' });

  return (
    <>
      <StatusBar barStyle="light-content" backgroundColor="#090D16" />

      {currentScreen.name === 'HOME' && (
        <HomeScreen
          onStartCall={(peer) => setCurrentScreen({ name: 'CALL', peer })}
          onOpenChat={(peer) => setCurrentScreen({ name: 'CHAT', peer })}
          onOpenSOS={() => setCurrentScreen({ name: 'SOS' })}
          onOpenDiagnostics={() => setCurrentScreen({ name: 'DIAGNOSTICS' })}
          onOpenContacts={() => setCurrentScreen({ name: 'CONTACTS' })}
        />
      )}

      {currentScreen.name === 'CALL' && (
        <ActiveCallScreen
          peerName={currentScreen.peer.nickname}
          peerId={currentScreen.peer.id}
          onHangup={() => setCurrentScreen({ name: 'HOME' })}
        />
      )}

      {currentScreen.name === 'CHAT' && (
        <ChatScreen
          peer={currentScreen.peer}
          onBack={() => setCurrentScreen({ name: 'HOME' })}
          onStartCall={() => setCurrentScreen({ name: 'CALL', peer: currentScreen.peer })}
        />
      )}

      {currentScreen.name === 'DIAGNOSTICS' && (
        <DiagnosticsScreen onBack={() => setCurrentScreen({ name: 'HOME' })} />
      )}

      {currentScreen.name === 'SOS' && (
        <SOSBroadcastScreen onBack={() => setCurrentScreen({ name: 'HOME' })} />
      )}

      {currentScreen.name === 'CONTACTS' && (
        <ContactsScreen
          onBack={() => setCurrentScreen({ name: 'HOME' })}
          onCallContact={(contact) =>
            setCurrentScreen({
              name: 'CALL',
              peer: {
                id: contact.id,
                nickname: contact.name,
                rssi: -60,
                batteryPercent: 100,
                hopCount: 1,
                transport: 'BLE_L2CAP',
                isPaired: contact.isVerified,
                lastSeenMs: Date.now(),
              },
            })
          }
        />
      )}
    </>
  );
}
