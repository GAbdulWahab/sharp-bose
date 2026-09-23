const path = require('path');
const http = require('http');
const fs = require('fs');
const dgram = require('dgram');
const os = require('os');
const { spawn } = require('child_process');
const { WebSocketServer } = require('ws');

// Safe Electron import (supports both Electron GUI runtime and pure Node.js headless runtime)
let electron = null;
let app = null;
let BrowserWindow = null;
let ipcMain = null;

try {
  electron = require('electron');
  if (typeof electron === 'object' && electron !== null && electron.app) {
    app = electron.app;
    BrowserWindow = electron.BrowserWindow;
    ipcMain = electron.ipcMain;
  }
} catch (e) {
  // Pure Node.js environment
}

// -------------------------------------------------------------
// Low-RAM / Low-CPU Chromium Engine Flags (when in Electron)
// -------------------------------------------------------------
if (app && app.commandLine) {
  app.commandLine.appendSwitch('disable-gpu');
  app.commandLine.appendSwitch('disable-software-rasterizer');
  app.commandLine.appendSwitch('disable-gpu-compositing');
  app.commandLine.appendSwitch('disable-extensions');
  app.commandLine.appendSwitch('disable-component-update');
  app.commandLine.appendSwitch('disable-background-networking');
  app.commandLine.appendSwitch('disable-sync');
  app.commandLine.appendSwitch('disable-breakpad');
  app.commandLine.appendSwitch('disable-features', 'CalculateNativeWinOcclusion,SpareRendererForSitePerProcess');
  app.commandLine.appendSwitch('js-flags', '--max-old-space-size=64 --lite-mode');
  app.commandLine.appendSwitch('renderer-process-limit', '1');
}

const HTTP_PORT = 3000;
const UDP_PORT = 3000;
const LOCAL_NODE_ID = 'node-desktop-' + Math.random().toString(36).substring(2, 6);
const IS_HEADLESS = process.argv.includes('--headless') || process.argv.includes('-h') || !app;

let mainWindow = null;
let httpServer = null;
let wss = null;
let udpSocket = null;
let udpBeaconTimer = null;

// Native Windows Bluetooth Service state
let btProcess = null;
let latestBtStatus = { available: false, state: 'INITIALIZING' };
const btDiscoveredDevices = new Map();

function startWindowsBluetoothService() {
  if (process.platform !== 'win32') return;

  const exePath = path.join(__dirname, 'win-bluetooth', 'SharpBoseWinBluetooth.exe');
  if (!fs.existsSync(exePath)) {
    console.warn('[Bluetooth Service] Binary not found at:', exePath);
    return;
  }

  try {
    btProcess = spawn(exePath, [], {
      stdio: ['pipe', 'pipe', 'pipe'],
      windowsHide: true
    });

    let buffer = '';

    btProcess.stdout.on('data', (chunk) => {
      buffer += chunk.toString('utf8');
      const lines = buffer.split('\n');
      buffer = lines.pop(); // Keep last incomplete line

      for (const line of lines) {
        const trimmed = line.trim();
        if (!trimmed) continue;
        try {
          const json = JSON.parse(trimmed);
          handleBtServiceMessage(json);
        } catch (e) {}
      }
    });

    btProcess.stderr.on('data', (chunk) => {
      console.warn('[BT Service Warning]', chunk.toString('utf8').trim());
    });

    btProcess.on('exit', (code) => {
      console.log(`[Bluetooth Service] Process exited with code ${code}`);
      btProcess = null;
      if (!app?.isQuitting) {
        setTimeout(startWindowsBluetoothService, 3000);
      }
    });

    console.log('[Bluetooth Service] Native Windows Bluetooth Service started.');
  } catch (err) {
    console.error('[Bluetooth Service] Failed to spawn service:', err.message);
  }
}

function handleBtServiceMessage(msg) {
  if (!msg || !msg.type) return;

  let payload = msg.data;
  if (typeof payload === 'string') {
    try { payload = JSON.parse(payload); } catch (e) {}
  }

  switch (msg.type) {
    case 'STATUS':
    case 'RADIO_CHANGED':
      latestBtStatus = payload;
      if (mainWindow && !mainWindow.isDestroyed()) {
        mainWindow.webContents.send('bluetooth:status-changed', latestBtStatus);
      }
      break;

    case 'DEVICE_FOUND':
      if (payload && payload.address) {
        btDiscoveredDevices.set(payload.address, payload);
      }
      if (mainWindow && !mainWindow.isDestroyed()) {
        mainWindow.webContents.send('bluetooth:device-discovered', payload);
      }
      break;

    case 'CONNECT_STATUS':
      if (mainWindow && !mainWindow.isDestroyed()) {
        mainWindow.webContents.send('bluetooth:connection-changed', payload);
      }
      break;

    case 'SCAN_STATE':
      if (mainWindow && !mainWindow.isDestroyed()) {
        mainWindow.webContents.send('bluetooth:scan-state', payload);
      }
      break;

    case 'ERROR':
      if (mainWindow && !mainWindow.isDestroyed()) {
        mainWindow.webContents.send('bluetooth:error', typeof payload === 'string' ? payload : (payload.error || payload.message || 'Bluetooth Error'));
      }
      break;

    case 'LOG':
      if (mainWindow && !mainWindow.isDestroyed()) {
        mainWindow.webContents.send('bluetooth:log', typeof payload === 'string' ? payload : JSON.stringify(payload));
      }
      break;
  }
}

