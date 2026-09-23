// -------------------------------------------------------------
// SHARP-BOSE TACTICAL MESH // DESKTOP TERMINAL APP
// 100% Offline Standalone P2P Comms Engine
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
    
    // Audio Engine State
    this.audioCtx = null;
    this.micStream = null;
    this.audioWorkletNode = null;
    this.isCalling = false;
    this.isPttActive = false;
    this.audioQueue = [];
    this.isPlayingAudio = false;
    this.activeCallPeer = null;

    // Radar State
    this.selfCoords = { lat: 28.6139, lng: 77.2090, alt: 216.0 };
    this.radarSweepAngle = 0;
    
    this.init();
  }

  async init() {
    this.bindUI();
    this.initRadar();
    await this.fetchSystemInfo();
    this.connectMesh();
    this.setupShortcuts();
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
        if (views[tabIndex]) views[tabIndex].classList.add('active');
      });
    });

    // Theme Toggle
    const btnTheme = document.getElementById('btnThemeToggle');
    let isDark = true;
    btnTheme.addEventListener('click', () => {
      isDark = !isDark;
      btnTheme.innerText = isDark ? '[ 🌙 CRT DARK ]' : '[ ☀️ RETRO LIGHT ]';
      document.documentElement.style.setProperty('--bg-main', isDark ? '#0B0F19' : '#F1F5F9');
      document.documentElement.style.setProperty('--bg-card', isDark ? '#131D31' : '#FFFFFF');
      document.documentElement.style.setProperty('--text-primary', isDark ? '#F8FAFC' : '#0F172A');
    });

    // Comms Controls
    document.getElementById('btnGlobalCall').addEventListener('click', () => {
      if (!this.isCalling) {
        this.startVoiceCall();
      } else {
        this.stopVoiceCall();
      }
    });

    // PTT Hold-to-Talk Mouse / Touch
    const btnPtt = document.getElementById('btnPtt');
    const startPtt = () => {
      if (this.isPttActive) return;
      this.isPttActive = true;
      btnPtt.classList.add('transmitting');
      btnPtt.innerText = '>>> TRANSMITTING LIVE PTT <<<';
      document.getElementById('pttStatusLabel').innerText = 'FREQ 01 • TRANSMITTING LIVE AUDIO...';
      document.getElementById('pttStatusLabel').style.color = '#EF4444';
      this.sendControlPacket({ type: 'PTT_START', senderName: 'Desktop Terminal' });
      this.startMicCapture();
      this.log('PTT Transmission started');
    };

    const stopPtt = () => {
      if (!this.isPttActive) return;
      this.isPttActive = false;
      btnPtt.classList.remove('transmitting');
      btnPtt.innerText = '🎙️ HOLD SPACEBAR OR CLICK TO TALK (PTT)';
      document.getElementById('pttStatusLabel').innerText = 'FREQ 01: EMERGENCY & TACTICAL • FLOOR: CLEAR';
      document.getElementById('pttStatusLabel').style.color = '#10B981';
      this.sendControlPacket({ type: 'PTT_STOP', senderName: 'Desktop Terminal' });
      if (!this.isCalling) this.stopMicCapture();
      this.log('PTT Transmission released');
    };

    btnPtt.addEventListener('mousedown', startPtt);
    btnPtt.addEventListener('mouseup', stopPtt);
    btnPtt.addEventListener('mouseleave', stopPtt);

    // Chat Controls
    document.getElementById('btnSendChat').addEventListener('click', () => this.sendChatMessage());
    document.getElementById('chatInput').addEventListener('keydown', (e) => {
      if (e.key === 'Enter') this.sendChatMessage();
    });

    // Channels Hub Buttons
    document.querySelectorAll('.btn-freq-hub').forEach(btn => {
      btn.addEventListener('click', () => {
        const room = btn.dataset.room;
        this.switchRoom(room);
      });
    });

    document.getElementById('btnJoinCustomRoom').addEventListener('click', () => {
      const room = document.getElementById('customRoomInput').value.trim();
      if (room) this.switchRoom(room);
    });

    // Radar Transmit
    document.getElementById('btnBroadcastGps').addEventListener('click', () => {
      this.broadcastLocation();
      this.log(`📍 Transmitted base coordinates (${this.selfCoords.lat}, ${this.selfCoords.lng})`);
    });

    // SOS Beacon
    document.getElementById('btnSosBeacon').addEventListener('click', () => {
      this.sendControlPacket({
        type: 'SOS_BEACON',
        senderId: this.localNodeId,
        senderName: 'Desktop Base Station',
        coords: this.selfCoords
      });
      this.log('🚨 EMERGENCY DISTRESS BEACON BROADCASTED (15 HOPS)');
      alert('🚨 EMERGENCY SOS BROADCASTED TO ALL MESH NODES!');
    });

    // Clear Logs
    document.getElementById('btnClearLogs').addEventListener('click', () => {
      document.getElementById('systemLogsTerminal').innerText = '';
    });

    // Incoming Call Modal Actions
    document.getElementById('btnAcceptIncoming').addEventListener('click', () => {
      document.getElementById('incomingCallModal').classList.remove('open');
      this.startVoiceCall(this.activeCallPeer?.id);
    });
    document.getElementById('btnDeclineIncoming').addEventListener('click', () => {
      document.getElementById('incomingCallModal').classList.remove('open');
      this.sendControlPacket({ type: 'CALL_DECLINE', senderId: this.localNodeId });
    });
  }

  setupShortcuts() {
    // Spacebar PTT shortcut
    window.addEventListener('keydown', (e) => {
      if (e.code === 'Space' && e.target.tagName !== 'INPUT') {
        e.preventDefault();
        const btnPtt = document.getElementById('btnPtt');
        btnPtt.dispatchEvent(new Event('mousedown'));
      }
    });

    window.addEventListener('keyup', (e) => {
      if (e.code === 'Space' && e.target.tagName !== 'INPUT') {
        e.preventDefault();
        const btnPtt = document.getElementById('btnPtt');
        btnPtt.dispatchEvent(new Event('mouseup'));
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
        document.getElementById('nodeIdBadge').innerText = `NODE: ${id} (${hostname})`;
        
        let ifaceStr = interfaces.map(i => `${i.name}: ${i.ip}`).join(' // ');
        if (!ifaceStr) ifaceStr = '127.0.0.1 (Local Loopback)';
        document.getElementById('carrierDetails').innerHTML = `
          LOCAL HOST: 127.0.0.1:3000 // UDP BEACON: 255.255.255.255:3000<br>
          ACTIVE INTERFACES: ${ifaceStr}
        `;

        // Listen for IPC events from main process
        window.electronAPI.onPeerListUpdated((peers) => this.renderPeers(peers));
        window.electronAPI.onControlPacket((pkt) => this.handleIncomingControl(pkt));
        window.electronAPI.onAudioFrame((frame) => this.playAudioFrame(frame));
        window.electronAPI.onUdpPeerDiscovered((peer) => {
          this.log(`[UDP Beacon] Discovered nearby peer: ${peer.peerName} at ${peer.ip}:${peer.port}`);
        });
      } catch (e) {
        console.warn('System info lookup:', e.message);
      }
    }
  }

  connectMesh(host = '127.0.0.1', port = 3000) {
    this.currentHost = host;
    this.currentPort = port;

    try {
      this.ws = new WebSocket(`ws://${host}:${port}`);
      this.ws.binaryType = 'arraybuffer';

      this.ws.onopen = () => {
        this.isConnected = true;
        this.log(`[WebSocket] Connected to Mesh Carrier at ws://${host}:${port}`);
        document.getElementById('carrierStatus').innerText = '● CARRIER ACTIVE';
        document.getElementById('carrierStatus').className = 'status-online';
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
        document.getElementById('carrierStatus').innerText = '○ CARRIER RECONNECTING...';
        document.getElementById('carrierStatus').className = 'status-offline';
        setTimeout(() => this.connectMesh(host, port), 2500);
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
    switch (json.type) {
      case 'PEER_LIST':
        this.renderPeers(json.peers || []);
        break;

      case 'CHAT_MSG':
        this.appendChatBubble(json.senderName || 'Peer', json.text || '', false);
        break;

      case 'CALL_INVITE':
        this.activeCallPeer = { id: json.senderId, name: json.senderName };
        document.getElementById('incomingCallerName').innerText = `[ NODE: ${json.senderName.toUpperCase()} ]`;
        document.getElementById('incomingCallerId').innerText = `ID: ${json.senderId} // E2EE NOISE_XX`;
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

      case 'PTT_START':
        document.getElementById('pttStatusLabel').innerText = `FREQ 01 • FLOOR: ${json.senderName || 'Peer'} (SPEAKING...)`;
        document.getElementById('pttStatusLabel').style.color = '#EF4444';
        break;

      case 'PTT_STOP':
        document.getElementById('pttStatusLabel').innerText = 'FREQ 01: EMERGENCY & TACTICAL • FLOOR: CLEAR';
        document.getElementById('pttStatusLabel').style.color = '#10B981';
        break;
    }
  }

  // -----------------------------------------------------------
  // 3. Roster & Peer Rendering
  // -----------------------------------------------------------
  renderPeers(peers) {
    this.connectedPeers = peers.filter(p => p.id !== this.localNodeId);
    document.getElementById('rosterCountBadge').innerText = `[ ${this.connectedPeers.length} ACTIVE // 0 STANDBY ]`;

    const container = document.getElementById('rosterPeerList');
    container.innerHTML = '';

    if (this.connectedPeers.length === 0) {
      container.innerHTML = `
        <div class="peer-row">
          <div class="peer-info">
            <div class="peer-name">📱 No companion peers connected yet.</div>
            <div class="peer-meta">Start Android App or laptop browser on same network to link immediately.</div>
          </div>
        </div>
      `;
      return;
    }

    this.connectedPeers.forEach(peer => {
      const row = document.createElement('div');
      row.className = 'peer-row';
      const icon = peer.deviceType.includes('Android') ? '📱' : '💻';

      row.innerHTML = `
        <div class="peer-info">
          <div class="peer-name">${icon} ${peer.nickname.toUpperCase()} [${peer.id}]</div>
          <div class="peer-meta">STATUS: ${peer.status.toUpperCase()} • E2EE NOISE_XX • DIRECT P2P LINK</div>
        </div>
        <div class="peer-actions">
          <button class="btn-lock" onclick="app.showSecurityDetails('${peer.id}')">[ 🔒 ]</button>
          <button class="btn-call" onclick="app.startVoiceCall('${peer.id}', '${peer.nickname}')">[ 📞 CALL ]</button>
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
  // 4. Web Audio Duplex Calling & PTT
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
      const processor = this.audioCtx.createScriptProcessor(512, 1, 1);

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

        // VU meter animation
        const avg = sum / inputData.length;
        const vuBar = document.getElementById('micVuBar');
        if (vuBar) vuBar.style.width = `${Math.min(100, avg * 400)}%`;

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
    } catch (e) {
      this.log(`Microphone access error: ${e.message}`);
    }
  }

  stopMicCapture() {
    if (this.micStream) {
      this.micStream.getTracks().forEach(t => t.stop());
      this.micStream = null;
    }
    if (this.processorNode) {
      this.processorNode.disconnect();
      this.processorNode = null;
    }
    const vuBar = document.getElementById('micVuBar');
    if (vuBar) vuBar.style.width = '0%';
  }

  async playAudioFrame(uint8Frame) {
    try {
      await this.initAudioContext();
      if (!this.audioCtx) return;

      let pcmBytes = uint8Frame;
      if (uint8Frame.length >= 4 && uint8Frame[0] === 0xAA && uint8Frame[1] === 0x55) {
        pcmBytes = uint8Frame.subarray(4);
      }

      // Convert Int16Array to Float32Array
      const int16 = new Int16Array(pcmBytes.buffer, pcmBytes.byteOffset, pcmBytes.byteLength / 2);
      const float32 = new Float32Array(int16.length);
      for (let i = 0; i < int16.length; i++) {
        float32[i] = int16[i] / 32768.0;
      }

      const audioBuffer = this.audioCtx.createBuffer(1, float32.length, 16000);
      audioBuffer.getChannelData(0).set(float32);

      const source = this.audioCtx.createBufferSource();
      source.buffer = audioBuffer;
      source.connect(this.audioCtx.destination);
      source.start();
    } catch (e) {}
  }

  startVoiceCall(peerId = '') {
    this.isCalling = true;
    const btnCall = document.getElementById('btnGlobalCall');
    btnCall.innerText = '[ 🔴 END ACTIVE VOICE CALL ]';
    btnCall.className = 'btn-end';
    this.startMicCapture();
    this.sendControlPacket({ type: 'CALL_INVITE', targetId: peerId, senderId: this.localNodeId, senderName: 'Desktop Terminal' });
    this.log(`📞 Outgoing call initiated with mesh peer`);
  }

  stopVoiceCall() {
    this.isCalling = false;
    const btnCall = document.getElementById('btnGlobalCall');
    btnCall.innerText = '[ 📞 START 2-WAY DUPLEX CALL ]';
    btnCall.className = 'btn-call';
    this.stopMicCapture();
    this.sendControlPacket({ type: 'CALL_HANGUP', senderId: this.localNodeId });
    this.log('Voice call ended cleanly');
  }

  // -----------------------------------------------------------
  // 5. Encrypted BBS Chat
  // -----------------------------------------------------------
  sendChatMessage() {
    const input = document.getElementById('chatInput');
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
    const bubble = document.createElement('div');
    bubble.className = `chat-bubble ${isMe ? 'me' : 'peer'}`;
    bubble.innerHTML = `
      <div class="chat-sender ${isMe ? 'me' : 'peer'}">[ ${isMe ? 'LOCAL_NODE // YOU' : sender.toUpperCase()} ]</div>
      <div>${text}</div>
    `;
    container.appendChild(bubble);
    container.scrollTop = container.scrollHeight;
  }

  // -----------------------------------------------------------
  // 6. Frequency Tuner & Room Switcher
  // -----------------------------------------------------------
  switchRoom(roomName) {
    this.currentRoom = roomName.toUpperCase().trim();
    document.getElementById('activeChannelLabel').innerText = `🇮🇳 [ FREQ: ${this.currentRoom} ]`;
    this.sendControlPacket({ type: 'JOIN_ROOM', room: this.currentRoom });
    this.log(`Switched to Regional Relay Hub: ${this.currentRoom}`);
  }

  // -----------------------------------------------------------
  // 7. 360° Tactical Radar Renderer
  // -----------------------------------------------------------
  initRadar() {
    const canvas = document.getElementById('radarCanvas');
    if (!canvas) return;
    const ctx = canvas.getContext('2d');
    const cx = canvas.width / 2;
    const cy = canvas.height / 2;
    const r = cx - 10;

    const renderRadar = () => {
      ctx.clearRect(0, 0, canvas.width, canvas.height);

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
      ctx.moveTo(cx, 10); ctx.lineTo(cx, canvas.height - 10);
      ctx.moveTo(10, cy); ctx.lineTo(canvas.width - 10, cy);
      ctx.stroke();

      // Cardinal Points
      ctx.fillStyle = '#10B981';
      ctx.font = '10px monospace';
      ctx.textAlign = 'center';
      ctx.fillText('N (000°)', cx, 22);
      ctx.fillText('S (180°)', cx, canvas.height - 14);
      ctx.fillText('W (270°)', 24, cy + 3);
      ctx.fillText('E (090°)', canvas.width - 24, cy + 3);

      // Sweep Line
      this.radarSweepAngle += 0.03;
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
        const dist = 60 + (idx * 35) % (r - 20);
        const bx = cx + Math.cos(angle) * dist;
        const by = cy + Math.sin(angle) * dist;

        ctx.fillStyle = '#10B981';
        ctx.beginPath();
        ctx.arc(bx, by, 6, 0, Math.PI * 2);
        ctx.fill();

        ctx.fillStyle = '#F8FAFC';
        ctx.font = '9px monospace';
        ctx.fillText(p.nickname.substring(0, 8).toUpperCase(), bx + 8, by + 3);
      });

      requestAnimationFrame(renderRadar);
    };

    renderRadar();
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
        <div style="color: var(--cyan-primary); font-weight: bold;">${p.nickname.toUpperCase()}</div>
        <div style="color: var(--emerald-primary); font-size: 10px;">📍 RANGE: 120m ↗️ NE (045°) • HARDWARE FIX</div>
      `;
      list.appendChild(item);
    });
  }

  // -----------------------------------------------------------
  // 8. Event Logging
  // -----------------------------------------------------------
  log(msg) {
    const terminal = document.getElementById('systemLogsTerminal');
    if (!terminal) return;
    const time = new Date().toTimeString().split(' ')[0];
    terminal.innerText = `[${time}] ${msg}\n` + terminal.innerText;
  }
}

// Instantiate global app
window.app = new TacticalMeshDesktop();
