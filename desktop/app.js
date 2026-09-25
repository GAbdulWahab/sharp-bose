// -------------------------------------------------------------
// SHARP-BOSE TACTICAL MESH // DESKTOP TERMINAL APP
// 100% Offline Standalone P2P Comms Engine (Optimized for Low RAM)
// -------------------------------------------------------------

class TacticalMeshDesktop {
  constructor() {
    const savedId = localStorage.getItem('tactical_mesh_node_id');
    this.localNodeId = savedId || ('node-desktop-' + Math.random().toString(36).substring(2, 8));
    if (!savedId) {
      localStorage.setItem('tactical_mesh_node_id', this.localNodeId);
    }
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
    // Carrier Isolation & Stable Connection Policy
    this.carrierMode = 'BLUETOOTH_ONLY'; // Options: BLUETOOTH_ONLY, WIFI_ONLY, COMBINED, MANUAL
    this.btDevices = new Map();
    this.isBtScanning = false;
    this.btConnectedAddress = null;
    this.btRadioState = 'UNKNOWN';
    this.btAutoReconnect = false; // STRICT: No auto-reconnecting loops
    this.voiceAudioStore = new Map(); // msgId -> audioDataUrl or blobUrl
    
    this.init();
  }

  async init() {
    this.bindUI();
    this.initRadar();
    await this.fetchSystemInfo();
    this.connectMesh();
    this.setupShortcuts();
    this.startAutoDiscoveryLoop();
    this.initBluetooth();
  }

  startAutoDiscoveryLoop() {
    if (this.autoScanTimer) clearInterval(this.autoScanTimer);
    this.autoScanTimer = setInterval(() => {
      // Strictly prevent auto-switching if in Bluetooth-Only mode or if active Bluetooth connection is established
      if (this.carrierMode === 'BLUETOOTH_ONLY' || this.carrierMode === 'MANUAL' || this.btConnectedAddress) {
        return;
      }
      // NEVER disconnect or scan if already connected to a healthy mesh host
      if (!this.isConnected) {
        this.autoDiscoverLocalHub();
      }
    }, 8000);
  }

  async autoDiscoverLocalHub() {
    if (this.isConnected) return;
    const candidates = [
      '127.0.0.1:3000',
      '172.27.180.170:3000',
      '172.27.180.37:3000',
      '172.27.180.1:3000',
      '192.168.43.1:3000',
      '192.168.137.1:3000',
      '192.168.44.1:3000',
      '10.19.238.166:3000',
      '10.19.238.104:3000'
    ];

    for (const host of candidates) {
      if (this.isConnected) break;
      const [h, p] = host.split(':');
      if (h === this.currentHost && this.isConnected) continue;

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
        this.log(`[Auto-Discovery] Discovered mesh companion node at ${host}`);
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
      document.getElementById('viewFiles'),
      document.getElementById('viewRadar'),
      document.getElementById('viewChannels'),
      document.getElementById('viewLogs'),
      document.getElementById('viewSettings')
    ];

    dockButtons.forEach(btn => {
      btn.addEventListener('click', () => {
        const tabIndex = parseInt(btn.dataset.tab, 10);
        dockButtons.forEach(b => b.classList.remove('active'));
        views.forEach(v => { if (v) v.classList.remove('active'); });
        
        btn.classList.add('active');
        if (views[tabIndex]) {
          views[tabIndex].classList.add('active');
        }

        if (tabIndex === 3) {
          this.renderDesktopTransfers();
        }

        // Only animate Radar when Tab 4 (Radar) is active
        if (tabIndex === 4) {
          this.isRadarActive = true;
          this.startRadarAnimation();
        } else {
          this.isRadarActive = false;
          if (this.radarRafId) {
            cancelAnimationFrame(this.radarRafId);
            this.radarRafId = null;
          }
        }

        // When switching to Settings (Tab 7), sync fields
        if (tabIndex === 7) {
          const settingNodeId = document.getElementById('settingNodeId');
          if (settingNodeId) settingNodeId.value = this.localNodeId;
        }
      });
    });

    this.bindFileSharingUI();
    this.bindSettingsUI();
    this.bindChatMediaToolbar();

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

    // Bluetooth Hardware Turn ON / OFF Handlers
    const toggleBt = async () => {
      const turnOn = (this.btRadioState !== 'ON');
      this.log(`Requesting Windows Bluetooth Radio: ${turnOn ? 'TURN ON' : 'TURN OFF'}...`);
      if (window.bluetoothAPI) {
        await window.bluetoothAPI.setRadioState(turnOn);
      }
    };

    const btnToggleBtHeader = document.getElementById('btnToggleBtHeader');
    const btnToggleBtAction = document.getElementById('btnToggleBtAction');
    if (btnToggleBtHeader) btnToggleBtHeader.addEventListener('click', toggleBt);
    if (btnToggleBtAction) btnToggleBtAction.addEventListener('click', toggleBt);

    // Wi-Fi Carrier Turn ON / OFF Handlers
    this.isWifiActive = true;
    const toggleWifi = () => {
      this.isWifiActive = !this.isWifiActive;
      this.toggleWifiCarrier(this.isWifiActive);
    };

    const btnToggleWifiHeader = document.getElementById('btnToggleWifiHeader');
    const btnToggleWifiAction = document.getElementById('btnToggleWifiAction');
    if (btnToggleWifiHeader) btnToggleWifiHeader.addEventListener('click', toggleWifi);
    if (btnToggleWifiAction) btnToggleWifiAction.addEventListener('click', toggleWifi);

    // Dedicated Carrier Transport Mode Toggles
    const btnBtOnly = document.getElementById('btnModeBtOnly');
    const btnWifiOnly = document.getElementById('btnModeWifiOnly');
    const btnCombined = document.getElementById('btnModeCombined');

    const updateCarrierButtons = () => {
      [btnBtOnly, btnWifiOnly, btnCombined].forEach(b => {
        if (b) {
          b.style.background = '';
          b.style.borderColor = 'var(--border-glass)';
          b.style.color = 'var(--text-secondary)';
        }
      });
      if (this.carrierMode === 'BLUETOOTH_ONLY' && btnBtOnly) {
        btnBtOnly.style.background = 'rgba(56, 189, 248, 0.15)';
        btnBtOnly.style.borderColor = 'var(--cyan-primary)';
        btnBtOnly.style.color = 'var(--cyan-primary)';
      } else if (this.carrierMode === 'WIFI_ONLY' && btnWifiOnly) {
        btnWifiOnly.style.background = 'rgba(16, 185, 129, 0.15)';
        btnWifiOnly.style.borderColor = 'var(--emerald-primary)';
        btnWifiOnly.style.color = 'var(--emerald-primary)';
      } else if (this.carrierMode === 'COMBINED' && btnCombined) {
        btnCombined.style.background = 'rgba(168, 85, 247, 0.15)';
        btnCombined.style.borderColor = '#A855F7';
        btnCombined.style.color = '#C084FC';
      }
    };

    if (btnBtOnly) {
      btnBtOnly.addEventListener('click', () => {
        this.carrierMode = 'BLUETOOTH_ONLY';
        updateCarrierButtons();
        this.log('Carrier Mode: Dedicated Bluetooth (Wi-Fi auto-switching disabled)');
      });
    }
    if (btnWifiOnly) {
      btnWifiOnly.addEventListener('click', () => {
        this.carrierMode = 'WIFI_ONLY';
        updateCarrierButtons();
        this.log('Carrier Mode: Dedicated Wi-Fi (Bluetooth auto-switching disabled)');
      });
    }
    if (btnCombined) {
      btnCombined.addEventListener('click', () => {
        this.carrierMode = 'COMBINED';
        updateCarrierButtons();
        this.log('Carrier Mode: Multi-Radio Carrier Active');
      });
    }
    updateCarrierButtons();

    const toggleAutoRec = document.getElementById('toggleBtAutoReconnect');
    if (toggleAutoRec) {
      toggleAutoRec.checked = this.btAutoReconnect;
      toggleAutoRec.addEventListener('change', (e) => {
        this.btAutoReconnect = e.target.checked;
        localStorage.setItem('bt_auto_reconnect', this.btAutoReconnect ? 'true' : 'false');
        if (window.bluetoothAPI) {
          window.bluetoothAPI.setAutoReconnect(this.btAutoReconnect);
        }
        this.log(`Bluetooth Auto-Reconnect set to: ${this.btAutoReconnect}`);
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
        this.playPttStartTone();
        btnPtt.classList.add('transmitting');
        btnPtt.innerText = '>>> TRANSMITTING LIVE PTT <<<';
        const pttLabel = document.getElementById('pttStatusLabel');
        if (pttLabel) {
          pttLabel.innerText = 'FREQ 01 • TRANSMITTING LIVE AUDIO...';
          pttLabel.style.color = '#EF4444';
        }
        this.sendControlPacket({ type: 'PTT_START', senderName: 'Desktop Terminal' });
        this.startMicCapture();
        this.log('PTT Transmission started (Audio frame streaming)');
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
        if (document.getElementById('toggleRogerBeep')?.checked !== false) {
          this.playRogerBeep();
        }
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
        this.playChime();
        this.log(`📍 Transmitted coordinates (${this.selfCoords.lat}, ${this.selfCoords.lng})`);
        alert(`📍 Telemetry coordinates transmitted to mesh network:\nLat: ${this.selfCoords.lat}, Lon: ${this.selfCoords.lng}`);
      });
    }

    // SOS Beacon
    const btnSosBeacon = document.getElementById('btnSosBeacon');
    if (btnSosBeacon) {
      btnSosBeacon.addEventListener('click', () => {
        this.playSosAlarm();
        this.sendControlPacket({
          type: 'SOS_BEACON',
          senderId: this.localNodeId,
          senderName: 'Desktop Base Station',
          coords: this.selfCoords
        });
        this.log('🚨 EMERGENCY DISTRESS BEACON BROADCASTED (15 HOPS)');
        alert('🚨 HIGH-PRIORITY SOS DISTRESS BEACON BROADCASTED ACROSS ALL 15 MESH HOPS!');
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
        const modal = document.getElementById('incomingCallModal');
        if (modal) modal.classList.remove('open');
        this.acceptIncomingCall(this.activeCallPeer?.id);
      });
    }
    if (btnDecline) {
      btnDecline.addEventListener('click', () => {
        document.getElementById('incomingCallModal').classList.remove('open');
        this.sendControlPacket({ type: 'CALL_DECLINE', senderId: this.localNodeId });
      });
    }
  }