function sendBtCommand(cmd) {
  if (btProcess && btProcess.stdin && !btProcess.stdin.destroyed) {
    try {
      btProcess.stdin.write(cmd + '\n');
    } catch (e) {}
  }
}

const allWebSockets = new Set();
const clients = new Map(); // ws -> clientInfo

function getLocalIpAddresses() {
  const interfaces = os.networkInterfaces();
  const addresses = [];
  for (const name of Object.keys(interfaces)) {
    for (const iface of interfaces[name]) {
      if (iface.family === 'IPv4' && !iface.internal) {
        addresses.push({
          name: name,
          ip: iface.address,
          netmask: iface.netmask,
          mac: iface.mac
        });
      }
    }
  }
  return addresses;
}

// -------------------------------------------------------------
// 1. Embedded Mesh Hub WebSocket & Static HTTP Server
// -------------------------------------------------------------
function startEmbeddedHub() {
  const MIME_TYPES = {
    '.html': 'text/html',
    '.css': 'text/css',
    '.js': 'application/javascript',
    '.json': 'application/json',
    '.png': 'image/png',
    '.ico': 'image/x-icon'
  };

  httpServer = http.createServer((req, res) => {
    let reqPath = req.url.split('?')[0];
    if (reqPath === '/api/status') {
      res.writeHead(200, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({
        system: 'SHARP-BOSE-TACTICAL-MESH-HUB',
        nodeId: LOCAL_NODE_ID,
        version: '2.0.0',
        status: 'ONLINE',
        interfaces: getLocalIpAddresses(),
        bluetooth: latestBtStatus
      }));
      return;
    }

    // Serve static frontend files (allows browser usage with ~20MB RAM)
    if (reqPath === '/' || reqPath === '') reqPath = '/index.html';
    const filePath = path.join(__dirname, reqPath);

    fs.stat(filePath, (err, stats) => {
      if (!err && stats.isFile()) {
        const ext = path.extname(filePath).toLowerCase();
        const contentType = MIME_TYPES[ext] || 'application/octet-stream';
        res.writeHead(200, { 'Content-Type': contentType });
        fs.createReadStream(filePath).pipe(res);
      } else {
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({
          system: 'SHARP-BOSE-TACTICAL-MESH-HUB',
          nodeId: LOCAL_NODE_ID,
          version: '2.0.0',
          status: 'ONLINE',
          interfaces: getLocalIpAddresses()
        }));
      }
    });
  });

  httpServer.on('error', (err) => {
    if (err.code === 'EADDRINUSE') {
      console.log(`[Mesh Hub] Port ${HTTP_PORT} is already in use by active mesh hub. Connecting as client terminal.`);
    } else {
      console.warn('[HTTP Server Warning]', err.message);
    }
  });

  try {
    wss = new WebSocketServer({ server: httpServer });
    wss.on('error', (err) => {
      // Handled cleanly when port is occupied
    });

    function broadcastPeerList() {
      const allClients = Array.from(clients.values());

      for (const client of allWebSockets) {
        if (client.readyState === 1) { // OPEN
          const currentInfo = clients.get(client);
          const currentId = currentInfo ? currentInfo.id : null;

          // Exclude the recipient's own device and self local hub
          const peerList = allClients
            .filter(c => c.id !== currentId && c.id !== LOCAL_NODE_ID)
            .map(c => ({
              id: c.id,
              nickname: c.nickname,
              deviceType: c.deviceType,
              location: c.location || null,
              status: c.status || 'Online',
              room: c.room || 'INDIA-MAIN'
            }));

          client.send(JSON.stringify({ type: 'PEER_LIST', peers: peerList }));
        }
      }

      if (mainWindow && !mainWindow.isDestroyed()) {
        const remotePeers = Array.from(clients.values())
          .filter(c => !c.isLocal && c.id !== LOCAL_NODE_ID)
          .map(c => ({
            id: c.id,
            nickname: c.nickname,
            deviceType: c.deviceType,
            location: c.location || null,
            status: c.status || 'Online',
            room: c.room || 'INDIA-MAIN'
          }));

        mainWindow.webContents.send('hub:peer-list-updated', remotePeers);
      }
    }

    wss.on('connection', (ws, req) => {
      if (req.socket && req.socket.setNoDelay) {
        req.socket.setNoDelay(true); // Disable TCP buffering for instant live audio
      }
      if (ws._socket && ws._socket.setNoDelay) {
        ws._socket.setNoDelay(true);
      }
      allWebSockets.add(ws);
      ws.isAlive = true;
      ws.on('pong', () => { ws.isAlive = true; });

      const isMobile = /Android|iPhone|iPad/i.test(req.headers['user-agent'] || '');
      const remoteIp = req.socket?.remoteAddress || '';
      const isLoopback = remoteIp === '127.0.0.1' || remoteIp === '::1' || remoteIp === '::ffff:127.0.0.1';

      const clientInfo = {
        id: 'node-' + Math.random().toString(36).substring(2, 7),
        nickname: isMobile ? 'Android Phone' : (isLoopback ? 'Desktop Local' : 'Desktop/Laptop Peer'),
        deviceType: isMobile ? 'Android' : 'Desktop',
        status: 'Online',
        room: 'INDIA-MAIN',
        location: null,
        isLocal: isLoopback,
        remoteIp: remoteIp,
        ws: ws
      };

      clients.set(ws, clientInfo);

      // Assign ID to client
      ws.send(JSON.stringify({
        type: 'ASSIGN_ID',
        id: clientInfo.id,
        nickname: clientInfo.nickname,
        room: clientInfo.room
      }));

      broadcastPeerList();

      ws.on('message', (message, isBinary) => {
        ws.isAlive = true;
        // Audio stream binary forwarder
        const isAudioFrame = isBinary || (Buffer.isBuffer(message) && message.length >= 4 && message[0] === 0xAA && message[1] === 0x55);
        if (isAudioFrame) {
          for (const client of allWebSockets) {
            if (client !== ws && client.readyState === 1) {
              client.send(message, { binary: true });
            }
          }
          return;
        }

        try {
          const text = message.toString();
          const json = JSON.parse(text);

          switch (json.type) {
            case 'SET_NICKNAME':
              if (json.nickname) clientInfo.nickname = json.nickname;
              if (json.deviceType) clientInfo.deviceType = json.deviceType;
              broadcastPeerList();
              break;

            case 'JOIN_ROOM':
              if (json.room) clientInfo.room = json.room.toUpperCase();
              broadcastPeerList();
              break;

            case 'LOCATION_UPDATE':
              clientInfo.location = {
                lat: json.latitude,
                lng: json.longitude,
                alt: json.altitude || 0,
                accuracy: json.accuracy || 0,
                timestamp: Date.now()
              };
              broadcastPeerList();
              break;

            case 'CALL_INVITE':
            case 'CALL_ACCEPT':
            case 'CALL_DECLINE':
            case 'CALL_HANGUP':
            case 'PTT_START':
            case 'PTT_STOP':
            case 'CHAT_MSG':
            case 'SOS_BEACON':
              // Broadcast packet to all other connected peers
              for (const client of allWebSockets) {
                if (client !== ws && client.readyState === 1) {
                  client.send(text);
                }
              }
              break;
          }
        } catch (err) {}
      });

      const cleanup = () => {
        allWebSockets.delete(ws);
        clients.delete(ws);
        broadcastPeerList();
      };

      ws.on('close', cleanup);
      ws.on('error', cleanup);
    });

    httpServer.listen(HTTP_PORT, '0.0.0.0', () => {
      console.log(`[Mesh Hub] Embedded Hub active on http://0.0.0.0:${HTTP_PORT}`);
    });
  } catch (e) {
    console.log('[Mesh Hub Note]', e.message);
  }
}

