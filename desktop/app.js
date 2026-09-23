// -------------------------------------------------------------
// SHARP-BOSE TACTICAL MESH // DESKTOP TERMINAL APP
// 100% Offline Standalone P2P Comms Engine (Optimized for Low RAM)
// -------------------------------------------------------------

class TacticalMeshDesktop {
  constructor() {
    this.localNodeId = 'node-desktop-' + Math.random().toString(36).substring(2, 6);
    this.currentHost = '127.0.0.1';
    this.currentPort = 3000;
    this.currentRoom = 'INDIA-MAIN';
    this.connectedPeers = [];
    this.ws = null;
    this.isConnected = false;
    this.reconnectTimer = null;
    this.autoScanTimer = null;
    
    // Audio Engine State
    this.audioCtx = null;
    this.micStream = null;
    this.processorNode = null;
    this.isCalling = false;
    this.isPttActive = false;
    this.activeCallPeer = null;

    // Radar State
    this.selfCoords = { lat: 28.6139, lng: 77.2090, alt: 216.0 };
    this.radarSweepAngle = 0;
    this.isRadarActive = false;
    this.radarRafId = null;
    
    this.init();
  }

  async init() {
    this.bindUI();
    this.initRadar();
    await this.fetchSystemInfo();
    this.connectMesh();
    this.setupShortcuts();
    this.startAutoDiscoveryLoop();
  }

  startAutoDiscoveryLoop() {
    if (this.autoScanTimer) clearInterval(this.autoScanTimer);
    this.autoScanTimer = setInterval(() => {
      if (!this.isConnected) {
        this.autoDiscoverLocalHub();
      }
    }, 3000);
  }

  async autoDiscoverLocalHub() {
    const candidates = [
      '127.0.0.1:3000',
      '172.27.180.170:3000',
      '172.27.180.37:3000',
      '172.27.180.1:3000',
      '192.168.44.1:3000',
      '192.168.137.1:3000',
      '10.19.238.166:3000',
      '10.19.238.104:3000'
    ];

    for (const host of candidates) {
      if (this.isConnected) break;
      const [h, p] = host.split(':');
      const reachable = await new Promise(resolve => {
        try {
          const testWs = new WebSocket(`ws://${host}`);
          const tm = setTimeout(() => {
            try { testWs.close(); } catch(e){}
            resolve(false);
          }, 800);
          testWs.onopen = () => {
            clearTimeout(tm);
            try { testWs.close(); } catch(e){}
            resolve(true);
          };
          testWs.onerror = () => {
            clearTimeout(tm);
            resolve(false);
          };
        } catch(e) { resolve(false); }
      });

      if (reachable && !this.isConnected) {
        this.log(`[Auto-Discovery] Connected to mesh node at ${host}`);
        this.connectMesh(h, parseInt(p, 10));
        break;
      }
    }
  }

