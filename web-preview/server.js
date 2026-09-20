const http = require('http');
const https = require('https');
const fs = require('fs');
const path = require('path');
const { WebSocketServer } = require('ws');
const { generateSelfSignedCert } = require('./gencert');

const HTTP_PORT = 3000;
const HTTPS_PORT = 3443;
const FILE_PATH = path.join(__dirname, 'index.html');

function handleHttpRequest(req, res) {
  fs.readFile(FILE_PATH, (err, data) => {
    if (err) {
      res.writeHead(500, { 'Content-Type': 'text/plain' });
      res.end('Error loading preview');
    } else {
      res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
      res.end(data);
    }
  });
}

// 1. HTTP Server
const httpServer = http.createServer(handleHttpRequest);
const wssHttp = new WebSocketServer({ server: httpServer });

// 2. HTTPS Server (for Mobile Secure Context / Microphone permission)
let httpsServer = null;
let wssHttps = null;
try {
  const { key, cert } = generateSelfSignedCert();
  httpsServer = https.createServer({ key, cert }, handleHttpRequest);
  wssHttps = new WebSocketServer({ server: httpsServer });
  console.log('✅ Generated self-signed SSL certificate for HTTPS.');
} catch (e) {
  console.warn('⚠️ Could not start HTTPS server:', e.message);
}

const allWebSockets = new Set();
let clients = new Map(); // ws -> clientInfo

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
}

function handleWsConnection(ws, req) {
  allWebSockets.add(ws);
  ws.isAlive = true;
  ws.on('pong', () => { ws.isAlive = true; });

  const isMobile = /Android|iPhone|iPad/i.test(req.headers['user-agent'] || '');
  const clientInfo = {
    id: 'node-' + Math.random().toString(36).substring(2, 7),
    nickname: isMobile ? 'Android Phone' : 'Laptop',
    deviceType: isMobile ? 'Android' : 'Laptop',
    status: 'Online',
    room: 'INDIA-MAIN',
    location: null,
    ws: ws
  };

  clients.set(ws, clientInfo);
  console.log(`[Mesh Radio] Connected: ${clientInfo.nickname} (${clientInfo.id}) from ${req.socket.remoteAddress}`);

  // Send assigned client info to self
  ws.send(JSON.stringify({ type: 'ASSIGN_ID', id: clientInfo.id, nickname: clientInfo.nickname, room: clientInfo.room }));

  // Notify everyone of updated peer list
  broadcastPeerList();

  ws.on('message', (message, isBinary) => {
    ws.isAlive = true;
    // If binary, it's a live audio frame chunk
    if (isBinary) {
      // Forward binary audio frame to all other connected peers
      for (const client of allWebSockets) {
        if (client !== ws && client.readyState === 1) {
          client.send(message, { binary: true });
        }
      }
      return;
    }

    try {
      const data = JSON.parse(message.toString());

      if (data.type === 'PING') {
        if (ws.readyState === 1) {
          ws.send(JSON.stringify({ type: 'PONG', timestamp: Date.now() }));
        }
        return;
      }

      if (data.type === 'SET_NICKNAME') {
        clientInfo.nickname = data.nickname;
        if (data.deviceType) clientInfo.deviceType = data.deviceType;
        if (data.room) clientInfo.room = data.room;
        broadcastPeerList();
      } else if (data.type === 'JOIN_ROOM') {
        clientInfo.room = data.room || 'INDIA-MAIN';
        broadcastPeerList();
      } else if (data.type === 'LOCATION_UPDATE') {
        clientInfo.location = {
          lat: data.latitude,
          lng: data.longitude,
          alt: data.altitude || 0,
          accuracy: data.accuracy || 0,
          speed: data.speed || 0,
          heading: data.heading || 0,
          timestamp: Date.now()
        };
        data.senderId = clientInfo.id;
        data.senderName = clientInfo.nickname;
        data.deviceType = clientInfo.deviceType;
        const outMsg = JSON.stringify(data);
        for (const client of allWebSockets) {
          if (client !== ws && client.readyState === 1) {
            client.send(outMsg);
          }
        }
        broadcastPeerList();
      } else if (data.type === 'MESH_PACKET') {
        // Multi-hop packet relaying across mesh
        data.hopCount = (data.hopCount || 0) + 1;
        if (!data.relayPath) data.relayPath = [];
        data.relayPath.push(clientInfo.id);

        const outMsg = JSON.stringify(data);
        for (const client of allWebSockets) {
          if (client !== ws && client.readyState === 1) {
            client.send(outMsg);
          }
        }
      } else if (data.type === 'CALL_INVITE' || data.type === 'CALL_ACCEPT' || data.type === 'CALL_DECLINE' || data.type === 'CALL_HANGUP' || data.type === 'PTT_START' || data.type === 'PTT_STOP' || data.type === 'CHAT_MSG' || data.type === 'SOS_ALERT' || data.type === 'SIGNAL_OFFER' || data.type === 'SIGNAL_ANSWER' || data.type === 'SIGNAL_CANDIDATE') {
        if (data.type === 'CALL_ACCEPT') clientInfo.status = 'In Call';
        if (data.type === 'CALL_HANGUP' || data.type === 'CALL_DECLINE') clientInfo.status = 'Online';
        if (data.type === 'PTT_START') clientInfo.status = 'Transmitting (PTT)';
        if (data.type === 'PTT_STOP') clientInfo.status = 'Online';

        // Forward signaling/control event to other peers
        data.senderId = clientInfo.id;
        data.senderName = clientInfo.nickname;
        data.deviceType = clientInfo.deviceType;
        const outMsg = JSON.stringify(data);

        for (const client of allWebSockets) {
          if (client !== ws && client.readyState === 1) {
            client.send(outMsg);
          }
        }
        if (data.type.startsWith('CALL_') || data.type.startsWith('PTT_')) {
          broadcastPeerList();
        }
      }
    } catch (e) {
      console.error('Error parsing WS message:', e);
    }
  });

  ws.on('close', () => {
    console.log(`[Mesh Radio] Disconnected: ${clientInfo.nickname} (${clientInfo.id})`);
    allWebSockets.delete(ws);
    clients.delete(ws);
    broadcastPeerList();
  });
}