// -------------------------------------------------------------
// 2. UDP Mesh Auto-Discovery Beacon
// -------------------------------------------------------------
function startUdpBeacon() {
  try {
    udpSocket = dgram.createSocket({ type: 'udp4', reuseAddr: true });

    udpSocket.on('error', (err) => {
      console.warn('[UDP Warning]', err.message);
    });

    udpSocket.on('message', (msg, rinfo) => {
      try {
        const str = msg.toString();
        if (str.startsWith('MESH_BEACON:')) {
          const parts = str.substring(12).split('|');
          if (parts.length >= 3) {
            const peerId = parts[0];
            const peerName = parts[1];
            const port = parseInt(parts[2], 10);
            if (peerId !== LOCAL_NODE_ID && mainWindow && !mainWindow.isDestroyed()) {
              mainWindow.webContents.send('hub:udp-peer-discovered', {
                peerId,
                peerName,
                ip: rinfo.address,
                port
              });
            }
          }
        }
      } catch (e) {}
    });

    udpSocket.bind(UDP_PORT, '0.0.0.0', () => {
      try {
        udpSocket.setBroadcast(true);
      } catch (e) {}
    });

    udpBeaconTimer = setInterval(() => {
      const beaconMsg = Buffer.from(`MESH_BEACON:${LOCAL_NODE_ID}|Desktop Hub (${os.hostname()})|${HTTP_PORT}`);
      try {
        if (udpSocket) {
          udpSocket.send(beaconMsg, 0, beaconMsg.length, UDP_PORT, '255.255.255.255', () => {});
        }
      } catch (e) {}
    }, 2500);
  } catch (e) {
    console.warn('[UDP Beacon Note]', e.message);
  }
}

