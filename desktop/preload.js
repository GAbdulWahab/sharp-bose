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