// Keepalive Heartbeat Interval (Ping every 10 seconds to prevent NAT Carrier timeouts)
const keepAliveTimer = setInterval(() => {
  for (const ws of allWebSockets) {
    if (ws.isAlive === false) {
      console.log('[Heartbeat] Terminating inactive socket');
      allWebSockets.delete(ws);
      clients.delete(ws);
      return ws.terminate();
    }
    ws.isAlive = false;
    ws.ping();
  }
}, 10000);

wssHttp.on('connection', handleWsConnection);
if (wssHttps) {
  wssHttps.on('connection', handleWsConnection);
}

const os = require('os');

httpServer.listen(HTTP_PORT, '0.0.0.0', () => {
  const interfaces = os.networkInterfaces();
  const ips = [];
  for (const name in interfaces) {
    for (const iface of interfaces[name]) {
      if (iface.family === 'IPv4' && !iface.internal) {
        ips.push({ name, ip: iface.address });
      }
    }
  }

  console.log(`\n=============================================================`);
  console.log(`📡 [OFFLINE MESH LIVE AUDIO SERVER RUNNING]`);
  console.log(`💻 Laptop Browser URL:  http://localhost:${HTTP_PORT}`);
  ips.forEach(entry => {
    console.log(`📱 ${entry.name} URL:  https://${entry.ip}:${HTTPS_PORT} (or http://${entry.ip}:${HTTP_PORT})`);
  });
  console.log(`=============================================================\n`);
});

if (httpsServer) {
  httpsServer.listen(HTTPS_PORT, '0.0.0.0');
}
