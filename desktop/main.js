const { app, BrowserWindow, ipcMain } = require('electron');
const path = require('path');
const http = require('http');
const fs = require('fs');
const dgram = require('dgram');
const os = require('os');
const { WebSocketServer } = require('ws');

// -------------------------------------------------------------
// Low-RAM / Low-CPU Chromium Engine Flags for Low-End Systems
// -------------------------------------------------------------
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

const HTTP_PORT = 3000;
const UDP_PORT = 3000;
const LOCAL_NODE_ID = 'node-desktop-' + Math.random().toString(36).substring(2, 6);
const IS_HEADLESS = process.argv.includes('--headless') || process.argv.includes('-h');

let mainWindow = null;
let httpServer = null;
let wss = null;
let udpSocket = null;
let udpBeaconTimer = null;

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
        interfaces: getLocalIpAddresses()
      }));
      return;
    }

    // Serve static frontend files (allows headless browser usage with ~20MB RAM)
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
    console.warn('[HTTP Server Warning]', err.message);
  });

  wss = new WebSocketServer({ server: httpServer });

  function broadcastPeerList() {
    const peerList = Array.from(clients.values()).map(c => ({
      id: c.id,
      nickname: c.nickname,
      deviceType: c.deviceType,
      location: c.location || null,
      status: c.status || 'Online',
      room: c.room || 'INDIA-MAIN',
      isLocal: false
    }));

    const msg = JSON.stringify({ type: 'PEER_LIST', peers: peerList });
    for (const client of allWebSockets) {
      if (client.readyState === 1) { // OPEN
        client.send(msg);
      }
    }

    if (mainWindow && !mainWindow.isDestroyed()) {
      mainWindow.webContents.send('hub:peer-list-updated', peerList);
    }
  }

  wss.on('connection', (ws, req) => {
    allWebSockets.add(ws);
    ws.isAlive = true;
    ws.on('pong', () => { ws.isAlive = true; });

    const isMobile = /Android|iPhone|iPad/i.test(req.headers['user-agent'] || '');
    const clientInfo = {
      id: 'node-' + Math.random().toString(36).substring(2, 7),
      nickname: isMobile ? 'Android Phone' : 'Desktop/Laptop Peer',
      deviceType: isMobile ? 'Android' : 'Desktop',
      status: 'Online',
      room: 'INDIA-MAIN',
      location: null,
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
      // Audio stream binary forwarder (zero-copy forward to peers)
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
            if (mainWindow && !mainWindow.isDestroyed()) {
              mainWindow.webContents.send('hub:control-packet', json);
            }
            break;
        }
      } catch (err) {
        // Silently discard malformed packets
      }
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
    console.log(`[Mesh Hub] Embedded Hub active on port ${HTTP_PORT}`);
  });
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
    }, 3000);
  } catch (e) {
    console.warn('[UDP Beacon Note]', e.message);
  }
}

// -------------------------------------------------------------
// 3. Ultra-Lightweight Electron Window Creation
// -------------------------------------------------------------
function createWindow() {
  if (IS_HEADLESS) {
    console.log('[Headless Mode] Desktop terminal running in background hub mode on port 3000.');
    return;
  }

  mainWindow = new BrowserWindow({
    width: 1120,
    height: 740,
    minWidth: 860,
    minHeight: 580,
    backgroundColor: '#0B0F19',
    title: 'SHARP-BOSE TACTICAL MESH // DESKTOP TERMINAL v2.0',
    icon: path.join(__dirname, 'icon.png'),
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      nodeIntegration: false,
      contextIsolation: true,
      webSecurity: true,
      backgroundThrottling: true,
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

// Single Instance Lock to prevent multiple heavy Electron instances
const gotSingleLock = app.requestSingleInstanceLock();
if (!gotSingleLock && !IS_HEADLESS) {
  app.quit();
} else {
  app.on('second-instance', () => {
    if (mainWindow) {
      if (mainWindow.isMinimized()) mainWindow.restore();
      mainWindow.focus();
    }
  });

  // IPC Handlers
  ipcMain.handle('app:get-interfaces', () => getLocalIpAddresses());
  ipcMain.handle('app:get-node-id', () => LOCAL_NODE_ID);
  ipcMain.handle('app:get-hostname', () => os.hostname());

  app.whenReady().then(() => {
    startEmbeddedHub();
    startUdpBeacon();
    createWindow();

    app.on('activate', () => {
      if (BrowserWindow.getAllWindows().length === 0 && !IS_HEADLESS) createWindow();
    });
  });

  app.on('window-all-closed', () => {
    if (process.platform !== 'darwin') {
      if (udpBeaconTimer) clearInterval(udpBeaconTimer);
      if (udpSocket) try { udpSocket.close(); } catch (e) {}
      if (httpServer) try { httpServer.close(); } catch (e) {}
      app.quit();
    }
  });
}
