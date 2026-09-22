import React, { useState, useEffect } from 'react';
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
import { NativeBridge } from './services/NativeBridge';

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

  useEffect(() => {
    // Listen for live incoming calls from Laptop or other Android phones
    const unsubscribeIncoming = NativeBridge.onIncomingCall((data) => {
      console.log('[App] Incoming call received from:', data.callerName, data.callerId);
      setIncomingCaller(data.peer);
    });

    const unsubscribeCallState = NativeBridge.onCallStateChanged((data) => {
      if (data.state === 'ENDED') {
        setIncomingCaller(null);
        setCurrentScreen((prev) => (prev.name === 'CALL' ? { name: 'HOME' } : prev));
      }
    });

    return () => {
      unsubscribeIncoming();
      unsubscribeCallState();
    };
  }, []);

  const simulateIncomingCall = () => {
    setIncomingCaller({
      id: 'node-sim',
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
      NativeBridge.acceptIncomingCall('active', caller.id);
      setCurrentScreen({ name: 'CALL', peer: caller });
    }
  };

  const handleDeclineIncomingCall = () => {
    if (incomingCaller) {
      NativeBridge.declineIncomingCall(incomingCaller.id);
      setIncomingCaller(null);
    }
  };

  const handleHangupCall = () => {
    NativeBridge.endCall();
    setCurrentScreen({ name: 'HOME' });
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
          onStartCall={(peer) => {
            NativeBridge.initiateCall(peer.id, peer.nickname);
            setCurrentScreen({ name: 'CALL', peer });
          }}
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
          onCallPeer={(peer) => {
            NativeBridge.initiateCall(peer.id, peer.nickname);
            setCurrentScreen({ name: 'CALL', peer });
          }}
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
          onCallNode={(nodeName, nodeId) => {
            const peer = {
              id: nodeId,
              nickname: nodeName,
              rssi: -65,
              batteryPercent: 85,
              hopCount: 1,
              transport: 'WIFI_DIRECT' as const,
              isPaired: true,
              lastSeenMs: Date.now(),
            };
            NativeBridge.initiateCall(nodeId, nodeName);
            setCurrentScreen({ name: 'CALL', peer });
          }}
        />
      )}

      {currentScreen.name === 'CALL' && (
        <ActiveCallScreen
          peerName={currentScreen.peer.nickname}
          peerId={currentScreen.peer.id}
          onHangup={handleHangupCall}
        />
      )}

      {currentScreen.name === 'CHAT' && (
        <ChatScreen
          peer={currentScreen.peer}
          onBack={() => setCurrentScreen({ name: 'HOME' })}
          onStartCall={() => {
            NativeBridge.initiateCall(currentScreen.peer.id, currentScreen.peer.nickname);
            setCurrentScreen({ name: 'CALL', peer: currentScreen.peer });
          }}
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
          onCallContact={(contact) => {
            const peer: PeerNode = {
              id: contact.id,
              nickname: contact.name,
              rssi: -60,
              batteryPercent: 100,
              hopCount: 1,
              transport: 'WIFI_DIRECT',
              isPaired: contact.isVerified,
              lastSeenMs: Date.now(),
            };
            NativeBridge.initiateCall(contact.id, contact.name);
            setCurrentScreen({ name: 'CALL', peer });
          }}
        />
      )}
    </>
  );
}