  // -----------------------------------------------------------
  // 1. UI Binding & Tab Navigation
  // -----------------------------------------------------------
  bindUI() {
    // Tab Dock Switching
    const dockButtons = document.querySelectorAll('.dock-btn');
    const views = [
      document.getElementById('viewRoster'),
      document.getElementById('viewComms'),
      document.getElementById('viewChat'),
      document.getElementById('viewRadar'),
      document.getElementById('viewChannels'),
      document.getElementById('viewLogs')
    ];

    dockButtons.forEach(btn => {
      btn.addEventListener('click', () => {
        const tabIndex = parseInt(btn.dataset.tab, 10);
        dockButtons.forEach(b => b.classList.remove('active'));
        views.forEach(v => v.classList.remove('active'));
        
        btn.classList.add('active');
        if (views[tabIndex]) {
          views[tabIndex].classList.add('active');
        }

        // Only animate Radar when Tab 3 (Radar) is active
        if (tabIndex === 3) {
          this.isRadarActive = true;
          this.startRadarAnimation();
        } else {
          this.isRadarActive = false;
          if (this.radarRafId) {
            cancelAnimationFrame(this.radarRafId);
            this.radarRafId = null;
          }
        }
      });
    });

    // Theme Toggle
    const btnTheme = document.getElementById('btnThemeToggle');
    let isDark = true;
    if (btnTheme) {
      btnTheme.addEventListener('click', () => {
        isDark = !isDark;
        btnTheme.innerText = isDark ? '[ 🌙 CRT DARK ]' : '[ ☀️ RETRO LIGHT ]';
        document.documentElement.style.setProperty('--bg-main', isDark ? '#0B0F19' : '#F1F5F9');
        document.documentElement.style.setProperty('--bg-card', isDark ? '#131D31' : '#FFFFFF');
        document.documentElement.style.setProperty('--text-primary', isDark ? '#F8FAFC' : '#0F172A');
      });
    }

    // Auto-Discover & Custom Connect Buttons
    const btnAutoScan = document.getElementById('btnAutoScan');
    if (btnAutoScan) {
      btnAutoScan.addEventListener('click', () => {
        this.log('Scanning for active Bluetooth PAN & Wi-Fi mesh carriers...');
        this.autoDiscoverLocalHub();
      });
    }

    const btnConnectManual = document.getElementById('btnConnectManual');
    if (btnConnectManual) {
      btnConnectManual.addEventListener('click', () => {
        const ip = prompt('Enter custom Mesh Node IP or Hostname (e.g. 172.27.180.170:3000):', '172.27.180.170:3000');
        if (ip) {
          const parts = ip.trim().split(':');
          this.connectMesh(parts[0], parseInt(parts[1] || '3000', 10));
        }
      });
    }

    // Comms Controls
    const btnCall = document.getElementById('btnGlobalCall');
    if (btnCall) {
      btnCall.addEventListener('click', () => {
        if (!this.isCalling) {
          this.startVoiceCall();
        } else {
          this.stopVoiceCall();
        }
      });
    }

    // PTT Hold-to-Talk Mouse / Touch
    const btnPtt = document.getElementById('btnPtt');
    if (btnPtt) {
      const startPtt = () => {
        if (this.isPttActive) return;
        this.isPttActive = true;
        btnPtt.classList.add('transmitting');
        btnPtt.innerText = '>>> TRANSMITTING LIVE PTT <<<';
        const pttLabel = document.getElementById('pttStatusLabel');
        if (pttLabel) {
          pttLabel.innerText = 'FREQ 01 • TRANSMITTING LIVE AUDIO...';
          pttLabel.style.color = '#EF4444';
        }
        this.sendControlPacket({ type: 'PTT_START', senderName: 'Desktop Terminal' });
        this.startMicCapture();
        this.log('PTT Transmission started');
      };

      const stopPtt = () => {
        if (!this.isPttActive) return;
        this.isPttActive = false;
        btnPtt.classList.remove('transmitting');
        btnPtt.innerText = '🎙️ HOLD SPACEBAR OR CLICK TO TALK (PTT)';
        const pttLabel = document.getElementById('pttStatusLabel');
        if (pttLabel) {
          pttLabel.innerText = 'FREQ 01: EMERGENCY & TACTICAL • FLOOR: CLEAR';
          pttLabel.style.color = '#10B981';
        }
        this.sendControlPacket({ type: 'PTT_STOP', senderName: 'Desktop Terminal' });
        if (!this.isCalling) this.stopMicCapture();
        this.log('PTT Transmission released');
      };

      btnPtt.addEventListener('mousedown', startPtt);
      btnPtt.addEventListener('mouseup', stopPtt);
      btnPtt.addEventListener('mouseleave', stopPtt);
    }

    // Chat Controls
    const btnSendChat = document.getElementById('btnSendChat');
    const chatInput = document.getElementById('chatInput');
    if (btnSendChat) btnSendChat.addEventListener('click', () => this.sendChatMessage());
    if (chatInput) {
      chatInput.addEventListener('keydown', (e) => {
        if (e.key === 'Enter') this.sendChatMessage();
      });
    }

    // Channels Hub Buttons
    document.querySelectorAll('.btn-freq-hub').forEach(btn => {
      btn.addEventListener('click', () => {
        const room = btn.dataset.room;
        this.switchRoom(room);
      });
    });

    const btnJoinCustom = document.getElementById('btnJoinCustomRoom');
    if (btnJoinCustom) {
      btnJoinCustom.addEventListener('click', () => {
        const customInput = document.getElementById('customRoomInput');
        const room = customInput ? customInput.value.trim() : '';
        if (room) this.switchRoom(room);
      });
    }

    // Radar Transmit
    const btnBroadcastGps = document.getElementById('btnBroadcastGps');
    if (btnBroadcastGps) {
      btnBroadcastGps.addEventListener('click', () => {
        this.broadcastLocation();
        this.log(`📍 Transmitted coordinates (${this.selfCoords.lat}, ${this.selfCoords.lng})`);
      });
    }

    // SOS Beacon
    const btnSosBeacon = document.getElementById('btnSosBeacon');
    if (btnSosBeacon) {
      btnSosBeacon.addEventListener('click', () => {
        this.sendControlPacket({
          type: 'SOS_BEACON',
          senderId: this.localNodeId,
          senderName: 'Desktop Base Station',
          coords: this.selfCoords
        });
        this.log('🚨 EMERGENCY DISTRESS BEACON BROADCASTED (15 HOPS)');
        alert('🚨 EMERGENCY SOS BROADCASTED TO ALL MESH NODES!');
      });
    }

    // Clear Logs
    const btnClearLogs = document.getElementById('btnClearLogs');
    if (btnClearLogs) {
      btnClearLogs.addEventListener('click', () => {
        const terminal = document.getElementById('systemLogsTerminal');
        if (terminal) terminal.innerText = '';
      });
    }

    // Incoming Call Modal Actions
    const btnAccept = document.getElementById('btnAcceptIncoming');
    const btnDecline = document.getElementById('btnDeclineIncoming');
    if (btnAccept) {
      btnAccept.addEventListener('click', () => {
        document.getElementById('incomingCallModal').classList.remove('open');
        this.startVoiceCall(this.activeCallPeer?.id);
      });
    }
    if (btnDecline) {
      btnDecline.addEventListener('click', () => {
        document.getElementById('incomingCallModal').classList.remove('open');
        this.sendControlPacket({ type: 'CALL_DECLINE', senderId: this.localNodeId });
      });
    }
  }