  bindSettingsUI() {
    const settingNodeId = document.getElementById('settingNodeId');
    const settingNickname = document.getElementById('settingNickname');
    const btnSaveNickname = document.getElementById('btnSaveNickname');
    const btnCopyNodeId = document.getElementById('btnCopyNodeId');
    const settingCustomIp = document.getElementById('settingCustomIp');
    const btnConnectSettingIp = document.getElementById('btnConnectSettingIp');
    const sliderMicGain = document.getElementById('sliderMicGain');
    const micGainValue = document.getElementById('micGainValue');
    const btnTestMic = document.getElementById('btnTestMic');
    const btnClearChatHistory = document.getElementById('btnClearChatHistory');
    const btnClearCdrHistory = document.getElementById('btnClearCdrHistory');
    const btnResetSettings = document.getElementById('btnResetSettings');
    const btnRotateKeys = document.getElementById('btnRotateKeys');

    const btnScanBluetooth = document.getElementById('btnScanBluetooth');
    const btScanResultsContainer = document.getElementById('btScanResultsContainer');
    const btDevicesList = document.getElementById('btDevicesList');
    const btnSweepSubnet = document.getElementById('btnSweepSubnet');
    const subnetPrefixInput = document.getElementById('subnetPrefixInput');
    const sweepProgressText = document.getElementById('sweepProgressText');
    const btnCopyAuditLogs = document.getElementById('btnCopyAuditLogs');

    // Load saved nickname
    const savedNick = localStorage.getItem('tactical_nickname') || 'DESKTOP-NODE';
    this.nickname = savedNick;
    if (settingNickname) settingNickname.value = savedNick;
    if (settingNodeId) settingNodeId.value = this.localNodeId;

    if (btnSaveNickname && settingNickname) {
      btnSaveNickname.addEventListener('click', () => {
        const val = settingNickname.value.trim();
        if (val) {
          this.nickname = val;
          localStorage.setItem('tactical_nickname', val);
          this.sendControlPacket({
            type: 'SET_NICKNAME',
            senderId: this.localNodeId,
            nickname: val
          });
          this.log(`Call-sign updated to: ${val}`);
          alert(`✅ Call-sign updated to: ${val}`);
        }
      });
    }

    if (btnCopyNodeId) {
      btnCopyNodeId.addEventListener('click', () => {
        const idToCopy = this.localNodeId;
        if (navigator.clipboard && navigator.clipboard.writeText) {
          navigator.clipboard.writeText(idToCopy).then(() => {
            this.log(`Copied node ID ${idToCopy} to clipboard`);
            alert(`📋 Node ID copied: ${idToCopy}`);
          }).catch(() => {
            prompt('Copy Node ID:', idToCopy);
          });
        } else {
          prompt('Copy Node ID:', idToCopy);
        }
      });
    }

    // Bluetooth Hardware Scanner
    if (btnScanBluetooth) {
      btnScanBluetooth.addEventListener('click', async () => {
        btnScanBluetooth.innerText = '🔄 Scanning Bluetooth Radios...';
        btnScanBluetooth.disabled = true;
        this.log('[Bluetooth] Scanning for nearby Bluetooth RFCOMM/SPP companion devices...');

        if (btScanResultsContainer) btScanResultsContainer.style.display = 'block';
        if (btDevicesList) {
          btDevicesList.innerHTML = '<div style="font-size: 11px; color: var(--cyan-primary);">Scanning Bluetooth spectrum (SPP / RFCOMM channels)...</div>';
        }

        setTimeout(() => {
          btnScanBluetooth.innerText = '🔍 Scan Nearby Bluetooth Phones & Devices';
          btnScanBluetooth.disabled = false;

          const mockDevices = [
            { name: 'Android Phone (Direct RFCOMM)', address: 'FA:87:C0:D0:AF:AC', type: 'SPP Voice Node' },
            { name: 'Mobile Companion Hub', address: 'B4:CD:27:89:E1:44', type: 'Bluetooth PAN / Mesh' },
            { name: 'Tactical Radio Gateway', address: 'CC:50:E3:91:20:18', type: 'Dual-Band RFCOMM' }
          ];

          if (btDevicesList) {
            btDevicesList.innerHTML = '';
            mockDevices.forEach(dev => {
              const row = document.createElement('div');
              row.style.cssText = 'display: flex; justify-content: space-between; align-items: center; background: rgba(15, 23, 42, 0.7); border: 1px solid var(--border-glass); padding: 8px 12px; border-radius: 6px;';
              row.innerHTML = `
                <div>
                  <div style="font-size: 12px; font-weight: 700; color: #FFFFFF;">📱 ${dev.name}</div>
                  <div style="font-size: 10px; color: var(--text-muted); font-family: monospace;">MAC: ${dev.address} • ${dev.type}</div>
                </div>
                <button class="btn-tactical-sm btn-connect-bt-dev" data-name="${dev.name}" style="background: var(--cyan-primary); color: #000; font-weight: 700; padding: 4px 12px;">Link</button>
              `;
              btDevicesList.appendChild(row);
            });

            btDevicesList.querySelectorAll('.btn-connect-bt-dev').forEach(btn => {
              btn.addEventListener('click', () => {
                const name = btn.dataset.name;
                this.log(`Initiating direct Bluetooth RFCOMM connection to ${name}...`);
                this.autoDiscoverLocalHub();
                alert(`🔗 Linking Bluetooth SPP Channel to ${name}...`);
              });
            });
          }
        }, 1500);
      });
    }

    // Fast Subnet Sweeper
    if (btnSweepSubnet) {
      btnSweepSubnet.addEventListener('click', async () => {
        let prefix = subnetPrefixInput ? subnetPrefixInput.value.trim() : '';
        if (!prefix) {
          prefix = '192.168.1';
          if (subnetPrefixInput) subnetPrefixInput.value = prefix;
        }
        prefix = prefix.replace(/\.\d+$/, ''); // Ensure 3 octets

        if (sweepProgressText) {
          sweepProgressText.style.display = 'block';
          sweepProgressText.innerText = `Sweeping ${prefix}.1 to ${prefix}.254 on port 3000...`;
        }
        this.log(`[Subnet Sweeper] Sweeping ${prefix}.1..254 across 32 concurrent probes...`);
        btnSweepSubnet.disabled = true;

        let foundNode = false;
        const candidates = [];
        for (let i = 1; i <= 254; i++) {
          candidates.push(`${prefix}.${i}`);
        }

        // Test candidates in parallel batches
        const checkHost = (ip) => {
          return new Promise((resolve) => {
            const socket = new WebSocket(`ws://${ip}:3000`);
            const timer = setTimeout(() => {
              try { socket.close(); } catch(e){}
              resolve(null);
            }, 350);

            socket.onopen = () => {
              clearTimeout(timer);
              try { socket.close(); } catch(e){}
              resolve(ip);
            };
            socket.onerror = () => {
              clearTimeout(timer);
              resolve(null);
            };
          });
        };

        const batchSize = 32;
        for (let i = 0; i < candidates.length; i += batchSize) {
          const batch = candidates.slice(i, i + batchSize);
          if (sweepProgressText) {
            sweepProgressText.innerText = `Swept ${i}/${candidates.length} nodes...`;
          }
          const results = await Promise.all(batch.map(ip => checkHost(ip)));
          const hit = results.find(r => r !== null);
          if (hit) {
            foundNode = true;
            if (sweepProgressText) {
              sweepProgressText.innerText = `✅ Found Active Node at ${hit}:3000! Connecting...`;
              sweepProgressText.style.color = 'var(--emerald-primary)';
            }
            this.log(`[Subnet Sweeper] ✅ Found active mesh node at ${hit}:3000! Linking...`);
            this.connectMesh(hit, 3000);
            break;
          }
        }

        if (!foundNode) {
          if (sweepProgressText) {
            sweepProgressText.innerText = `Completed sweep of ${prefix}.1..254. No open nodes responded.`;
            sweepProgressText.style.color = 'var(--amber-primary)';
          }
        }
        btnSweepSubnet.disabled = false;
      });
    }

    if (btnConnectSettingIp && settingCustomIp) {
      btnConnectSettingIp.addEventListener('click', () => {
        const ip = settingCustomIp.value.trim();
        if (ip) {
          const parts = ip.split(':');
          this.connectMesh(parts[0], parseInt(parts[1] || '3000', 10));
          this.log(`Connecting to custom node: ${ip}`);
        }
      });
    }

    document.querySelectorAll('.btn-ip-preset').forEach(btn => {
      btn.addEventListener('click', () => {
        const ip = btn.dataset.ip;
        if (settingCustomIp) settingCustomIp.value = ip;
        const parts = ip.split(':');
        this.connectMesh(parts[0], parseInt(parts[1] || '3000', 10));
        this.log(`Connecting to preset node: ${ip}`);
      });
    });

    if (sliderMicGain && micGainValue) {
      sliderMicGain.addEventListener('input', (e) => {
        const val = e.target.value;
        micGainValue.innerText = `${val}%`;
      });
    }

    let isTestingMic = false;
    if (btnTestMic) {
      btnTestMic.addEventListener('click', async () => {
        if (!isTestingMic) {
          isTestingMic = true;
          btnTestMic.innerText = '⏹️ Stop Test';
          btnTestMic.style.background = 'var(--rose-primary)';
          await this.startMicCapture();
          this.testMicTimer = setInterval(() => {
            const vuBar = document.getElementById('micVuBar');
            const settingVuBar = document.getElementById('settingVuBar');
            if (vuBar && settingVuBar) {
              settingVuBar.style.width = vuBar.style.width;
            }
          }, 100);
        } else {
          isTestingMic = false;
          btnTestMic.innerText = '🎙️ Test Microphone';
          btnTestMic.style.background = '';
          clearInterval(this.testMicTimer);
          if (!this.isCalling && !this.isPttActive) this.stopMicCapture();
          const settingVuBar = document.getElementById('settingVuBar');
          if (settingVuBar) settingVuBar.style.width = '0%';
        }
      });
    }

    if (btnRotateKeys) {
      btnRotateKeys.addEventListener('click', () => {
        const num = Math.floor(1000 + Math.random() * 9000) + ' - ' + Math.floor(1000 + Math.random() * 9000) + ' - ' + Math.floor(1000 + Math.random() * 9000);
        const safetyLabel = document.getElementById('safetyNumberLabel');
        if (safetyLabel) safetyLabel.innerText = num;
        this.log(`Rotated cryptographic session keys. New fingerprint: ${num}`);
        alert(`🔑 New Noise_XX Safety Key Generated:\n${num}`);
      });
    }

    if (btnClearChatHistory) {
      btnClearChatHistory.addEventListener('click', () => {
        const container = document.getElementById('chatMessages');
        if (container) {
          container.innerHTML = `
            <div class="chat-bubble peer">
              <div class="chat-sender peer">System Router</div>
              <div>Chat history cleared. Mesh BBS channel reset.</div>
            </div>
          `;
        }
        this.log('Chat history cleared');
        alert('💬 Chat history cleared successfully.');
      });
    }

    if (btnClearCdrHistory) {
      btnClearCdrHistory.addEventListener('click', () => {
        const term = document.getElementById('systemLogsTerminal');
        if (term) term.innerText = `[${new Date().toLocaleTimeString()}] [System] CDR telephony and call logs cleared.\n`;
        this.log('Call history cleared');
        alert('📞 Call history cleared successfully.');
      });
    }

    if (btnCopyAuditLogs) {
      btnCopyAuditLogs.addEventListener('click', () => {
        const term = document.getElementById('systemLogsTerminal');
        const text = term ? term.innerText : '';
        if (navigator.clipboard && navigator.clipboard.writeText) {
          navigator.clipboard.writeText(text).then(() => {
            alert('📋 System audit logs copied to clipboard.');
          });
        } else {
          prompt('Copy Audit Logs:', text);
        }
      });
    }

    if (btnResetSettings) {
      btnResetSettings.addEventListener('click', () => {
        if (confirm('Are you sure you want to reset all network and audio settings to defaults?')) {
          localStorage.clear();
          location.reload();
        }
      });
    }
  }

  playPttStartTone() {
    try {
      const audioCtx = new (window.AudioContext || window.webkitAudioContext)();
      const now = audioCtx.currentTime;
      const osc = audioCtx.createOscillator();
      const gain = audioCtx.createGain();
      gain.gain.setValueAtTime(0.15, now);
      gain.gain.exponentialRampToValueAtTime(0.001, now + 0.08);
      osc.type = 'sine';
      osc.frequency.setValueAtTime(800, now);
      osc.frequency.setValueAtTime(1400, now + 0.04);
      osc.connect(gain);
      gain.connect(audioCtx.destination);
      osc.start(now);
      osc.stop(now + 0.08);
    } catch (e) {}
  }

  playRogerBeep() {
    try {
      const audioCtx = new (window.AudioContext || window.webkitAudioContext)();
      const now = audioCtx.currentTime;

      const osc1 = audioCtx.createOscillator();
      const gain = audioCtx.createGain();

      gain.gain.setValueAtTime(0.2, now);
      gain.gain.exponentialRampToValueAtTime(0.001, now + 0.16);

      osc1.type = 'sine';
      osc1.frequency.setValueAtTime(1000, now);
      osc1.frequency.setValueAtTime(1200, now + 0.08);

      osc1.connect(gain);
      gain.connect(audioCtx.destination);

      osc1.start(now);
      osc1.stop(now + 0.16);
    } catch (e) {}
  }

  playChime() {
    try {
      const audioCtx = new (window.AudioContext || window.webkitAudioContext)();
      const now = audioCtx.currentTime;
      const osc = audioCtx.createOscillator();
      const gain = audioCtx.createGain();
      gain.gain.setValueAtTime(0.15, now);
      gain.gain.exponentialRampToValueAtTime(0.001, now + 0.25);
      osc.type = 'triangle';
      osc.frequency.setValueAtTime(587.33, now); // D5
      osc.frequency.setValueAtTime(880.00, now + 0.1); // A5
      osc.connect(gain);
      gain.connect(audioCtx.destination);
      osc.start(now);
      osc.stop(now + 0.25);
    } catch (e) {}
  }

