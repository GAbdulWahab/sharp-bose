const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('electronAPI', {
  getInterfaces: () => ipcRenderer.invoke('app:get-interfaces'),
  getNodeId: () => ipcRenderer.invoke('app:get-node-id'),
  getHostname: () => ipcRenderer.invoke('app:get-hostname'),
  
  onPeerListUpdated: (callback) => {
    ipcRenderer.on('hub:peer-list-updated', (event, data) => callback(data));
  },
  onControlPacket: (callback) => {
    ipcRenderer.on('hub:control-packet', (event, data) => callback(data));
  },
  onAudioFrame: (callback) => {
    ipcRenderer.on('hub:audio-frame', (event, data) => callback(data));
  },
  onUdpPeerDiscovered: (callback) => {
    ipcRenderer.on('hub:udp-peer-discovered', (event, data) => callback(data));
  }
});

contextBridge.exposeInMainWorld('bluetoothAPI', {
  getStatus: () => ipcRenderer.invoke('bluetooth:get-status'),
  startScan: () => ipcRenderer.invoke('bluetooth:start-scan'),
  stopScan: () => ipcRenderer.invoke('bluetooth:stop-scan'),
  connect: (address) => ipcRenderer.invoke('bluetooth:connect', address),
  disconnect: () => ipcRenderer.invoke('bluetooth:disconnect'),
  setAutoReconnect: (enabled) => ipcRenderer.invoke('bluetooth:set-auto-reconnect', enabled),

  onStatusChanged: (callback) => {
    ipcRenderer.on('bluetooth:status-changed', (event, data) => callback(data));
  },
  onDeviceDiscovered: (callback) => {
    ipcRenderer.on('bluetooth:device-discovered', (event, data) => callback(data));
  },
  onConnectionChanged: (callback) => {
    ipcRenderer.on('bluetooth:connection-changed', (event, data) => callback(data));
  },
  onScanStateChanged: (callback) => {
    ipcRenderer.on('bluetooth:scan-state', (event, data) => callback(data));
  },
  onError: (callback) => {
    ipcRenderer.on('bluetooth:error', (event, data) => callback(data));
  },
  onLog: (callback) => {
    ipcRenderer.on('bluetooth:log', (event, data) => callback(data));
  }
});