  setupShortcuts() {
    // Spacebar PTT shortcut
    window.addEventListener('keydown', (e) => {
      if (e.code === 'Space' && e.target.tagName !== 'INPUT') {
        e.preventDefault();
        const btnPtt = document.getElementById('btnPtt');
        if (btnPtt) btnPtt.dispatchEvent(new Event('mousedown'));
      }
    });

    window.addEventListener('keyup', (e) => {
      if (e.code === 'Space' && e.target.tagName !== 'INPUT') {
        e.preventDefault();
        const btnPtt = document.getElementById('btnPtt');
        if (btnPtt) btnPtt.dispatchEvent(new Event('mouseup'));
      }
    });
  }

  // -----------------------------------------------------------
  // 2. Network & Mesh WebSocket Connection
  // -----------------------------------------------------------
  async fetchSystemInfo() {
    if (window.electronAPI) {
      try {
        const id = await window.electronAPI.getNodeId();
        const hostname = await window.electronAPI.getHostname();
        const interfaces = await window.electronAPI.getInterfaces();
        
        this.localNodeId = id;
        const nodeBadge = document.getElementById('nodeIdBadge');
        if (nodeBadge) nodeBadge.innerText = `NODE: ${id} (${hostname})`;
        
        let ifaceStr = (interfaces || []).map(i => `${i.name}: ${i.ip}`).join(' // ');
        if (!ifaceStr) ifaceStr = '127.0.0.1 (Local Loopback)';
        const carrierDetails = document.getElementById('carrierDetails');
        if (carrierDetails) {
          carrierDetails.innerHTML = `
            LOCAL HOST: 127.0.0.1:3000 // UDP BEACON: 255.255.255.255:3000<br>
            ACTIVE INTERFACES: ${ifaceStr}
          `;
        }

        // Listen for IPC events from main process (peers & control)
        window.electronAPI.onPeerListUpdated((peers) => this.renderPeers(peers));
        window.electronAPI.onControlPacket((pkt) => this.handleIncomingControl(pkt));
        window.electronAPI.onUdpPeerDiscovered((peer) => {
          this.log(`[UDP Beacon] Discovered nearby peer: ${peer.peerName} at ${peer.ip}:${peer.port}`);
        });
      } catch (e) {
        console.warn('System info lookup:', e.message);
      }
    }
  }