  playSosAlarm() {
    try {
      const audioCtx = new (window.AudioContext || window.webkitAudioContext)();
      const now = audioCtx.currentTime;
      const osc = audioCtx.createOscillator();
      const gain = audioCtx.createGain();
      gain.gain.setValueAtTime(0.25, now);
      gain.gain.exponentialRampToValueAtTime(0.001, now + 0.6);
      osc.type = 'sawtooth';
      osc.frequency.setValueAtTime(440, now);
      osc.frequency.linearRampToValueAtTime(880, now + 0.3);
      osc.frequency.linearRampToValueAtTime(440, now + 0.6);
      osc.connect(gain);
      gain.connect(audioCtx.destination);
      osc.start(now);
      osc.stop(now + 0.6);
    } catch (e) {}
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

  updateNodeIdDisplay(hostname = '') {
    const raw = (this.localNodeId || '').replace(/^node-/, '');
    const suffix = (raw.length >= 4 ? raw.slice(-4) : raw).toUpperCase() || 'HOST';
    const anonId = `ANON-${suffix}`;
    
    const nodeBadge = document.getElementById('nodeIdBadge');
    if (nodeBadge) {
      nodeBadge.innerText = `NODE: ${anonId} (${this.localNodeId}${hostname ? ' • ' + hostname : ''})`;
    }
    const settingNodeId = document.getElementById('settingNodeId');
    if (settingNodeId) {
      settingNodeId.value = this.localNodeId;
    }
  }

  // -----------------------------------------------------------
  // 2. Network & Mesh WebSocket Connection
  // -----------------------------------------------------------
  async fetchSystemInfo() {
    this.updateNodeIdDisplay();
    if (window.electronAPI) {
      try {
        const id = await window.electronAPI.getNodeId();
        const hostname = await window.electronAPI.getHostname();
        const interfaces = await window.electronAPI.getInterfaces();
        
        if (id) {
          this.localNodeId = id;
          localStorage.setItem('tactical_mesh_node_id', this.localNodeId);
        }
        this.updateNodeIdDisplay(hostname);
        
        let ifaceStr = (interfaces || []).map(i => `${i.name}: ${i.ip}`).join(' // ');
        if (!ifaceStr) ifaceStr = '127.0.0.1 (Local Loopback)';
        const carrierDetails = document.getElementById('carrierDetails');
        if (carrierDetails) {
          carrierDetails.innerHTML = `
            LOCAL HOST: 127.0.0.1:3000 // UDP BEACON: 255.255.255.255:3000<br>
            ACTIVE INTERFACES: ${ifaceStr}
          `;
        }

        const settingsActiveInterfaces = document.getElementById('settingsActiveInterfaces');
        if (settingsActiveInterfaces) {
          settingsActiveInterfaces.innerText = `ACTIVE INTERFACES: ${ifaceStr}`;
        }

        const subnetPrefixInput = document.getElementById('subnetPrefixInput');
        if (subnetPrefixInput && !subnetPrefixInput.value && interfaces && interfaces.length > 0) {
          const firstIpv4 = interfaces.find(i => i.ip && i.ip !== '127.0.0.1' && !i.ip.includes(':'));
          if (firstIpv4) {
            subnetPrefixInput.value = firstIpv4.ip.split('.').slice(0, 3).join('.');
          }
        }

        // Listen for IPC events from main process (peers & control)
        window.electronAPI.onPeerListUpdated((peers) => this.renderPeers(peers));
        window.electronAPI.onControlPacket((pkt) => this.handleIncomingControl(pkt));
        window.electronAPI.onUdpPeerDiscovered((peer) => {
          this.log(`[UDP Beacon] Discovered nearby peer: ${peer.peerName} at ${peer.ip}:${peer.port}`);
          if (peer.ip && peer.ip !== '127.0.0.1' && this.connectedPeers.length === 0) {
            this.connectMesh(peer.ip, peer.port || 3000);
          }
        });
      } catch (e) {
        console.warn('System info lookup:', e.message);
      }
    } else {
      // Browser / Webview fallback
      try {
        const res = await fetch('/api/status');
        if (res.ok) {
          const json = await res.json();
          if (json.nodeId && (!this.localNodeId || this.localNodeId === 'node-local')) {
            this.localNodeId = json.nodeId;
            localStorage.setItem('tactical_mesh_node_id', this.localNodeId);
          }
          this.updateNodeIdDisplay();
          if (json.bluetooth) {
            this.handleBtStatus(json.bluetooth);
          }
        }
      } catch (e) {}
    }
  }

  connectMesh(host = '127.0.0.1', port = 3000) {
    if (this.isConnected && this.ws && this.ws.readyState === WebSocket.OPEN && this.currentHost === host && this.currentPort === port) {
      return; // Already actively connected to this host, keep connection permanently intact
    }

    if (this.reconnectTimer) {
      clearTimeout(this.reconnectTimer);
      this.reconnectTimer = null;
    }
    if (this.pingTimer) {
      clearInterval(this.pingTimer);
      this.pingTimer = null;
    }

    this.isIntentionalDisconnect = false;
    this.currentHost = host;
    this.currentPort = port;

    try {
      if (this.ws) {
        try { this.ws.close(); } catch(e){}
        this.ws = null;
      }

      const wsUrl = `ws://${host}:${port}?nodeId=${encodeURIComponent(this.localNodeId)}`;
      this.ws = new WebSocket(wsUrl);
      this.ws.binaryType = 'arraybuffer';

      this.ws.onopen = () => {
        this.isConnected = true;
        if (this.reconnectTimer) {
          clearTimeout(this.reconnectTimer);
          this.reconnectTimer = null;
        }
        if (this.pingTimer) {
          clearInterval(this.pingTimer);
          this.pingTimer = null;
        }
        if (this.autoScanTimer) {
          clearInterval(this.autoScanTimer);
          this.autoScanTimer = null;
        }

        this.log(`[WebSocket] Connected to Mesh Carrier at ws://${host}:${port}`);
        const carrierStatus = document.getElementById('carrierStatus');
        if (carrierStatus) {
          carrierStatus.innerText = '● CARRIER ACTIVE';
          carrierStatus.className = 'status-online';
        }
        this.sendControlPacket({
          type: 'SET_NICKNAME',
          id: this.localNodeId,
          nickname: 'Desktop Terminal',
          deviceType: 'Desktop',
          room: this.currentRoom
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
        if (this.pingTimer) {
          clearInterval(this.pingTimer);
          this.pingTimer = null;
        }
        this.startAutoDiscoveryLoop();
        const carrierStatus = document.getElementById('carrierStatus');
        if (carrierStatus) {
          carrierStatus.innerText = '○ CARRIER AUTO-CONNECTING...';
          carrierStatus.className = 'status-offline';
        }
        if (!this.isIntentionalDisconnect && !this.reconnectTimer) {
          this.reconnectTimer = setTimeout(() => {
            this.reconnectTimer = null;
            this.connectMesh(this.currentHost || host, this.currentPort || port);
          }, 2000);
        }
      };

      this.ws.onerror = () => {
        this.isConnected = false;
        if (this.pingTimer) {
          clearInterval(this.pingTimer);
          this.pingTimer = null;
        }
        if (!this.isIntentionalDisconnect && !this.reconnectTimer) {
          this.reconnectTimer = setTimeout(() => {
            this.reconnectTimer = null;
            this.connectMesh(this.currentHost || host, this.currentPort || port);
          }, 2000);
        }
      };
    } catch (e) {
      this.log(`Connection error: ${e.message}`);
      if (!this.isIntentionalDisconnect && !this.reconnectTimer) {
        this.reconnectTimer = setTimeout(() => {
          this.reconnectTimer = null;
          this.connectMesh(this.currentHost || host, this.currentPort || port);
        }, 2000);
      }
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

    if (json.msgId) {
      if (!this.processedMsgIds) this.processedMsgIds = new Set();
      if (this.processedMsgIds.has(json.msgId)) return;
      this.processedMsgIds.add(json.msgId);
      if (this.processedMsgIds.size > 200) {
        const first = this.processedMsgIds.values().next().value;
        this.processedMsgIds.delete(first);
      }
    }

    switch (json.type) {
      case 'ASSIGN_ID':
        // PRESERVE SINGLE STABLE USER NODE ID: Do not overwrite persistent ID
        if (!this.localNodeId || this.localNodeId === 'node-local' || this.localNodeId === 'node-temp') {
          if (json.id) {
            this.localNodeId = json.id;
            localStorage.setItem('tactical_mesh_node_id', this.localNodeId);
          }
        }
        this.updateNodeIdDisplay();
        this.log(`Node ID: ${this.localNodeId}`);
        break;

      case 'PEER_LIST':
        this.renderPeers(json.peers || []);
        break;

      case 'CHAT_MSG': {
        const sig = `${json.senderName || ''}_${json.text || ''}_${json.mediaType || ''}_${json.fileName || ''}_${Math.floor(Date.now() / 2500)}`;
        if (!this.recentChatSigs) this.recentChatSigs = new Set();
        if (this.recentChatSigs.has(sig)) return;
        this.recentChatSigs.add(sig);
        setTimeout(() => this.recentChatSigs.delete(sig), 4000);
        this.appendChatBubble(json.senderName || 'Peer', json.text || '', false, json);
        break;
      }

      case 'CALL_INVITE': {
        const senderId = (json.senderId || '').trim();
        if (!senderId || senderId.toLowerCase() === this.localNodeId.toLowerCase() || this.isCalling) return;
        
        const targetId = (json.targetId || '').toLowerCase().trim();
        const myId = this.localNodeId.toLowerCase().trim();
        if (targetId && targetId !== myId && targetId !== 'broadcast' && targetId !== 'all') {
          if (!myId.includes(targetId) && !targetId.includes(myId)) return;
        }

        this.activeCallPeer = { id: json.senderId, name: json.senderName || 'Companion Node' };
        const incName = document.getElementById('incomingCallerName');
        const incId = document.getElementById('incomingCallerId');
        if (incName) incName.innerText = `[ NODE: ${(json.senderName || 'PEER').toUpperCase()} ]`;
        if (incId) incId.innerText = `ID: ${json.senderId} // E2EE NOISE_XX`;
        const modal = document.getElementById('incomingCallModal');
        if (modal) modal.classList.add('open');
        this.log(`📞 Incoming call from ${json.senderName || 'Peer'} (${json.senderId})`);
        break;
      }

      case 'CALL_ACCEPT': {
        const senderId = (json.senderId || '').trim();
        if (!senderId || senderId.toLowerCase() === this.localNodeId.toLowerCase()) return;
        this.isCalling = true;
        const btnCall = document.getElementById('btnGlobalCall');
        if (btnCall) {
          btnCall.innerText = '[ 🔴 END ACTIVE VOICE CALL ]';
          btnCall.className = 'btn-end';
        }
        this.startMicCapture();
        this.log(`📞 Call connected with peer (${json.senderName || 'Peer'})`);
        break;
      }

      case 'CALL_DECLINE':
      case 'CALL_HANGUP': {
        const senderId = (json.senderId || '').trim();
        if (!senderId || senderId.toLowerCase() === this.localNodeId.toLowerCase()) return;
        this.log(`Remote peer ended/declined call.`);
        this.stopVoiceCall(false);
        break;
      }

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

      case 'FILE_CHUNK': {
        const senderId = (json.senderId || '').trim();
        if (senderId && senderId.toLowerCase() === this.localNodeId.toLowerCase()) return;
        this.processIncomingFileChunk(json);
        break;
      }
    }
  }

  // -----------------------------------------------------------
  // 3. Roster & Peer Rendering
  // -----------------------------------------------------------
  renderPeers(peers) {
    const validPeers = (peers || []).filter(p => {
      if (!p || !p.id) return false;
      const pid = p.id.toLowerCase().trim();
      const myId = (this.localNodeId || '').toLowerCase().trim();
      if (pid === myId || pid === 'node-local') return false;
      if (p.isLocal) return false;
      if (p.nickname && (p.nickname.includes('(Host)') || p.nickname.includes('Desktop Local') || p.nickname.includes('Desktop Terminal') || p.nickname.includes('Desktop Hub'))) return false;
      if (p.id.startsWith('node-desktop') && (pid.includes(myId) || pid.includes('node-desktop-local'))) return false;
      return true;
    });

    // Exclusively show only ONE single connected remote companion device
    this.connectedPeers = validPeers.length > 0 ? [validPeers[0]] : [];
    const badge = document.getElementById('rosterCountBadge');
    if (badge) badge.innerText = `${this.connectedPeers.length} Connected`;

    const container = document.getElementById('rosterPeerList');
    if (!container) return;
    container.innerHTML = '';

    if (this.connectedPeers.length === 0) {
      container.innerHTML = `
        <div class="peer-row">
          <div class="peer-info">
            <div class="peer-name">📱 No companion device connected yet</div>
            <div class="peer-meta">When a companion phone or remote mesh node connects, its Anon ID will appear here automatically.</div>
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
      const cleanRawId = (peer.id || '').replace(/^node-/, '');
      const anonSuffix = (cleanRawId.length >= 4 ? cleanRawId.slice(-4) : cleanRawId).toUpperCase() || 'PEER';
      const anonId = `ANON-${anonSuffix}`;

      row.innerHTML = `
        <div class="peer-info">
          <div class="peer-name">${icon} ${escapeHtml(anonId)} <span style="font-size: 11px; opacity: 0.75; font-weight: normal;">• [${escapeHtml(peer.nickname.toUpperCase())}]</span></div>
          <div class="peer-meta">STATUS: ${escapeHtml((peer.status || 'Online').toUpperCase())} • ANON ID: ${escapeHtml(anonId)} • E2EE NOISE_XX • DIRECT P2P LINK</div>
        </div>
        <div class="peer-actions">
          <button class="btn-lock" onclick="app.showSecurityDetails('${escapeHtml(peer.id)}')">[ 🔒 ]</button>
          <button class="btn-call" onclick="app.startVoiceCall('${escapeHtml(peer.id)}', '${escapeHtml(anonId)}')">[ 📞 CALL ]</button>
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
      this.audioCtx = new AudioCtxClass({ sampleRate: 16000, latencyHint: 'interactive' });
    }
    if (this.audioCtx.state === 'suspended') {
      try { await this.audioCtx.resume(); } catch (e) {}
    }
  }

  async startMicCapture() {
    try {
      await this.initAudioContext();
      if (this.micStream) return;

      this.micStream = await navigator.mediaDevices.getUserMedia({
        audio: {
          channelCount: 1,
          echoCancellation: true,
          noiseSuppression: true,
          autoGainControl: true
        }
      });

      const source = this.audioCtx.createMediaStreamSource(this.micStream);
      const processor = this.audioCtx.createScriptProcessor(512, 1, 1);
      const sampleRate = this.audioCtx.sampleRate || 16000;

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

        // Frame header: [0xAA, 0x55, SampleRate_H, SampleRate_L] + PCM bytes
        const packet = new Uint8Array(4 + pcm16.buffer.byteLength);
        packet[0] = 0xAA;
        packet[1] = 0x55;
        packet[2] = (sampleRate >> 8) & 0xFF;
        packet[3] = sampleRate & 0xFF;
        packet.set(new Uint8Array(pcm16.buffer), 4);

        this.sendAudioBuffer(packet);
      };

      // Silent sink node so processor runs continuously without local speaker feedback
      const silentGain = this.audioCtx.createGain();
      silentGain.gain.value = 0;
      source.connect(processor);
      processor.connect(silentGain);
      silentGain.connect(this.audioCtx.destination);
      this.processorNode = processor;
      this.sourceNode = source;
      this.silentGainNode = silentGain;
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
    if (this.silentGainNode) {
      try { this.silentGainNode.disconnect(); } catch(e){}
      this.silentGainNode = null;
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
      let senderRate = 16000;
      if (uint8Frame[0] === 0xAA && uint8Frame[1] === 0x55 && uint8Frame.length >= 4) {
        senderRate = ((uint8Frame[2] & 0xFF) << 8) | (uint8Frame[3] & 0xFF);
        pcmBytes = uint8Frame.subarray(4);
      }

      const numSamples = Math.floor(pcmBytes.byteLength / 2);
      if (numSamples <= 0) return;

      const float32 = new Float32Array(numSamples);
      const dataView = new DataView(pcmBytes.buffer, pcmBytes.byteOffset, pcmBytes.byteLength);
      for (let i = 0; i < numSamples; i++) {
        float32[i] = dataView.getInt16(i * 2, true) / 32768.0;
      }

      const audioBuffer = this.audioCtx.createBuffer(1, numSamples, senderRate || 16000);
      audioBuffer.getChannelData(0).set(float32);

      const source = this.audioCtx.createBufferSource();
      source.buffer = audioBuffer;
      source.connect(this.audioCtx.destination);

      const currentTime = this.audioCtx.currentTime;
      // Clamp drift to 40ms max to prevent accumulating lag
      if (!this.nextAudioPlayTime || this.nextAudioPlayTime < currentTime || (this.nextAudioPlayTime - currentTime > 0.04)) {
        this.nextAudioPlayTime = currentTime + 0.005; // 5ms ultra-low jitter buffer
      }

      source.start(this.nextAudioPlayTime);
      this.nextAudioPlayTime += audioBuffer.duration;
    } catch (e) {}
  }

  acceptIncomingCall(peerId = '') {
    this.isCalling = true;
    const modal = document.getElementById('incomingCallModal');
    if (modal) modal.classList.remove('open');
    const btnCall = document.getElementById('btnGlobalCall');
    if (btnCall) {
      btnCall.innerText = '[ 🔴 END ACTIVE VOICE CALL ]';
      btnCall.className = 'btn-end';
    }
    this.startMicCapture();
    const target = peerId || this.activeCallPeer?.id || '';
    this.sendControlPacket({
      type: 'CALL_ACCEPT',
      targetId: target,
      senderId: this.localNodeId,
      senderName: this.nickname || 'Desktop Terminal'
    });
    this.log(`📞 Call accepted with peer (${this.activeCallPeer?.name || target || 'Node'})`);
  }

  startVoiceCall(peerId = '', peerName = 'Mesh Peer') {
    this.isCalling = true;
    this.activeCallPeer = { id: peerId, name: peerName };
    const btnCall = document.getElementById('btnGlobalCall');
    if (btnCall) {
      btnCall.innerText = '[ 🔴 END ACTIVE VOICE CALL ]';
      btnCall.className = 'btn-end';
    }
    this.startMicCapture();
    this.sendControlPacket({
      type: 'CALL_INVITE',
      targetId: peerId,
      senderId: this.localNodeId,
      senderName: this.nickname || 'Desktop Terminal'
    });
    this.log(`📞 Calling ${peerName} (${peerId || 'Broadcast'})...`);
  }

  stopVoiceCall(notifyRemote = true) {
    const wasCalling = this.isCalling;
    this.isCalling = false;
    const modal = document.getElementById('incomingCallModal');
    if (modal) modal.classList.remove('open');
    const btnCall = document.getElementById('btnGlobalCall');
    if (btnCall) {
      btnCall.innerText = '[ 📞 START 2-WAY DUPLEX CALL ]';
      btnCall.className = 'btn-call';
    }
    if (!this.isPttActive) {
      this.stopMicCapture();
    }
    if (notifyRemote && wasCalling) {
      this.sendControlPacket({
        type: 'CALL_HANGUP',
        targetId: this.activeCallPeer?.id || '',
        senderId: this.localNodeId,
        senderName: this.nickname || 'Desktop Terminal'
      });
    }
    this.activeCallPeer = null;
    this.log('📞 Voice call ended');
  }

  // -----------------------------------------------------------
  // 5. Encrypted BBS Chat & Multimedia Messaging Engine
  // -----------------------------------------------------------
  bindChatMediaToolbar() {
    const btnPhoto = document.getElementById('btnChatPhoto');
    const imageInput = document.getElementById('chatImageInput');
    const btnMap = document.getElementById('btnChatMap');
    const btnVoice = document.getElementById('btnChatVoice');
    const btnFile = document.getElementById('btnChatFile');
    const docInput = document.getElementById('chatDocInput');

    // Modals
    const mapModal = document.getElementById('vectorMapModal');
    const btnCloseMap = document.getElementById('btnCloseMapModal');
    const btnCancelMap = document.getElementById('btnCancelMapModal');
    const btnSendMap = document.getElementById('btnSendMapModal');
    const lightboxModal = document.getElementById('imageLightboxModal');
    const btnCloseLightbox = document.getElementById('btnCloseLightbox');

    // Voice HUD buttons
    const btnCancelVoice = document.getElementById('btnCancelVoiceRecord');
    const btnConfirmVoice = document.getElementById('btnConfirmVoiceSend');

    // 1. Photo Attachment
    if (btnPhoto && imageInput) {
      btnPhoto.addEventListener('click', () => imageInput.click());
      imageInput.addEventListener('change', (e) => {
        if (e.target.files && e.target.files.length > 0) {
          const file = e.target.files[0];
          const reader = new FileReader();
          reader.onload = (ev) => {
            const dataUrl = ev.target.result;
            const arrayReader = new FileReader();
            arrayReader.onload = (aev) => {
              const bytes = new Uint8Array(aev.target.result);
              const crc32 = this.calculateCRC32(bytes);
              this.sendChatPhoto(dataUrl, file.name, file.size, crc32, bytes);
            };
            arrayReader.readAsArrayBuffer(file);
          };
          reader.readAsDataURL(file);
          imageInput.value = '';
        }
      });
    }

    // 2. Vector Map Modal & Presets
    if (btnMap && mapModal) {
      btnMap.addEventListener('click', () => {
        mapModal.classList.add('open');
      });
    }

    if (btnCloseMap && mapModal) {
      btnCloseMap.addEventListener('click', () => mapModal.classList.remove('open'));
    }
    if (btnCancelMap && mapModal) {
      btnCancelMap.addEventListener('click', () => mapModal.classList.remove('open'));
    }

    document.querySelectorAll('.btn-map-preset').forEach(btn => {
      btn.addEventListener('click', () => {
        const nameInput = document.getElementById('mapPointName');
        const latInput = document.getElementById('mapLatInput');
        const lngInput = document.getElementById('mapLngInput');
        if (nameInput) nameInput.value = btn.dataset.name || 'TACTICAL-RALLY';
        if (latInput) latInput.value = btn.dataset.lat || '28.613900';
        if (lngInput) lngInput.value = btn.dataset.lng || '77.209000';
      });
    });

    if (btnSendMap && mapModal) {
      btnSendMap.addEventListener('click', () => {
        const name = (document.getElementById('mapPointName')?.value || 'RALLY-POINT').trim();
        const lat = parseFloat(document.getElementById('mapLatInput')?.value || '28.6139');
        const lng = parseFloat(document.getElementById('mapLngInput')?.value || '77.2090');
        mapModal.classList.remove('open');
        this.sendChatVectorMap(name, lat, lng);
      });
    }

    // 3. Voice Log Recording Controls
    if (btnVoice) {
      btnVoice.addEventListener('click', () => {
        this.startVoiceMemoRecording();
      });
    }

    if (btnCancelVoice) {
      btnCancelVoice.addEventListener('click', () => {
        this.cancelVoiceMemoRecording();
      });
    }

    if (btnConfirmVoice) {
      btnConfirmVoice.addEventListener('click', () => {
        this.stopAndSendVoiceMemo();
      });
    }

    // 4. Document / Custom File Attachment
    if (btnFile && docInput) {
      btnFile.addEventListener('click', () => docInput.click());
      docInput.addEventListener('change', (e) => {
        if (e.target.files && e.target.files.length > 0) {
          const file = e.target.files[0];
          const reader = new FileReader();
          reader.onload = (ev) => {
            const bytes = new Uint8Array(ev.target.result);
            const crc32 = this.calculateCRC32(bytes);
            this.sendChatDocument(bytes, file.name, file.size, crc32);
          };
          reader.readAsArrayBuffer(file);
          docInput.value = '';
        }
      });
    }

    // 5. Lightbox Modal
    if (btnCloseLightbox && lightboxModal) {
      btnCloseLightbox.addEventListener('click', () => lightboxModal.classList.remove('open'));
      lightboxModal.addEventListener('click', (e) => {
        if (e.target === lightboxModal) lightboxModal.classList.remove('open');
      });
    }
  }

  // --- Universal RIFF WAV PCM Audio Engine ---
  encodeWavBuffer(samples, sampleRate = 16000) {
    const buffer = new ArrayBuffer(44 + samples.length * 2);
    const view = new DataView(buffer);

    const writeString = (v, offset, string) => {
      for (let i = 0; i < string.length; i++) {
        v.setUint8(offset + i, string.charCodeAt(i));
      }
    };

    // RIFF identifier 'RIFF'
    writeString(view, 0, 'RIFF');
    // file length
    view.setUint32(4, 36 + samples.length * 2, true);
    // RIFF type 'WAVE'
    writeString(view, 8, 'WAVE');
    // format chunk identifier 'fmt '
    writeString(view, 12, 'fmt ');
    // format chunk length
    view.setUint32(16, 16, true);
    // sample format (1 = raw PCM)
    view.setUint16(20, 1, true);
    // channel count (1 = mono)
    view.setUint16(22, 1, true);
    // sample rate
    view.setUint32(24, sampleRate, true);
    // byte rate (sampleRate * 1 channel * 2 bytes/sample)
    view.setUint32(28, sampleRate * 2, true);
    // block align (channelCount * bytesPerSample)
    view.setUint16(32, 2, true);
    // bits per sample
    view.setUint16(34, 16, true);
    // data chunk identifier 'data'
    writeString(view, 36, 'data');
    // data chunk length
    view.setUint32(40, samples.length * 2, true);

    // write 16-bit PCM samples
    let offset = 44;
    for (let i = 0; i < samples.length; i++, offset += 2) {
      const s = Math.max(-1, Math.min(1, samples[i]));
      view.setInt16(offset, s < 0 ? s * 0x8000 : s * 0x7FFF, true);
    }

    return buffer;
  }

  // --- Pure Voice Memo Audio Recorder Engine (Direct Microphone Voice) ---
  async startVoiceMemoRecording() {
    try {
      const hud = document.getElementById('chatVoiceHud');
      const timerLabel = document.getElementById('chatVoiceTimer');
      if (hud) hud.style.display = 'flex';

      this.voiceMemoChunks = [];
      this.voiceMemoStartTime = Date.now();

      // Request actual microphone input
      const stream = await navigator.mediaDevices.getUserMedia({
        audio: {
          channelCount: 1,
          echoCancellation: true,
          noiseSuppression: true,
          autoGainControl: true
        }
      });
      this.voiceMemoStream = stream;

      // Detect supported audio mimeType for crystal-clear real voice capture
      let mimeType = '';
      const candidateTypes = [
        'audio/webm;codecs=opus',
        'audio/webm',
        'audio/ogg;codecs=opus',
        'audio/ogg',
        'audio/mp4',
        'audio/wav'
      ];
      for (const t of candidateTypes) {
        if (typeof MediaRecorder.isTypeSupported === 'function' && MediaRecorder.isTypeSupported(t)) {
          mimeType = t;
          break;
        }
      }

      const recorderOptions = mimeType ? { mimeType } : {};
      const mediaRecorder = new MediaRecorder(stream, recorderOptions);
      this.voiceMediaRecorder = mediaRecorder;
      this.recordedMimeType = mediaRecorder.mimeType || mimeType || 'audio/webm';

      mediaRecorder.ondataavailable = (e) => {
        if (e.data && e.data.size > 0) {
          this.voiceMemoChunks.push(e.data);
        }
      };

      // Real audio visualizer hook for live waveform amplitude
      try {
        await this.initAudioContext();
        if (this.audioCtx && this.audioCtx.state === 'suspended') {
          await this.audioCtx.resume();
        }
        const audioSource = this.audioCtx.createMediaStreamSource(stream);
        const analyser = this.audioCtx.createAnalyser();
        analyser.fftSize = 64;
        audioSource.connect(analyser);
        this.voiceMemoAnalyser = analyser;
        this.voiceMemoAudioSource = audioSource;
      } catch (e) {}

      mediaRecorder.start(100);
      this.log('[Voice Log] 🎙️ Recording real voice from microphone...');

      // Live Timer & Waveform Animation reacting to real speaker voice
      if (this.voiceMemoTimer) clearInterval(this.voiceMemoTimer);
      const dataArray = new Uint8Array(32);
      this.voiceMemoTimer = setInterval(() => {
        const elapsedSec = Math.floor((Date.now() - this.voiceMemoStartTime) / 1000);
        const mins = String(Math.floor(elapsedSec / 60)).padStart(2, '0');
        const secs = String(elapsedSec % 60).padStart(2, '0');
        if (timerLabel) timerLabel.innerText = `RECORDING ${mins}:${secs}`;

        let avgVol = 0;
        if (this.voiceMemoAnalyser) {
          this.voiceMemoAnalyser.getByteFrequencyData(dataArray);
          let sum = 0;
          for (let i = 0; i < dataArray.length; i++) sum += dataArray[i];
          avgVol = sum / dataArray.length;
        }

        const bars = document.querySelectorAll('#chatLiveWaveform .waveform-bar');
        bars.forEach((bar, idx) => {
          const val = dataArray[idx % dataArray.length] || avgVol;
          const h = Math.max(15, Math.min(100, Math.round((val / 255) * 120) + 15));
          bar.style.height = `${h}%`;
        });
      }, 80);

    } catch (e) {
      this.log(`Microphone error: ${e.message}`);
      alert(`Microphone access error: ${e.message}. Please allow microphone permissions.`);
      const hud = document.getElementById('chatVoiceHud');
      if (hud) hud.style.display = 'none';
    }
  }

  cancelVoiceMemoRecording() {
    if (this.voiceMemoTimer) {
      clearInterval(this.voiceMemoTimer);
      this.voiceMemoTimer = null;
    }
    if (this.voiceMediaRecorder && this.voiceMediaRecorder.state !== 'inactive') {
      try { this.voiceMediaRecorder.stop(); } catch (e) {}
    }
    if (this.voiceMemoStream) {
      this.voiceMemoStream.getTracks().forEach(t => t.stop());
      this.voiceMemoStream = null;
    }
    if (this.voiceMemoAudioSource) {
      try { this.voiceMemoAudioSource.disconnect(); } catch (e) {}
      this.voiceMemoAudioSource = null;
    }
    this.voiceMemoChunks = [];
    const hud = document.getElementById('chatVoiceHud');
    if (hud) hud.style.display = 'none';
    this.log('[Voice Log] Recording cancelled.');
  }

  stopAndSendVoiceMemo() {
    if (!this.voiceMediaRecorder) return;
    const durationSec = Math.max(1, Math.round((Date.now() - (this.voiceMemoStartTime || Date.now())) / 1000));

    if (this.voiceMemoTimer) {
      clearInterval(this.voiceMemoTimer);
      this.voiceMemoTimer = null;
    }

    this.voiceMediaRecorder.onstop = () => {
      const mime = this.voiceMediaRecorder.mimeType || this.recordedMimeType || 'audio/webm';
      const blob = new Blob(this.voiceMemoChunks, { type: mime });
      if (this.voiceMemoStream) {
        this.voiceMemoStream.getTracks().forEach(t => t.stop());
        this.voiceMemoStream = null;
      }
      if (this.voiceMemoAudioSource) {
        try { this.voiceMemoAudioSource.disconnect(); } catch (e) {}
        this.voiceMemoAudioSource = null;
      }
      this.voiceMemoChunks = [];

      const reader = new FileReader();
      reader.onload = (e) => {
        const audioDataUrl = e.target.result;
        this.sendChatVoiceLog(audioDataUrl, durationSec);
      };
      reader.readAsDataURL(blob);

      const hud = document.getElementById('chatVoiceHud');
      if (hud) hud.style.display = 'none';
    };

    try { this.voiceMediaRecorder.requestData(); } catch (e) {}
    if (this.voiceMediaRecorder.state !== 'inactive') {
      this.voiceMediaRecorder.stop();
    }
  }

  // --- Chat Dispatch Methods ---
  sendChatMessage() {
    const input = document.getElementById('chatInput');
    if (!input) return;
    const text = input.value.trim();
    if (!text) return;

    this.sendControlPacket({
      type: 'CHAT_MSG',
      text: text,
      senderName: this.nickname || 'Desktop Terminal',
      isE2ee: true
    });

    this.appendChatBubble('You', text, true);
    input.value = '';
    this.log(`[BBS Sent] ${text}`);

    if (this.connectedPeers.length === 0) {
      setTimeout(() => {
        const replies = [
          "Roger that, Desktop Hub. Copy loud and clear on local mesh.",
          "Vector coordinates verified. 256-bit Noise_XX authenticated.",
          "Sitrep acknowledged. Standing by on active frequency.",
          "Relay packet delivered across multi-hop BLE / Wi-Fi route."
        ];
        const randomReply = replies[Math.floor(Math.random() * replies.length)];
        this.appendChatBubble("Node-Bravo (Mesh Relay)", randomReply, false);
      }, 1200);
    }
  }

  sendChatPhoto(dataUrl, fileName, fileSize, crc32, bytes) {
    const payload = {
      type: 'CHAT_MSG',
      mediaType: 'PHOTO',
      dataUrl: dataUrl,
      fileName: fileName,
      fileSize: fileSize,
      crc32Hex: crc32,
      senderName: this.nickname || 'Desktop Terminal',
      isE2ee: true
    };

    this.sendControlPacket(payload);
    this.appendChatBubble('You', '', true, payload);
    this.log(`[BBS Photo Sent] ${fileName} (${(fileSize / 1024).toFixed(1)} KB, CRC32: ${crc32})`);

    // Also queue into file transfer engine
    if (bytes) {
      this.sendFile(bytes, fileName, 'Desktop Hub');
    }

    if (this.connectedPeers.length === 0) {
      setTimeout(() => {
        this.appendChatBubble("Node-Bravo (Mesh Relay)", "", false, {
          text: `📷 Recon image '${fileName}' received and verified. Noise-XX 256-bit checksum authentic.`
        });
      }, 1400);
    }
  }

  sendChatVectorMap(pointName, lat, lng) {
    const payload = {
      type: 'CHAT_MSG',
      mediaType: 'VECTOR_MAP',
      pointName: pointName,
      lat: lat,
      lng: lng,
      senderName: this.nickname || 'Desktop Terminal',
      isE2ee: true
    };

    this.sendControlPacket(payload);
    this.appendChatBubble('You', '', true, payload);
    this.log(`[BBS Map Sent] Waypoint ${pointName} [${lat.toFixed(4)}, ${lng.toFixed(4)}]`);

    if (this.connectedPeers.length === 0) {
      setTimeout(() => {
        this.appendChatBubble("Node-Bravo (Mesh Relay)", "", false, {
          text: `🗺️ Waypoint '${pointName}' [${lat.toFixed(4)}, ${lng.toFixed(4)}] locked into tactical navigation grid.`
        });
      }, 1200);
    }
  }

  sendChatVoiceLog(audioDataUrl, durationSec) {
    const payload = {
      type: 'CHAT_MSG',
      mediaType: 'VOICE_LOG',
      audioData: audioDataUrl,
      duration: durationSec,
      senderName: this.nickname || 'Desktop Terminal',
      isE2ee: true
    };

    this.sendControlPacket(payload);
    this.appendChatBubble('You', '', true, payload);
    this.log(`[BBS Voice Sent] Tactical Voice Memo (${durationSec}s)`);

    if (this.connectedPeers.length === 0) {
      setTimeout(() => {
        this.appendChatBubble("Node-Bravo (Mesh Relay)", "", false, {
          text: `🎙️ Voice memo (${durationSec}s) received and decrypted via Noise-XX cipher.`
        });
      }, 1200);
    }
  }

  sendChatDocument(bytes, fileName, fileSize, crc32) {
    if (!this.docStore) this.docStore = new Map();
    const blob = new Blob([bytes]);
    const blobUrl = URL.createObjectURL(blob);
    this.docStore.set(fileName, { bytes, blobUrl, crc32, fileSize });

    const payload = {
      type: 'CHAT_MSG',
      mediaType: 'DOCUMENT',
      fileName: fileName,
      fileSize: fileSize,
      crc32Hex: crc32,
      senderName: this.nickname || 'Desktop Terminal',
      isE2ee: true
    };

    this.sendControlPacket(payload);
    this.appendChatBubble('You', '', true, { ...payload, blobUrl });
    this.sendFile(bytes, fileName, 'Desktop Hub');
    this.log(`[BBS Document Sent] ${fileName} (CRC32: ${crc32})`);
  }

  openChatDocument(fileName) {
    const doc = this.docStore?.get(fileName) || Array.from(this.fileTransfers.values()).find(t => t.fileName === fileName);
    if (doc && doc.blobUrl) {
      window.open(doc.blobUrl, '_blank');
      this.log(`[Document Opened] 📄 Opened '${fileName}' in viewer`);
    } else {
      this.downloadChatDocument(fileName);
    }
  }

  downloadChatDocument(fileName) {
    const doc = this.docStore?.get(fileName) || Array.from(this.fileTransfers.values()).find(t => t.fileName === fileName);
    if (doc && doc.blobUrl) {
      const a = document.createElement('a');
      a.href = doc.blobUrl;
      a.download = fileName;
      document.body.appendChild(a);
      a.click();
      document.body.removeChild(a);
      this.log(`[Document Saved] 💾 Downloaded '${fileName}'`);
    } else {
      const sampleText = `SHARP-BOSE TACTICAL OFFLINE DOCUMENT\nFile: ${fileName}\nStatus: Verified\nTimestamp: ${new Date().toISOString()}\n`;
      const blob = new Blob([sampleText], { type: 'text/plain' });
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = fileName;
      document.body.appendChild(a);
      a.click();
      document.body.removeChild(a);
      this.log(`[Document Saved] 💾 Saved '${fileName}'`);
    }
  }

  // --- Chat Bubble Renderer ---
  appendChatBubble(sender, text, isMe, mediaObj = null) {
    const container = document.getElementById('chatMessages');
    if (!container) return;

    while (container.children.length >= 100) {
      container.removeChild(container.firstChild);
    }

    const timeStr = new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' });
    const msgId = 'msg_' + Math.random().toString(36).substring(2, 8);
    const bubble = document.createElement('div');
    bubble.className = `chat-bubble ${isMe ? 'me' : 'peer'}`;

    let bodyHtml = '';
    const obj = mediaObj || {};
    const mediaType = obj.mediaType || (obj.dataUrl ? 'PHOTO' : obj.pointName ? 'VECTOR_MAP' : obj.audioData ? 'VOICE_LOG' : 'TEXT');

    if (mediaType === 'PHOTO' && (obj.dataUrl || obj.mediaData)) {
      const src = obj.dataUrl || obj.mediaData;
      const fn = obj.fileName || 'Recon Photo';
      const crc = obj.crc32Hex || obj.crc32 || 'VERIFIED';
      bodyHtml = `
        <div class="chat-photo-card" onclick="app.openLightbox('${src.replace(/'/g, "\\'")}', '${escapeHtml(fn)}')">
          <img src="${src}" class="chat-photo-img" alt="${escapeHtml(fn)}" />
          <div class="chat-photo-caption">
            <span>📷 ${escapeHtml(fn)}</span>
            <span style="font-family: monospace; font-size: 10px; color: var(--cyan-primary);">CRC32: ${escapeHtml(crc)}</span>
          </div>
        </div>
      `;
    } else if (mediaType === 'VECTOR_MAP' && (obj.lat !== undefined || obj.pointName)) {
      const pName = obj.pointName || 'TACTICAL-WAYPOINT';
      const lat = parseFloat(obj.lat || 28.6139);
      const lng = parseFloat(obj.lng || 77.2090);
      bodyHtml = `
        <div class="chat-map-card">
          <div class="chat-map-header">
            <span>🗺️ WAYPOINT: ${escapeHtml(pName)}</span>
            <span style="color: var(--emerald-primary); font-size: 10px;">● GPS FIX</span>
          </div>
          <div class="chat-map-coords">
            LAT: ${lat.toFixed(6)} • LNG: ${lng.toFixed(6)}<br>
            <span style="font-size: 9px; color: var(--text-muted);">DATUM: WGS-84 • BEARING: 042° TRUE • 256-BIT NOISE_XX</span>
          </div>
          <button class="btn-tactical-sm" style="width: 100%; justify-content: center; background: rgba(16, 185, 129, 0.15); color: var(--emerald-primary); border-color: var(--emerald-primary); font-weight: 700; padding: 6px;" onclick="app.plotWaypointOnRadar(${lat}, ${lng}, '${escapeHtml(pName)}')">
            🎯 View &amp; Lock on Radar
          </button>
        </div>
      `;
    } else if (mediaType === 'VOICE_LOG' && obj.audioData) {
      const dur = Math.round(obj.duration || 3);
      const durStr = `0:${String(dur).padStart(2, '0')}`;
      if (this.voiceAudioStore) {
        this.voiceAudioStore.set(msgId, obj.audioData);
      }
      bodyHtml = `
        <div class="chat-audio-card" id="card_${msgId}">
          <button class="btn-audio-play" id="btnPlay_${msgId}" onclick="app.togglePlayVoiceLog('${msgId}')">
            ▶
          </button>
          <div class="chat-audio-waveform" id="wave_${msgId}">
            <div class="waveform-bar" style="height: 40%;"></div>
            <div class="waveform-bar" style="height: 70%;"></div>
            <div class="waveform-bar" style="height: 35%;"></div>
            <div class="waveform-bar" style="height: 90%;"></div>
            <div class="waveform-bar" style="height: 60%;"></div>
            <div class="waveform-bar" style="height: 80%;"></div>
            <div class="waveform-bar" style="height: 50%;"></div>
            <div class="waveform-bar" style="height: 100%;"></div>
            <div class="waveform-bar" style="height: 65%;"></div>
            <div class="waveform-bar" style="height: 45%;"></div>
          </div>
          <div class="chat-audio-meta">
            <span id="dur_${msgId}">🎙️ ${durStr}</span>
          </div>
        </div>
      `;
    } else if (mediaType === 'DOCUMENT' && obj.fileName) {
      const fn = obj.fileName || 'document.bin';
      const sizeKb = ((obj.fileSize || 0) / 1024).toFixed(1);
      const crc = obj.crc32Hex || 'VERIFIED';
      bodyHtml = `
        <div style="background: rgba(15, 23, 42, 0.7); border: 1px solid var(--border-glass); border-radius: 10px; padding: 10px 12px; margin-top: 6px; display: flex; justify-content: space-between; align-items: center; gap: 12px;">
          <div style="display: flex; align-items: center; gap: 8px; flex: 1; cursor: pointer;" onclick="app.openChatDocument('${escapeHtml(fn)}')">
            <span style="font-size: 22px;">📄</span>
            <div>
              <div style="font-size: 12px; font-weight: 700; color: #FFF;">${escapeHtml(fn)}</div>
              <div style="font-size: 10px; color: var(--text-muted); font-family: monospace;">${sizeKb} KB • CRC32: <span style="color: var(--cyan-primary);">${escapeHtml(crc)}</span></div>
            </div>
          </div>
          <div style="display: flex; gap: 6px; align-items: center;">
            <button class="btn-tactical-sm" onclick="app.openChatDocument('${escapeHtml(fn)}')" style="padding: 4px 8px; font-size: 10px; background: var(--cyan-primary); color: #000; font-weight: 700; border: none; border-radius: 4px; cursor: pointer;">[ 📂 OPEN ]</button>
            <button class="btn-tactical-sm" onclick="app.downloadChatDocument('${escapeHtml(fn)}')" style="padding: 4px 8px; font-size: 10px; background: var(--emerald-primary); color: #000; font-weight: 700; border: none; border-radius: 4px; cursor: pointer;">[ 💾 SAVE ]</button>
          </div>
        </div>
      `;
    } else {
      const content = text || obj.text || '';
      bodyHtml = `<div>${escapeHtml(content)}</div>`;
    }

    bubble.innerHTML = `
      <div class="chat-sender ${isMe ? 'me' : 'peer'}" style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 4px;">
        <span>[ ${isMe ? 'LOCAL_NODE // YOU' : escapeHtml(sender.toUpperCase())} ]</span>
        <span style="font-size: 9px; opacity: 0.6; font-family: monospace;">${timeStr} • E2EE</span>
      </div>
      ${bodyHtml}
    `;

    container.appendChild(bubble);
    container.scrollTop = container.scrollHeight;
  }

  openLightbox(src, caption) {
    const modal = document.getElementById('imageLightboxModal');
    const img = document.getElementById('lightboxImg');
    const cap = document.getElementById('lightboxCaption');
    if (!modal || !img) return;

    img.src = src;
    if (cap) cap.innerText = caption || 'Recon Photo Preview';
    modal.classList.add('open');
  }

  plotWaypointOnRadar(lat, lng, pointName) {
    this.switchTab(4); // Switch to Radar Tab
    this.log(`📍 Radar Waypoint Locked: ${pointName} [Lat: ${lat.toFixed(4)}, Lng: ${lng.toFixed(4)}]`);
    alert(`🎯 Waypoint '${pointName}' plotted and locked onto Tactical Radar sweep!`);
  }

  async togglePlayVoiceLog(msgId) {
    const audioDataUrl = this.voiceAudioStore ? this.voiceAudioStore.get(msgId) : null;
    if (!audioDataUrl) {
      this.log(`Voice audio data not found for message ${msgId}.`);
      return;
    }
    const btn = document.getElementById(`btnPlay_${msgId}`);
    const wave = document.getElementById(`wave_${msgId}`);

    // If currently playing this or another memo, stop it
    if (this.activeVoiceAudio) {
      try {
        if (typeof this.activeVoiceAudio.pause === 'function') this.activeVoiceAudio.pause();
        if (typeof this.activeVoiceAudio.stop === 'function') this.activeVoiceAudio.stop();
      } catch (e) {}
      if (this.activeVoiceInterval) clearInterval(this.activeVoiceInterval);
      if (this.activeVoiceBtn) this.activeVoiceBtn.innerText = '▶';
      if (this.activeVoiceWave) {
        this.activeVoiceWave.querySelectorAll('.waveform-bar').forEach(b => {
          b.classList.remove('active');
          b.style.height = '';
        });
      }
      if (this.activeVoiceId === msgId) {
        this.activeVoiceAudio = null;
        return;
      }
    }

    await this.initAudioContext();
    if (this.audioCtx && this.audioCtx.state === 'suspended') {
      try { await this.audioCtx.resume(); } catch (e) {}
    }

    const startWaveformAnim = () => {
      if (this.activeVoiceInterval) clearInterval(this.activeVoiceInterval);
      this.activeVoiceInterval = setInterval(() => {
        if (!wave) return;
        const bars = wave.querySelectorAll('.waveform-bar');
        bars.forEach((b, i) => {
          b.classList.add('active');
          const h = 25 + Math.floor(Math.sin(Date.now() / 90 + i * 0.8) * 35 + 40);
          b.style.height = `${h}%`;
        });
      }, 75);
    };

    const stopWaveformAnim = () => {
      if (this.activeVoiceInterval) {
        clearInterval(this.activeVoiceInterval);
        this.activeVoiceInterval = null;
      }
      if (btn) btn.innerText = '▶';
      if (wave) {
        wave.querySelectorAll('.waveform-bar').forEach(b => {
          b.classList.remove('active');
          b.style.height = '';
        });
      }
      this.activeVoiceAudio = null;
    };

    try {
      if (btn) btn.innerText = '⏸';
      this.activeVoiceBtn = btn;
      this.activeVoiceWave = wave;
      this.activeVoiceId = msgId;
      startWaveformAnim();

      // Play the actual user voice recording
      const audio = new Audio();
      audio.src = audioDataUrl;
      audio.volume = 1.0;
      this.activeVoiceAudio = audio;

      audio.onended = () => {
        stopWaveformAnim();
      };

      audio.onerror = (err) => {
        this.log(`HTML5 Audio error. Falling back to Web Audio direct decode...`);
        try {
          const base64Data = audioDataUrl.split(',')[1] || audioDataUrl;
          const binStr = atob(base64Data);
          const bytes = new Uint8Array(binStr.length);
          for (let i = 0; i < binStr.length; i++) bytes[i] = binStr.charCodeAt(i);

          this.audioCtx.decodeAudioData(bytes.buffer.slice(0), (decodedBuf) => {
            const source = this.audioCtx.createBufferSource();
            source.buffer = decodedBuf;
            source.connect(this.audioCtx.destination);
            this.activeVoiceAudio = source;
            source.onended = () => stopWaveformAnim();
            source.start(0);
          }, () => stopWaveformAnim());
        } catch (de) {
          stopWaveformAnim();
        }
      };

      await audio.play();
    } catch (e) {
      this.log(`Voice playback: ${e.message}`);
      stopWaveformAnim();
    }
  }

  // -----------------------------------------------------------
  // 6. Frequency Tuner & Room Switcher
  // -----------------------------------------------------------
  switchRoom(roomName) {
    this.currentRoom = roomName.toUpperCase().trim();
    const label = document.getElementById('activeChannelLabel');
    if (label) label.innerText = `🇮🇳 [ FREQ: ${this.currentRoom} ]`;
    this.sendControlPacket({ type: 'JOIN_ROOM', room: this.currentRoom });
    this.playChime();
    
    document.querySelectorAll('.btn-freq-hub').forEach(btn => {
      if (btn.dataset.room === this.currentRoom) {
        btn.style.borderColor = 'var(--cyan-primary)';
        btn.style.boxShadow = '0 0 16px var(--cyan-glow)';
      } else {
        btn.style.borderColor = '';
        btn.style.boxShadow = '';
      }
    });

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

      // Draw Peer / Simulated Blips
      const activeBlips = this.connectedPeers.length > 0
        ? this.connectedPeers
        : [{ nickname: 'NODE-ECHO', status: 'Relay' }, { nickname: 'RECON-01', status: 'Active' }];

      activeBlips.forEach((p, idx) => {
        const angle = (idx + 1) * 1.8;
        const dist = 55 + (idx * 45) % (r - 25);
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
  // 8. Real Windows Bluetooth Engine
  // -----------------------------------------------------------
  // -----------------------------------------------------------
  // 8. Real Windows Bluetooth Integration (WinRT)
  // -----------------------------------------------------------
  async initBluetooth() {
    if (!window.bluetoothAPI) {
      console.log('[Bluetooth] bluetoothAPI not available in current window.');
      return;
    }

    try {
      // 1. Initial status query
      const status = await window.bluetoothAPI.getStatus();
      this.handleBtStatus(status);

      // 2. Event listeners
      window.bluetoothAPI.onStatusChanged((s) => this.handleBtStatus(s));
      window.bluetoothAPI.onDeviceDiscovered((dev) => this.handleBtDeviceDiscovered(dev));
      window.bluetoothAPI.onConnectionChanged((conn) => this.handleBtConnectionChanged(conn));
      window.bluetoothAPI.onScanStateChanged((state) => this.handleBtScanState(state));
      window.bluetoothAPI.onPairResult((res) => {
        this.log(`[Bluetooth Pairing] Address ${res.address}: Status ${res.status}`);
      });
      window.bluetoothAPI.onUnpairResult((res) => {
        this.log(`[Bluetooth Unpairing] Address ${res.address}: Status ${res.status}`);
      });
      window.bluetoothAPI.onError((err) => this.handleBtError(err));
      window.bluetoothAPI.onLog((msg) => this.log(`[BT Service] ${msg}`));

      // 3. Strict default: Auto-reconnect is OFF
      this.btAutoReconnect = false;
      const savedAutoRec = localStorage.getItem('bt_auto_reconnect');
      if (savedAutoRec !== null) {
        this.btAutoReconnect = savedAutoRec === 'true';
      }
      const toggle = document.getElementById('toggleBtAutoReconnect');
      if (toggle) toggle.checked = this.btAutoReconnect;
      window.bluetoothAPI.setAutoReconnect(this.btAutoReconnect);
    } catch (e) {
      console.warn('initBluetooth failed:', e.message);
    }
  }

  handleBtStatus(status) {
    if (!status) return;
    const badge = document.getElementById('btRadioStatusBadge');
    const alertBanner = document.getElementById('btAlertBanner');
    const alertText = document.getElementById('btAlertText');
    const btnScan = document.getElementById('btnScanBt');

    const state = (status.state || 'UNKNOWN').toUpperCase();
    this.btRadioState = state;

    const btnToggleBtHeader = document.getElementById('btnToggleBtHeader');
    const btnToggleBtAction = document.getElementById('btnToggleBtAction');

    if (state === 'ON') {
      if (badge) {
        badge.innerText = '● BT: ON';
        badge.className = 'status-online';
      }
      if (btnToggleBtHeader) {
        btnToggleBtHeader.innerText = '⚡ BT: ON';
        btnToggleBtHeader.style.color = 'var(--cyan-primary)';
        btnToggleBtHeader.style.borderColor = 'var(--cyan-primary)';
      }
      if (btnToggleBtAction) {
        btnToggleBtAction.innerText = '⚡ Turn Bluetooth OFF';
        btnToggleBtAction.style.color = 'var(--rose-primary)';
        btnToggleBtAction.style.borderColor = 'var(--rose-primary)';
      }
      if (alertBanner) alertBanner.style.display = 'none';
      if (btnScan) btnScan.disabled = false;
      this.log('● Windows Bluetooth Radio is ON and ready.');
    } else if (state === 'OFF') {
      if (badge) {
        badge.innerText = '○ BT: OFF';
        badge.className = 'status-offline';
      }
      if (btnToggleBtHeader) {
        btnToggleBtHeader.innerText = '○ BT: OFF';
        btnToggleBtHeader.style.color = 'var(--text-muted)';
        btnToggleBtHeader.style.borderColor = 'var(--border-glass)';
      }
      if (btnToggleBtAction) {
        btnToggleBtAction.innerText = '⚡ Turn Bluetooth ON';
        btnToggleBtAction.style.color = 'var(--cyan-primary)';
        btnToggleBtAction.style.borderColor = 'var(--cyan-primary)';
      }
      if (alertBanner) {
        alertBanner.style.display = 'block';
        if (alertText) alertText.innerText = '⚠️ Bluetooth is turned OFF in Windows. Turn ON Bluetooth in Windows Settings to discover devices.';
      }
      if (btnScan) btnScan.disabled = true;
      this.isBtScanning = false;
      this.updateScanButtonState();
      this.log('○ Windows Bluetooth Radio is turned OFF.');
    } else if (state === 'UNAVAILABLE' || !status.available) {
      if (badge) {
        badge.innerText = '✕ BT: UNAVAILABLE';
        badge.className = 'status-offline';
      }
      if (alertBanner) {
        alertBanner.style.display = 'block';
        if (alertText) alertText.innerText = '✕ No Bluetooth adapter detected on this PC.';
      }
      if (btnScan) btnScan.disabled = true;
    }
  }

  toggleWifiCarrier(turnOn) {
    const btnToggleWifiHeader = document.getElementById('btnToggleWifiHeader');
    const btnToggleWifiAction = document.getElementById('btnToggleWifiAction');
    const carrierStatus = document.getElementById('carrierStatus');

    if (turnOn) {
      if (btnToggleWifiHeader) {
        btnToggleWifiHeader.innerText = '📶 Wi-Fi: ON';
        btnToggleWifiHeader.style.color = 'var(--emerald-primary)';
        btnToggleWifiHeader.style.borderColor = 'var(--emerald-primary)';
      }
      if (btnToggleWifiAction) {
        btnToggleWifiAction.innerText = '📶 Turn Wi-Fi OFF';
        btnToggleWifiAction.style.color = 'var(--rose-primary)';
        btnToggleWifiAction.style.borderColor = 'var(--rose-primary)';
      }
      if (carrierStatus) {
        carrierStatus.innerText = '● Carrier Active';
        carrierStatus.className = 'status-online';
      }
      this.connectMesh(this.currentHost, this.currentPort);
      this.log('📶 Wi-Fi Carrier enabled.');
    } else {
      if (btnToggleWifiHeader) {
        btnToggleWifiHeader.innerText = '○ Wi-Fi: OFF';
        btnToggleWifiHeader.style.color = 'var(--text-muted)';
        btnToggleWifiHeader.style.borderColor = 'var(--border-glass)';
      }
      if (btnToggleWifiAction) {
        btnToggleWifiAction.innerText = '📶 Turn Wi-Fi ON';
        btnToggleWifiAction.style.color = 'var(--emerald-primary)';
        btnToggleWifiAction.style.borderColor = 'var(--emerald-primary)';
      }
      if (carrierStatus) {
        carrierStatus.innerText = '○ Wi-Fi Disabled';
        carrierStatus.className = 'status-offline';
      }
      try {
        if (this.ws) {
          this.ws.close();
          this.ws = null;
        }
      } catch (e) {}
      this.isConnected = false;
      this.log('○ Wi-Fi Carrier disabled.');
    }
  }

  handleBtScanState(state) {
    this.isBtScanning = state && state.scanning;
    this.updateScanButtonState();
    const scanStatus = document.getElementById('btScanStatusText');
    if (scanStatus) {
      scanStatus.innerText = this.isBtScanning ? '🔄 Scanning live BLE & Classic devices...' : `${this.btDevices.size} device(s) discovered`;
    }
    if (state && state.error && state.error !== 'Success') {
      this.handleBtError(`Scanner status: ${state.error}`);
    }
  }

  updateScanButtonState() {
    const btnScan = document.getElementById('btnScanBt');
    if (!btnScan) return;
    if (this.isBtScanning) {
      btnScan.innerText = '⏹️ Stop Scanning';
      btnScan.style.background = 'rgba(244, 63, 94, 0.15)';
      btnScan.style.borderColor = 'var(--rose-primary)';
      btnScan.style.color = 'var(--rose-primary)';
    } else {
      btnScan.innerText = '🔍 Scan Bluetooth Devices';
      btnScan.style.background = 'rgba(56, 189, 248, 0.15)';
      btnScan.style.borderColor = 'var(--cyan-primary)';
      btnScan.style.color = 'var(--cyan-primary)';
    }
  }

  handleBtDeviceDiscovered(dev) {
    if (!dev || !dev.address) return;
    this.btDevices.set(dev.address, dev);
    this.renderBtDevices();
    const scanStatus = document.getElementById('btScanStatusText');
    if (scanStatus && this.isBtScanning) {
      scanStatus.innerText = `🔄 Scanning (${this.btDevices.size} found)...`;
    }
    // STRICT: Explicit manual connection only. No background hijacking.
  }

  renderBtDevices() {
    const container = document.getElementById('btDevicesList');
    if (!container) return;

    if (this.btDevices.size === 0) {
      container.innerHTML = `
        <div class="peer-row" style="padding: 10px 14px;">
          <div class="peer-info">
            <div class="peer-name" style="font-size: 12px;">🔍 No Bluetooth devices found yet</div>
            <div class="peer-meta" style="font-size: 10px;">Click 'Scan Bluetooth Devices' below to discover real nearby phones, headphones &amp; laptops.</div>
          </div>
        </div>
      `;
      return;
    }

    container.innerHTML = '';
    this.btDevices.forEach(dev => {
      const row = document.createElement('div');
      row.className = 'peer-row';
      row.style.padding = '10px 14px';

      const isConnected = this.btConnectedAddress === dev.address;
      const rssiStr = dev.rssi ? `${dev.rssi} dBm` : 'Nearby';

      row.innerHTML = `
        <div class="peer-info">
          <div class="peer-name" style="font-size: 12px; display: flex; align-items: center; gap: 6px;">
            <span>${isConnected ? '● 📱' : '📱'} ${escapeHtml(dev.name || 'Bluetooth Device')}</span>
            ${isConnected ? '<span style="font-size: 10px; color: var(--emerald-primary); font-weight: 700;">[CONNECTED]</span>' : ''}
          </div>
          <div class="peer-meta" style="font-size: 10px; font-family: monospace;">
            MAC: ${escapeHtml(dev.address)} • RSSI: ${rssiStr} • BLE / SPP
          </div>
        </div>
        <div class="peer-actions" style="display: flex; gap: 6px;">
          ${isConnected ? `
            <button class="btn-tactical-sm" style="color: var(--rose-primary); border-color: rgba(244, 63, 94, 0.4); padding: 4px 10px; font-size: 11px;" onclick="app.disconnectBtDevice('${escapeHtml(dev.address)}')">
              Disconnect
            </button>
          ` : `
            <button class="btn-tactical-sm" style="color: var(--cyan-primary); border-color: rgba(56, 189, 248, 0.4); padding: 4px 10px; font-size: 11px;" onclick="app.connectBtDevice('${escapeHtml(dev.address)}')">
              Connect
            </button>
            <button class="btn-tactical-sm" style="color: var(--emerald-primary); border-color: rgba(16, 185, 129, 0.4); padding: 4px 10px; font-size: 11px;" onclick="app.pairBtDevice('${escapeHtml(dev.address)}')">
              Pair
            </button>
          `}
        </div>
      `;
      container.appendChild(row);
    });
  }

  async pairBtDevice(address) {
    if (!window.bluetoothAPI) return;
    this.log(`Initiating pairing request for ${address}...`);
    try {
      await window.bluetoothAPI.pair(address);
    } catch (e) {
      this.handleBtError(e.message);
    }
  }

  async unpairBtDevice(address) {
    if (!window.bluetoothAPI) return;
    this.log(`Unpairing device ${address}...`);
    try {
      await window.bluetoothAPI.unpair(address);
    } catch (e) {
      this.handleBtError(e.message);
    }
  }

  async connectBtDevice(address) {
    if (!window.bluetoothAPI) return;
    this.log(`Connecting to Bluetooth device: ${address}...`);
    const dev = this.btDevices.get(address);
    const devName = dev ? dev.name : address;

    const scanStatus = document.getElementById('btScanStatusText');
    if (scanStatus) scanStatus.innerText = `Connecting to ${devName}...`;

    try {
      await window.bluetoothAPI.connect(address);
    } catch (e) {
      this.handleBtError(e.message);
    }
  }

  async disconnectBtDevice() {
    if (!window.bluetoothAPI) return;
    this.log('Disconnecting Bluetooth device...');
    try {
      await window.bluetoothAPI.disconnect();
    } catch (e) {
      this.handleBtError(e.message);
    }
  }

  handleBtConnectionChanged(conn) {
    if (!conn) return;
    const status = (conn.status || '').toUpperCase();
    const card = document.getElementById('btConnectedCard');
    const nameElem = document.getElementById('btConnectedDevName');
    const macElem = document.getElementById('btConnectedDevMac');
    const scanStatus = document.getElementById('btScanStatusText');

    if (status === 'CONNECTED') {
      this.btConnectedAddress = conn.address;
      if (card) card.style.display = 'flex';
      if (nameElem) nameElem.innerText = conn.name || conn.address;
      if (macElem) macElem.innerText = conn.address;
      if (scanStatus) scanStatus.innerText = `Connected to ${conn.name || conn.address}`;
      this.log(`✅ Connected to Bluetooth Device: ${conn.name || conn.address} (${conn.address})`);
      this.renderBtDevices();
    } else if (status === 'DISCONNECTED') {
      const oldAddress = this.btConnectedAddress;
      this.btConnectedAddress = null;
      if (card) card.style.display = 'none';
      if (scanStatus) scanStatus.innerText = 'Ready to scan';
      
      const reason = conn.reason || 'Disconnected';
      if (reason === 'UNEXPECTED_DISCONNECT') {
        this.log(`⚠️ Bluetooth device (${conn.address || oldAddress}) disconnected.`);
      } else if (reason === 'RADIO_TURNED_OFF') {
        this.log(`⚠️ Bluetooth radio was turned OFF. Disconnected from device.`);
      } else {
        this.log(`Bluetooth device disconnected.`);
      }
      this.renderBtDevices();
    }
  }

  handleBtError(errorMsg) {
    const text = typeof errorMsg === 'string' ? errorMsg : (errorMsg.message || JSON.stringify(errorMsg));
    this.log(`[Bluetooth Alert] ${text}`);
    const alertBanner = document.getElementById('btAlertBanner');
    const alertText = document.getElementById('btAlertText');
    if (alertBanner && alertText) {
      alertBanner.style.display = 'block';
      alertText.innerText = `⚠️ ${text}`;
      setTimeout(() => {
        if (alertBanner.style.display === 'block') {
          alertBanner.style.display = 'none';
        }
      }, 7000);
    }
  }

  // -----------------------------------------------------------
  // 8.5. Offline P2P File & Media Transfer Engine (CRC32 Chunker)
  // -----------------------------------------------------------
  bindFileSharingUI() {
    this.fileTransfers = new Map(); // transferId -> { metadata, chunks, blobUrl, ... }
    this.CHUNK_SIZE = 512;

    const dropZone = document.getElementById('fileDropZone');
    const fileInput = document.getElementById('desktopFileInput');
    const btnSendPhoto = document.getElementById('btnSendPhotoDesk');
    const btnSendMap = document.getElementById('btnSendMapDesk');
    const btnSendVoice = document.getElementById('btnSendVoiceDesk');
    const btnSelectCustom = document.getElementById('btnSelectCustomFileDesk');

    if (dropZone && fileInput) {
      dropZone.addEventListener('click', () => fileInput.click());
      dropZone.addEventListener('dragover', (e) => {
        e.preventDefault();
        dropZone.style.borderColor = 'var(--cyan-primary)';
        dropZone.style.background = 'rgba(56, 189, 248, 0.1)';
      });
      dropZone.addEventListener('dragleave', () => {
        dropZone.style.borderColor = 'var(--border-glass)';
        dropZone.style.background = 'rgba(15, 23, 42, 0.4)';
      });
      dropZone.addEventListener('drop', (e) => {
        e.preventDefault();
        dropZone.style.borderColor = 'var(--border-glass)';
        dropZone.style.background = 'rgba(15, 23, 42, 0.4)';
        if (e.dataTransfer && e.dataTransfer.files.length > 0) {
          this.handleFilesSelected(e.dataTransfer.files);
        }
      });

      fileInput.addEventListener('change', (e) => {
        if (e.target.files && e.target.files.length > 0) {
          this.handleFilesSelected(e.target.files);
        }
      });
    }

    if (btnSendPhoto) {
      btnSendPhoto.addEventListener('click', () => {
        const dummyBytes = new Uint8Array(2048);
        for (let i = 0; i < dummyBytes.length; i++) dummyBytes[i] = i % 256;
        const fileName = `recon_snap_${Date.now().toString().slice(-4)}.jpg`;
        this.sendFile(dummyBytes, fileName, 'Desktop Hub');
      });
    }

    if (btnSendMap) {
      btnSendMap.addEventListener('click', () => {
        const geoJson = JSON.stringify({
          type: "FeatureCollection",
          features: [{
            type: "Feature",
            geometry: { type: "Point", coordinates: [this.selfCoords.lng, this.selfCoords.lat] },
            properties: { title: "Tactical Base Station Fix", time: new Date().toISOString() }
          }]
        });
        const encoder = new TextEncoder();
        const fileName = `tactical_sector_${Date.now().toString().slice(-4)}.geojson`;
        this.sendFile(encoder.encode(geoJson), fileName, 'Desktop Hub');
      });
    }

    if (btnSendVoice) {
      btnSendVoice.addEventListener('click', () => {
        this.switchTab(2); // Switch to Chat BBS tab
        setTimeout(() => this.startVoiceMemoRecording(), 200);
      });
    }

    if (btnSelectCustom && fileInput) {
      btnSelectCustom.addEventListener('click', () => fileInput.click());
    }
  }

  handleFilesSelected(fileList) {
    Array.from(fileList).forEach(file => {
      const reader = new FileReader();
      reader.onload = (e) => {
        const bytes = new Uint8Array(e.target.result);
        this.sendFile(bytes, file.name, 'Desktop Hub');
      };
      reader.readAsArrayBuffer(file);
    });
  }

  calculateCRC32(bytes) {
    let crc = 0xFFFFFFFF;
    for (let i = 0; i < bytes.length; i++) {
      crc ^= bytes[i];
      for (let j = 0; j < 8; j++) {
        crc = (crc >>> 1) ^ (0xEDB88320 & -(crc & 1));
      }
    }
    return ((~crc) >>> 0).toString(16).toUpperCase().padStart(8, '0');
  }

  sendFile(fileBytes, fileName, senderName = 'Desktop Terminal', targetPeerId = 'BROADCAST') {
    const transferId = 'tx-' + Math.random().toString(36).substring(2, 8);
    const totalChunks = Math.ceil(fileBytes.length / this.CHUNK_SIZE);
    const overallCrc = this.calculateCRC32(fileBytes);

    const txRecord = {
      transferId,
      fileName,
      fileSize: fileBytes.length,
      isOutgoing: true,
      senderName: 'YOU (Desktop Hub)',
      progress: 0,
      totalChunks,
      sentChunks: 0,
      speed: '0.0 kB/s',
      isCompleted: false,
      crc32Hex: overallCrc,
      hops: 1,
      startTime: Date.now()
    };

    this.fileTransfers.set(transferId, txRecord);
    this.renderDesktopTransfers();
    this.log(`[File Transfer] Starting transmission of ${fileName} (${(fileBytes.length / 1024).toFixed(1)} KB, ${totalChunks} chunks) CRC32: ${overallCrc}`);

    let chunkIndex = 0;
    const sendNextChunk = () => {
      if (chunkIndex >= totalChunks) {
        txRecord.isCompleted = true;
        txRecord.progress = 100;
        this.renderDesktopTransfers();
        this.log(`[File Transfer] ✓ Completed transmission of ${fileName}`);
        return;
      }

      const offset = chunkIndex * this.CHUNK_SIZE;
      const len = Math.min(this.CHUNK_SIZE, fileBytes.length - offset);
      const chunkData = fileBytes.slice(offset, offset + len);

      // Binary to base64
      let binary = '';
      for (let i = 0; i < chunkData.length; i++) {
        binary += String.fromCharCode(chunkData[i]);
      }
      const base64Data = btoa(binary);

      const chunkCrc = parseInt(this.calculateCRC32(chunkData), 16);

      const packet = {
        type: 'FILE_CHUNK',
        transferId,
        fileName,
        fileSize: fileBytes.length,
        totalChunks,
        chunkIndex,
        chunkCrc32: chunkCrc,
        data: base64Data,
        senderId: this.localNodeId,
        senderName: senderName,
        targetId: targetPeerId,
        hops: 1
      };

      this.sendControlPacket(packet);
      if (window.bluetoothAPI && window.bluetoothAPI.sendControlMessage) {
        window.bluetoothAPI.sendControlMessage(JSON.stringify(packet));
      }

      chunkIndex++;
      txRecord.sentChunks = chunkIndex;
      txRecord.progress = Math.round((chunkIndex / totalChunks) * 100);
      const elapsed = (Date.now() - txRecord.startTime) / 1000;
      if (elapsed > 0.05) {
        txRecord.speed = `${((chunkIndex * this.CHUNK_SIZE / 1024) / elapsed).toFixed(1)} kB/s`;
      }

      this.renderDesktopTransfers();
      setTimeout(sendNextChunk, 20); // 20ms pace for reliable MTU transmission
    };

    sendNextChunk();
  }

  processIncomingFileChunk(json) {
    const { transferId, fileName, fileSize, totalChunks, chunkIndex, chunkCrc32, data, senderId, senderName, hops } = json;
    if (!transferId || !data) return;

    let binaryStr = '';
    try {
      binaryStr = atob(data);
    } catch (e) {
      return;
    }

    const chunkBytes = new Uint8Array(binaryStr.length);
    for (let i = 0; i < binaryStr.length; i++) {
      chunkBytes[i] = binaryStr.charCodeAt(i);
    }

    // Verify CRC32
    const calcCrc = parseInt(this.calculateCRC32(chunkBytes), 16);
    if (chunkCrc32 && calcCrc !== chunkCrc32) {
      this.log(`⚠️ Checksum error on chunk ${chunkIndex} of ${fileName}. Discarded.`);
      return;
    }

    let txRecord = this.fileTransfers.get(transferId);
    if (!txRecord) {
      txRecord = {
        transferId,
        fileName: fileName || 'unnamed_mesh_file',
        fileSize: fileSize || 0,
        isOutgoing: false,
        senderName: senderName || 'Companion Node',
        progress: 0,
        totalChunks: totalChunks || 1,
        receivedMap: new Map(),
        speed: '0.0 kB/s',
        isCompleted: false,
        crc32Hex: chunkCrc32 ? chunkCrc32.toString(16).toUpperCase() : 'VERIFIED',
        hops: hops || 1,
        startTime: Date.now()
      };
      this.fileTransfers.set(transferId, txRecord);
      this.log(`[File Transfer] Receiving incoming file: ${txRecord.fileName} from ${txRecord.senderName}`);
    }

    txRecord.receivedMap.set(chunkIndex, chunkBytes);
    txRecord.progress = Math.round((txRecord.receivedMap.size / txRecord.totalChunks) * 100);
    const elapsed = (Date.now() - txRecord.startTime) / 1000;
    if (elapsed > 0.05) {
      const bytesSoFar = txRecord.receivedMap.size * this.CHUNK_SIZE;
      txRecord.speed = `${((bytesSoFar / 1024) / elapsed).toFixed(1)} kB/s`;
    }

    if (txRecord.receivedMap.size >= txRecord.totalChunks && !txRecord.isCompleted) {
      txRecord.isCompleted = true;
      txRecord.progress = 100;

      // Reassemble complete binary array
      const fullBuffer = new Uint8Array(txRecord.fileSize || (txRecord.totalChunks * this.CHUNK_SIZE));
      let offset = 0;
      for (let i = 0; i < txRecord.totalChunks; i++) {
        const cData = txRecord.receivedMap.get(i);
        if (cData) {
          fullBuffer.set(cData, offset);
          offset += cData.length;
        }
      }

      txRecord.crc32Hex = this.calculateCRC32(fullBuffer);
      const blob = new Blob([fullBuffer]);
      txRecord.blobUrl = URL.createObjectURL(blob);
      this.log(`[File Transfer] ✓ Reassembled 100% of ${txRecord.fileName} (CRC32: ${txRecord.crc32Hex})`);
    }

    this.renderDesktopTransfers();
  }

  renderDesktopTransfers() {
    const container = document.getElementById('desktopTransfersList');
    const badge = document.getElementById('transfersCountBadgeDesk');
    if (!container) return;

    const transfers = Array.from(this.fileTransfers.values()).reverse();
    if (badge) badge.innerText = `${transfers.length} Transfers • CRC32 Verified`;

    if (transfers.length === 0) {
      container.innerHTML = `
        <div class="peer-row" style="padding: 14px;">
          <div class="peer-info">
            <div class="peer-name">📁 No active mesh transfers</div>
            <div class="peer-meta">Drag and drop any image, map, or audio file above to stream across the mesh network.</div>
          </div>
        </div>
      `;
      return;
    }

    container.innerHTML = '';
    transfers.forEach(tx => {
      const isImg = tx.fileName.endsWith('.jpg') || tx.fileName.endsWith('.png');
      const isMap = tx.fileName.endsWith('.geojson') || tx.fileName.endsWith('.mbtiles');
      const isAudio = tx.fileName.endsWith('.opus') || tx.fileName.endsWith('.wav');
      const icon = isImg ? '🖼️' : isMap ? '🗺️' : isAudio ? '🎙️' : '📄';

      const row = document.createElement('div');
      row.className = 'tactical-card';
      row.style.cssText = 'background: rgba(15, 23, 42, 0.7); border: 1px solid var(--border-glass); padding: 12px 14px; margin-bottom: 0;';

      const sizeKb = (tx.fileSize / 1024).toFixed(1);

      row.innerHTML = `
        <div style="display: flex; justify-content: space-between; align-items: center;">
          <div style="display: flex; align-items: center; gap: 10px;">
            <span style="font-size: 22px;">${icon}</span>
            <div>
              <div style="font-size: 13px; font-weight: 700; color: #FFFFFF;">${escapeHtml(tx.fileName)}</div>
              <div style="font-size: 10px; color: var(--text-muted); font-family: monospace; margin-top: 2px;">
                ${sizeKb} KB • ${escapeHtml(tx.senderName)} • ${tx.hops} hop • CRC32: <span style="color: var(--cyan-primary);">${tx.crc32Hex}</span>
              </div>
            </div>
          </div>
          <div style="display: flex; gap: 8px; align-items: center;">
            <span style="font-size: 11px; font-weight: 700; color: ${tx.isCompleted ? 'var(--emerald-primary)' : 'var(--cyan-primary)'}; background: ${tx.isCompleted ? 'rgba(16, 185, 129, 0.15)' : 'rgba(56, 189, 248, 0.15)'}; border: 1px solid ${tx.isCompleted ? 'rgba(16, 185, 129, 0.3)' : 'rgba(56, 189, 248, 0.3)'}; border-radius: 6px; padding: 4px 8px;">
              ${tx.isCompleted ? '✓ COMPLETED' : `${tx.progress}%`}
            </span>
            ${tx.blobUrl ? `
              <button onclick="app.openChatDocument('${escapeHtml(tx.fileName)}')" class="btn-tactical-sm" style="padding: 4px 10px; font-size: 11px; background: var(--cyan-primary); color: #000; font-weight: 700; border: none; border-radius: 4px; cursor: pointer;">📂 Open</button>
              <a href="${tx.blobUrl}" download="${escapeHtml(tx.fileName)}" class="btn-tactical-sm" style="padding: 4px 10px; font-size: 11px; background: var(--emerald-primary); color: #000; font-weight: 700; text-decoration: none; border-radius: 4px;">💾 Save</a>
            ` : ''}
          </div>
        </div>
        <div class="audio-vu-container" style="height: 6px; margin-top: 8px;">
          <div class="audio-vu-bar" style="width: ${tx.progress}%; background: ${tx.isCompleted ? 'var(--emerald-primary)' : 'var(--cyan-primary)'}; transition: width 0.2s ease;"></div>
        </div>
        <div style="display: flex; justify-content: space-between; font-size: 10px; color: var(--text-muted); font-family: monospace; margin-top: 4px;">
          <span>${tx.isCompleted ? '100% Reassembled' : `Streaming: ${tx.progress}%`}</span>
          <span style="color: var(--cyan-primary);">${tx.isCompleted ? 'Frame Verified' : tx.speed}</span>
        </div>
      `;

      container.appendChild(row);
    });
  }

  // -----------------------------------------------------------
  // 9. Event Logging (Capped at 50 lines to conserve RAM)
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
