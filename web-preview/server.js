const http = require('http');
const fs = require('fs');
const path = require('path');
const { WebSocketServer } = require('ws');

const PORT = 3000;
const FILE_PATH = path.join(__dirname, 'index.html');

// Create HTTP Server
const server = http.createServer((req, res) => {
  fs.readFile(FILE_PATH, (err, data) => {
    if (err) {
      res.writeHead(500, { 'Content-Type': 'text/plain' });
      res.end('Error loading preview');
    } else {
      res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
      res.end(data);
    }
  });
});

// Attach WebSocket Server
const wss = new WebSocketServer({ server });

let clients = new Map(); // ws -> clientInfo

function broadcastPeerList() {
  const peerList = Array.from(clients.values()).map(c => ({
    id: c.id,
    nickname: c.nickname,
    deviceType: c.deviceType,
    isLocal: false
  }));

  const msg = JSON.stringify({ type: 'PEER_LIST', peers: peerList });
  for (const client of wss.clients) {
    if (client.readyState === 1) { // OPEN
      client.send(msg);
    }
  }
}

wss.on('connection', (ws, req) => {
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
      for (const client of wss.clients) {
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

        for (const client of wss.clients) {
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
    clients.delete(ws);
    broadcastPeerList();
  });
});

server.listen(PORT, '0.0.0.0', () => {
  console.log(`\n=============================================================`);
  console.log(`📡 [OFFLINE MESH LIVE AUDIO SERVER RUNNING]`);
  console.log(`💻 Laptop Browser URL:  http://localhost:${PORT}`);
  console.log(`📱 Android Phone URL:   http://10.73.88.166:${PORT}`);
  console.log(`=============================================================\n`);
});