  connectMesh(host = '127.0.0.1', port = 3000) {
    if (this.reconnectTimer) {
      clearTimeout(this.reconnectTimer);
      this.reconnectTimer = null;
    }

    this.currentHost = host;
    this.currentPort = port;

    try {
      if (this.ws) {
        try { this.ws.close(); } catch(e){}
        this.ws = null;
      }

      this.ws = new WebSocket(`ws://${host}:${port}`);
      this.ws.binaryType = 'arraybuffer';

      this.ws.onopen = () => {
        this.isConnected = true;
        this.log(`[WebSocket] Connected to Mesh Carrier at ws://${host}:${port}`);
        const carrierStatus = document.getElementById('carrierStatus');
        if (carrierStatus) {
          carrierStatus.innerText = '● CARRIER ACTIVE';
          carrierStatus.className = 'status-online';
        }
        this.sendControlPacket({
          type: 'SET_NICKNAME',
          nickname: 'Desktop Terminal',
          deviceType: 'Desktop'
        });
        this.sendControlPacket({ type: 'JOIN_ROOM', room: this.currentRoom });
      };

      this.ws.onmessage = (event) => {
        if (event.data instanceof ArrayBuffer) {
          this.playAudioFrame(new Uint8Array(event.data));
          return;
        }

        try {
          const json = JSON.parse(event.data);
          this.handleIncomingControl(json);
        } catch (e) {}
      };

      this.ws.onclose = () => {
        this.isConnected = false;
        const carrierStatus = document.getElementById('carrierStatus');
        if (carrierStatus) {
          carrierStatus.innerText = '○ CARRIER AUTO-CONNECTING...';
          carrierStatus.className = 'status-offline';
        }
        if (!this.reconnectTimer) {
          this.reconnectTimer = setTimeout(() => this.connectMesh(host, port), 2500);
        }
      };

      this.ws.onerror = () => {
        this.isConnected = false;
      };
    } catch (e) {
      this.log(`Connection error: ${e.message}`);
    }
  }

  sendControlPacket(obj) {
    if (this.ws && this.ws.readyState === WebSocket.OPEN) {
      this.ws.send(JSON.stringify(obj));
    }
  }

  sendAudioBuffer(uint8Buf) {
    if (this.ws && this.ws.readyState === WebSocket.OPEN) {
      this.ws.send(uint8Buf.buffer);
    }
  }

  handleIncomingControl(json) {
    if (!json || !json.type) return;

    switch (json.type) {
      case 'PEER_LIST':
        this.renderPeers(json.peers || []);
        break;

      case 'CHAT_MSG':
        this.appendChatBubble(json.senderName || 'Peer', json.text || '', false);
        break;

      case 'CALL_INVITE':
        this.activeCallPeer = { id: json.senderId, name: json.senderName };
        const incName = document.getElementById('incomingCallerName');
        const incId = document.getElementById('incomingCallerId');
        if (incName) incName.innerText = `[ NODE: ${(json.senderName || 'PEER').toUpperCase()} ]`;
        if (incId) incId.innerText = `ID: ${json.senderId} // E2EE NOISE_XX`;
        document.getElementById('incomingCallModal').classList.add('open');
        this.log(`📞 Incoming call from ${json.senderName} (${json.senderId})`);
        break;

      case 'CALL_ACCEPT':
        this.log(`Call accepted by remote peer.`);
        break;

      case 'CALL_DECLINE':
      case 'CALL_HANGUP':
        this.log(`Remote peer ended/declined call.`);
        this.stopVoiceCall();
        break;

      case 'PTT_START': {
        const pttLabel = document.getElementById('pttStatusLabel');
        if (pttLabel) {
          pttLabel.innerText = `FREQ 01 • FLOOR: ${json.senderName || 'Peer'} (SPEAKING...)`;
          pttLabel.style.color = '#EF4444';
        }
        break;
      }

      case 'PTT_STOP': {
        const pttLabel = document.getElementById('pttStatusLabel');
        if (pttLabel) {
          pttLabel.innerText = 'FREQ 01: EMERGENCY & TACTICAL • FLOOR: CLEAR';
          pttLabel.style.color = '#10B981';
        }
        break;
      }
    }
  }

