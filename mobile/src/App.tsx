import React, { useState } from 'react';
import { StatusBar } from 'react-native';
import { HomeScreen } from './screens/HomeScreen';
import { ActiveCallScreen } from './screens/ActiveCallScreen';
import { ChatScreen } from './screens/ChatScreen';
import { DiagnosticsScreen } from './screens/DiagnosticsScreen';
import { SOSBroadcastScreen } from './screens/SOSBroadcastScreen';
import { ContactsScreen } from './screens/ContactsScreen';
import { PTTIntercomScreen } from './screens/PTTIntercomScreen';
import { FileShareScreen } from './screens/FileShareScreen';
import { TacticalMapScreen } from './screens/TacticalMapScreen';
import { ConnectedPeersScreen } from './screens/ConnectedPeersScreen';
import { IncomingCallModal } from './components/IncomingCallModal';
import { PeerNode } from './types/protocol';
import { darkTheme, lightTheme } from './types/theme';

type CurrentScreen =
  | { name: 'HOME' }
  | { name: 'CALL'; peer: PeerNode }
  | { name: 'CHAT'; peer: PeerNode }
  | { name: 'DIAGNOSTICS' }
  | { name: 'SOS' }
  | { name: 'CONTACTS' }
  | { name: 'PTT' }
  | { name: 'FILE_SHARE' }
  | { name: 'TACTICAL_MAP' }
  | { name: 'CONNECTED_PEERS' };

export default function App() {
  const [isDarkMode, setIsDarkMode] = useState(true);
  const [currentScreen, setCurrentScreen] = useState<CurrentScreen>({ name: 'HOME' });
  const [incomingCaller, setIncomingCaller] = useState<PeerNode | null>(null);

  const theme = isDarkMode ? darkTheme : lightTheme;

  const toggleTheme = () => {
    setIsDarkMode((prev) => !prev);
  };

  const simulateIncomingCall = () => {
    setIncomingCaller({
      id: '0x7F4A21B9',
      nickname: 'Sarah-iPhone',
      rssi: -54,
      batteryPercent: 92,
      hopCount: 1,
      transport: 'BLE_L2CAP',
      isPaired: true,
      lastSeenMs: Date.now(),
    });
  };

  const handleAcceptIncomingCall = () => {
    if (incomingCaller) {
      const caller = incomingCaller;
      setIncomingCaller(null);
      setCurrentScreen({ name: 'CALL', peer: caller });
    }
  };

  const handleDeclineIncomingCall = () => {
    setIncomingCaller(null);
  };

  return (
    <>
      <StatusBar
        barStyle={theme.statusBar}
        backgroundColor={theme.bg}
      />

      {/* Global Incoming Call Receiving Modal */}
      <IncomingCallModal
        visible={incomingCaller !== null}
        caller={incomingCaller}
        onAccept={handleAcceptIncomingCall}
        onDecline={handleDeclineIncomingCall}
        theme={theme}
      />

      {currentScreen.name === 'HOME' && (
        <HomeScreen
          onStartCall={(peer) => setCurrentScreen({ name: 'CALL', peer })}
          onOpenChat={(peer) => setCurrentScreen({ name: 'CHAT', peer })}
          onOpenSOS={() => setCurrentScreen({ name: 'SOS' })}
          onOpenDiagnostics={() => setCurrentScreen({ name: 'DIAGNOSTICS' })}
          onOpenContacts={() => setCurrentScreen({ name: 'CONTACTS' })}
          onOpenPTT={() => setCurrentScreen({ name: 'PTT' })}
          onOpenFileShare={() => setCurrentScreen({ name: 'FILE_SHARE' })}
          onOpenTacticalMap={() => setCurrentScreen({ name: 'TACTICAL_MAP' })}
          onOpenConnectedPeers={() => setCurrentScreen({ name: 'CONNECTED_PEERS' })}
          onSimulateIncomingCall={simulateIncomingCall}
          isDarkMode={isDarkMode}
          onToggleTheme={toggleTheme}
          theme={theme}
        />
      )}

      {currentScreen.name === 'CONNECTED_PEERS' && (
        <ConnectedPeersScreen
          onBack={() => setCurrentScreen({ name: 'HOME' })}
          onCallPeer={(peer) => setCurrentScreen({ name: 'CALL', peer })}
          onChatPeer={(peer) => setCurrentScreen({ name: 'CHAT', peer })}
          theme={theme}
        />
      )}

      {currentScreen.name === 'PTT' && (
        <PTTIntercomScreen onBack={() => setCurrentScreen({ name: 'HOME' })} />
      )}

      {currentScreen.name === 'FILE_SHARE' && (
        <FileShareScreen onBack={() => setCurrentScreen({ name: 'HOME' })} />
      )}

      {currentScreen.name === 'TACTICAL_MAP' && (
        <TacticalMapScreen
          onBack={() => setCurrentScreen({ name: 'HOME' })}
          onCallNode={(nodeName, nodeId) =>
            setCurrentScreen({
              name: 'CALL',
              peer: {
                id: nodeId,
                nickname: nodeName,
                rssi: -65,
                batteryPercent: 85,
                hopCount: 1,
                transport: 'BLE_L2CAP',
                isPaired: true,
                lastSeenMs: Date.now(),
              },
            })
          }
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