// -------------------------------------------------------------
// 3. Electron Window Creation
// -------------------------------------------------------------
function createWindow() {
  if (IS_HEADLESS || !BrowserWindow) return;

  mainWindow = new BrowserWindow({
    width: 1140,
    height: 760,
    minWidth: 880,
    minHeight: 600,
    backgroundColor: '#0B0F19',
    title: 'SHARP-BOSE TACTICAL MESH // DESKTOP TERMINAL v2.0',
    icon: path.join(__dirname, 'icon.png'),
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      nodeIntegration: false,
      contextIsolation: true,
      webSecurity: true,
      backgroundThrottling: false,
      spellcheck: false,
      devTools: false
    }
  });

  mainWindow.setMenuBarVisibility(false);
  mainWindow.loadFile(path.join(__dirname, 'index.html'));

  mainWindow.on('closed', () => {
    mainWindow = null;
  });
}

// -------------------------------------------------------------
// 4. Runtime Lifecycle
// -------------------------------------------------------------
if (!app || IS_HEADLESS) {
  // Pure Node.js headless mode (super lightweight ~20MB RAM)
  startEmbeddedHub();
  startUdpBeacon();
  console.log(`[Mesh Hub] Headless hub active. Open http://localhost:${HTTP_PORT} in your browser.`);
} else {
  // Electron App mode
  const gotSingleLock = app.requestSingleInstanceLock();
  if (!gotSingleLock) {
    app.quit();
  } else {
    app.on('second-instance', () => {
      if (mainWindow) {
        if (mainWindow.isMinimized()) mainWindow.restore();
        mainWindow.focus();
      }
    });

    // IPC Handlers - Network and Mesh
    ipcMain.handle('app:get-interfaces', () => getLocalIpAddresses());
    ipcMain.handle('app:get-node-id', () => LOCAL_NODE_ID);
    ipcMain.handle('app:get-hostname', () => os.hostname());

    // IPC Handlers - Real Windows Bluetooth Service
    ipcMain.handle('bluetooth:get-status', () => {
      sendBtCommand('STATUS');
      return latestBtStatus;
    });
    ipcMain.handle('bluetooth:start-scan', () => {
      sendBtCommand('SCAN:START');
      return true;
    });
    ipcMain.handle('bluetooth:stop-scan', () => {
      sendBtCommand('SCAN:STOP');
      return true;
    });
    ipcMain.handle('bluetooth:connect', (event, address) => {
      sendBtCommand(`CONNECT:${address}`);
      return true;
    });
    ipcMain.handle('bluetooth:disconnect', () => {
      sendBtCommand('DISCONNECT');
      return true;
    });
    ipcMain.handle('bluetooth:set-auto-reconnect', (event, enabled) => {
      sendBtCommand(`AUTO_RECONNECT:${enabled}`);
      return true;
    });

    app.whenReady().then(() => {
      startEmbeddedHub();
      startUdpBeacon();
      startWindowsBluetoothService();
      createWindow();

      app.on('activate', () => {
        if (BrowserWindow.getAllWindows().length === 0) createWindow();
      });
    });

    app.on('window-all-closed', () => {
      if (process.platform !== 'darwin') {
        if (udpBeaconTimer) clearInterval(udpBeaconTimer);
        if (udpSocket) try { udpSocket.close(); } catch (e) {}
        if (httpServer) try { httpServer.close(); } catch (e) {}
        if (btProcess) {
          try {
            sendBtCommand('DISCONNECT');
            btProcess.kill();
          } catch (e) {}
        }
        app.quit();
      }
    });
  }
}