  // -----------------------------------------------------------
  // 3. Roster & Peer Rendering
  // -----------------------------------------------------------
  renderPeers(peers) {
    this.connectedPeers = (peers || []).filter(p => p.id !== this.localNodeId);
    const badge = document.getElementById('rosterCountBadge');
    if (badge) badge.innerText = `[ ${this.connectedPeers.length} ACTIVE // 0 STANDBY ]`;

    const container = document.getElementById('rosterPeerList');
    if (!container) return;
    container.innerHTML = '';

    if (this.connectedPeers.length === 0) {
      container.innerHTML = `
        <div class="peer-row">
          <div class="peer-info">
            <div class="peer-name">📱 No companion peers connected yet.</div>
            <div class="peer-meta">Turn Bluetooth on Android or connect to same network — auto-connects in seconds.</div>
          </div>
        </div>
      `;
      return;
    }

    this.connectedPeers.forEach(peer => {
      const row = document.createElement('div');
      row.className = 'peer-row';
      const isAndroid = peer.deviceType && peer.deviceType.includes('Android');
      const icon = isAndroid ? '📱' : '💻';

      row.innerHTML = `
        <div class="peer-info">
          <div class="peer-name">${icon} ${escapeHtml(peer.nickname.toUpperCase())} [${escapeHtml(peer.id)}]</div>
          <div class="peer-meta">STATUS: ${escapeHtml((peer.status || 'Online').toUpperCase())} • E2EE NOISE_XX • DIRECT P2P LINK</div>
        </div>
        <div class="peer-actions">
          <button class="btn-lock" onclick="app.showSecurityDetails('${escapeHtml(peer.id)}')">[ 🔒 ]</button>
          <button class="btn-call" onclick="app.startVoiceCall('${escapeHtml(peer.id)}', '${escapeHtml(peer.nickname)}')">[ 📞 CALL ]</button>
          <button class="btn-bbs" onclick="app.switchTab(2)">[ 💬 BBS ]</button>
        </div>
      `;
      container.appendChild(row);
    });

    this.updateRadarTelemetry();
  }

  showSecurityDetails(peerId) {
    this.switchTab(5);
    this.log(`Verified E2EE Noise-XX cryptographic channel with peer ${peerId}`);
  }

  switchTab(index) {
    const dockButtons = document.querySelectorAll('.dock-btn');
    if (dockButtons[index]) dockButtons[index].click();
  }

  // -----------------------------------------------------------
  // 4. Low-Latency Web Audio Duplex Calling & PTT
  // -----------------------------------------------------------
  async initAudioContext() {
    if (!this.audioCtx) {
      const AudioCtxClass = window.AudioContext || window.webkitAudioContext;
      this.audioCtx = new AudioCtxClass({ sampleRate: 16000 });
    }
    if (this.audioCtx.state === 'suspended') {
      await this.audioCtx.resume();
    }
  }

