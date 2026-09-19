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
  const isMobile = /Android|iPhone|iPad/i.test(req.headers['user-agent'] || '');
  const clientInfo = {
    id: 'node-' + Math.random().toString(36).substring(2, 7),
    nickname: isMobile ? 'Android Phone' : 'Laptop',
    deviceType: isMobile ? 'Android' : 'Laptop',
    ws: ws
  };

  clients.set(ws, clientInfo);
  console.log(`[Mesh Radio] Connected: ${clientInfo.nickname} (${clientInfo.id}) from ${req.socket.remoteAddress}`);

  // Send assigned client info to self
  ws.send(JSON.stringify({ type: 'ASSIGN_ID', id: clientInfo.id, nickname: clientInfo.nickname }));

  // Notify everyone of updated peer list
  broadcastPeerList();

  ws.on('message', (message, isBinary) => {
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

      if (data.type === 'SET_NICKNAME') {
        clientInfo.nickname = data.nickname;
        broadcastPeerList();
      } else if (data.type === 'CALL_INVITE' || data.type === 'CALL_ACCEPT' || data.type === 'CALL_DECLINE' || data.type === 'CALL_HANGUP' || data.type === 'PTT_START' || data.type === 'PTT_STOP' || data.type === 'CHAT_MSG' || data.type === 'SIGNAL_OFFER' || data.type === 'SIGNAL_ANSWER' || data.type === 'SIGNAL_CANDIDATE') {
        // Forward signaling/control event to other peers
        data.senderId = clientInfo.id;
        data.senderName = clientInfo.nickname;
        const outMsg = JSON.stringify(data);

        for (const client of allWebSockets) {
          if (client !== ws && client.readyState === 1) {
            client.send(outMsg);
          }
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

wssHttp.on('connection', handleWsConnection);
if (wssHttps) {
  wssHttps.on('connection', handleWsConnection);
}

httpServer.listen(HTTP_PORT, '0.0.0.0', () => {
  console.log(`\n=============================================================`);
  console.log(`📡 [OFFLINE MESH LIVE AUDIO SERVER RUNNING]`);
  console.log(`💻 Laptop Browser URL:  http://localhost:${HTTP_PORT}`);
  console.log(`📱 Android Phone HTTP:  http://10.73.88.166:${HTTP_PORT}`);
  if (httpsServer) {
    httpsServer.listen(HTTPS_PORT, '0.0.0.0', () => {
      console.log(`🔒 Android Phone HTTPS: https://10.73.88.166:${HTTPS_PORT} (Enables Mobile Mic)`);
      console.log(`=============================================================\n`);
    });
  } else {
    console.log(`=============================================================\n`);
  }
});