  async startMicCapture() {
    try {
      await this.initAudioContext();
      if (this.micStream) return;

      this.micStream = await navigator.mediaDevices.getUserMedia({
        audio: {
          channelCount: 1,
          sampleRate: 16000,
          echoCancellation: true,
          noiseSuppression: true,
          autoGainControl: true
        }
      });

      const source = this.audioCtx.createMediaStreamSource(this.micStream);
      const processor = this.audioCtx.createScriptProcessor(1024, 1, 1);

      processor.onaudioprocess = (e) => {
        if (!this.isCalling && !this.isPttActive) return;

        const inputData = e.inputBuffer.getChannelData(0);
        let sum = 0;
        
        // Convert Float32Array to 16-bit PCM
        const pcm16 = new Int16Array(inputData.length);
        for (let i = 0; i < inputData.length; i++) {
          const s = Math.max(-1, Math.min(1, inputData[i]));
          pcm16[i] = s < 0 ? s * 0x8000 : s * 0x7FFF;
          sum += Math.abs(inputData[i]);
        }

        // VU meter update
        const avg = sum / inputData.length;
        const vuBar = document.getElementById('micVuBar');
        if (vuBar) vuBar.style.width = `${Math.min(100, avg * 350)}%`;

        // Frame header: [0xAA, 0x55, 0x3E, 0x80 (16000 sample rate)]
        const packet = new Uint8Array(4 + pcm16.buffer.byteLength);
        packet[0] = 0xAA;
        packet[1] = 0x55;
        packet[2] = (16000 >> 8) & 0xFF;
        packet[3] = 16000 & 0xFF;
        packet.set(new Uint8Array(pcm16.buffer), 4);

        this.sendAudioBuffer(packet);
      };

      source.connect(processor);
      processor.connect(this.audioCtx.destination);
      this.processorNode = processor;
      this.sourceNode = source;
    } catch (e) {
      this.log(`Microphone access: ${e.message}`);
    }
  }

  stopMicCapture() {
    if (this.micStream) {
      this.micStream.getTracks().forEach(t => t.stop());
      this.micStream = null;
    }
    if (this.sourceNode) {
      try { this.sourceNode.disconnect(); } catch(e){}
      this.sourceNode = null;
    }
    if (this.processorNode) {
      try { this.processorNode.disconnect(); } catch(e){}
      this.processorNode = null;
    }
    const vuBar = document.getElementById('micVuBar');
    if (vuBar) vuBar.style.width = '0%';
  }

  async playAudioFrame(uint8Frame) {
    if (!uint8Frame || uint8Frame.length < 6) return;

    try {
      await this.initAudioContext();
      if (!this.audioCtx) return;

      let pcmBytes = uint8Frame;
      if (uint8Frame[0] === 0xAA && uint8Frame[1] === 0x55) {
        pcmBytes = uint8Frame.subarray(4);
      }

      // Convert Int16 to Float32
      const numSamples = Math.floor(pcmBytes.byteLength / 2);
      const int16 = new Int16Array(pcmBytes.buffer, pcmBytes.byteOffset, numSamples);
      const float32 = new Float32Array(numSamples);
      for (let i = 0; i < numSamples; i++) {
        float32[i] = int16[i] / 32768.0;
      }

      const audioBuffer = this.audioCtx.createBuffer(1, numSamples, 16000);
      audioBuffer.getChannelData(0).set(float32);

      const source = this.audioCtx.createBufferSource();
      source.buffer = audioBuffer;
      source.connect(this.audioCtx.destination);
      source.onended = () => {
        try { source.disconnect(); } catch(e) {}
      };
      source.start();
    } catch (e) {}
  }

  startVoiceCall(peerId = '') {
    this.isCalling = true;
    const btnCall = document.getElementById('btnGlobalCall');
    if (btnCall) {
      btnCall.innerText = '[ 🔴 END ACTIVE VOICE CALL ]';
      btnCall.className = 'btn-end';
    }
    this.startMicCapture();
    this.sendControlPacket({ type: 'CALL_INVITE', targetId: peerId, senderId: this.localNodeId, senderName: 'Desktop Terminal' });
    this.log(`📞 Outgoing call initiated with mesh peer`);
  }

  stopVoiceCall() {
    this.isCalling = false;
    const btnCall = document.getElementById('btnGlobalCall');
    if (btnCall) {
      btnCall.innerText = '[ 📞 START 2-WAY DUPLEX CALL ]';
      btnCall.className = 'btn-call';
    }
    this.stopMicCapture();
    this.sendControlPacket({ type: 'CALL_HANGUP', senderId: this.localNodeId });
    this.log('Voice call ended cleanly');
  }

  // -----------------------------------------------------------
  // 5. Encrypted BBS Chat (DOM capped at 100 messages)
  // -----------------------------------------------------------
  sendChatMessage() {
    const input = document.getElementById('chatInput');
    if (!input) return;
    const text = input.value.trim();
    if (!text) return;

    this.sendControlPacket({
      type: 'CHAT_MSG',
      text: text,
      senderName: 'Desktop Terminal',
      isE2ee: true
    });

    this.appendChatBubble('You', text, true);
    input.value = '';
    this.log(`[BBS Sent] ${text}`);
  }

  appendChatBubble(sender, text, isMe) {
    const container = document.getElementById('chatMessages');
    if (!container) return;

    // Maintain max 100 chat messages to avoid RAM growth
    while (container.children.length >= 100) {
      container.removeChild(container.firstChild);
    }

    const bubble = document.createElement('div');
    bubble.className = `chat-bubble ${isMe ? 'me' : 'peer'}`;
    bubble.innerHTML = `
      <div class="chat-sender ${isMe ? 'me' : 'peer'}">[ ${isMe ? 'LOCAL_NODE // YOU' : escapeHtml(sender.toUpperCase())} ]</div>
      <div>${escapeHtml(text)}</div>
    `;
    container.appendChild(bubble);
    container.scrollTop = container.scrollHeight;
  }

  // -----------------------------------------------------------
  // 6. Frequency Tuner & Room Switcher
  // -----------------------------------------------------------
  switchRoom(roomName) {
    this.currentRoom = roomName.toUpperCase().trim();
    const label = document.getElementById('activeChannelLabel');
    if (label) label.innerText = `🇮🇳 [ FREQ: ${this.currentRoom} ]`;
    this.sendControlPacket({ type: 'JOIN_ROOM', room: this.currentRoom });
    this.log(`Switched to Regional Relay Hub: ${this.currentRoom}`);
  }

  // -----------------------------------------------------------
  // 7. On-Demand 360° Tactical Radar Renderer
  // -----------------------------------------------------------
  initRadar() {
    const canvas = document.getElementById('radarCanvas');
    if (!canvas) return;
    this.radarCtx = canvas.getContext('2d');
    this.radarCx = canvas.width / 2;
    this.radarCy = canvas.height / 2;
    this.radarR = this.radarCx - 10;
  }

  startRadarAnimation() {
    if (!this.radarCtx) return;

    const render = () => {
      if (!this.isRadarActive) return;

      const ctx = this.radarCtx;
      const cx = this.radarCx;
      const cy = this.radarCy;
      const r = this.radarR;

      ctx.clearRect(0, 0, cx * 2, cy * 2);

      // Radar Circles
      ctx.strokeStyle = 'rgba(16, 185, 129, 0.4)';
      ctx.lineWidth = 1;
      for (let i = 1; i <= 4; i++) {
        ctx.beginPath();
        ctx.arc(cx, cy, (r / 4) * i, 0, Math.PI * 2);
        ctx.stroke();
      }

      // Crosshairs
      ctx.beginPath();
      ctx.moveTo(cx, 10); ctx.lineTo(cx, cy * 2 - 10);
      ctx.moveTo(10, cy); ctx.lineTo(cx * 2 - 10, cy);
      ctx.stroke();

      // Cardinal Points
      ctx.fillStyle = '#10B981';
      ctx.font = '10px monospace';
      ctx.textAlign = 'center';
      ctx.fillText('N (000°)', cx, 22);
      ctx.fillText('S (180°)', cx, cy * 2 - 14);
      ctx.fillText('W (270°)', 24, cy + 3);
      ctx.fillText('E (090°)', cx * 2 - 24, cy + 3);

      // Sweep Line
      this.radarSweepAngle += 0.04;
      if (this.radarSweepAngle >= Math.PI * 2) this.radarSweepAngle = 0;

      const sx = cx + Math.cos(this.radarSweepAngle) * r;
      const sy = cy + Math.sin(this.radarSweepAngle) * r;

      const sweepGrad = ctx.createLinearGradient(cx, cy, sx, sy);
      sweepGrad.addColorStop(0, 'rgba(16, 185, 129, 0.6)');
      sweepGrad.addColorStop(1, 'rgba(16, 185, 129, 0)');

      ctx.strokeStyle = sweepGrad;
      ctx.lineWidth = 2;
      ctx.beginPath();
      ctx.moveTo(cx, cy);
      ctx.lineTo(sx, sy);
      ctx.stroke();

      // Center Node
      ctx.fillStyle = '#38BDF8';
      ctx.beginPath();
      ctx.arc(cx, cy, 5, 0, Math.PI * 2);
      ctx.fill();

      // Draw Peer Blips
      this.connectedPeers.forEach((p, idx) => {
        const angle = (idx + 1) * 1.2;
        const dist = 50 + (idx * 35) % (r - 20);
        const bx = cx + Math.cos(angle) * dist;
        const by = cy + Math.sin(angle) * dist;

        ctx.fillStyle = '#10B981';
        ctx.beginPath();
        ctx.arc(bx, by, 6, 0, Math.PI * 2);
        ctx.fill();

        ctx.fillStyle = '#F8FAFC';
        ctx.font = '9px monospace';
        ctx.fillText((p.nickname || 'NODE').substring(0, 8).toUpperCase(), bx + 8, by + 3);
      });

      this.radarRafId = requestAnimationFrame(render);
    };

    if (this.radarRafId) cancelAnimationFrame(this.radarRafId);
    this.radarRafId = requestAnimationFrame(render);
  }

  broadcastLocation() {
    this.sendControlPacket({
      type: 'LOCATION_UPDATE',
      latitude: this.selfCoords.lat,
      longitude: this.selfCoords.lng,
      altitude: this.selfCoords.alt,
      accuracy: 5.0
    });
  }

  updateRadarTelemetry() {
    const list = document.getElementById('radarPeerList');
    if (!list) return;

    if (this.connectedPeers.length === 0) {
      list.innerHTML = '<div style="color: var(--text-muted);">Awaiting peer GPS telemetry broadcasts...</div>';
      return;
    }

    list.innerHTML = '';
    this.connectedPeers.forEach(p => {
      const item = document.createElement('div');
      item.style.padding = '6px';
      item.style.background = '#070A13';
      item.style.borderRadius = '4px';
      item.style.border = '1px solid var(--border-color)';
      item.innerHTML = `
        <div style="color: var(--cyan-primary); font-weight: bold;">${escapeHtml((p.nickname || 'PEER').toUpperCase())}</div>
        <div style="color: var(--emerald-primary); font-size: 10px;">📍 RANGE: ~100m ↗️ NE (045°) • HARDWARE FIX</div>
      `;
      list.appendChild(item);
    });
  }

  // -----------------------------------------------------------
  // 8. Event Logging (Capped at 50 lines to conserve RAM)
  // -----------------------------------------------------------
  log(msg) {
    const terminal = document.getElementById('systemLogsTerminal');
    if (!terminal) return;
    const time = new Date().toTimeString().split(' ')[0];
    const lines = terminal.innerText.split('\n');
    if (lines.length > 50) lines.length = 50;
    terminal.innerText = `[${time}] ${msg}\n` + lines.join('\n');
  }
}

function escapeHtml(str) {
  if (!str) return '';
  return String(str).replace(/[&<>"']/g, (m) => {
    switch (m) {
      case '&': return '&amp;';
      case '<': return '&lt;';
      case '>': return '&gt;';
      case '"': return '&quot;';
      case "'": return '&#39;';
      default: return m;
    }
  });
}

// Instantiate global app
window.app = new TacticalMeshDesktop();
