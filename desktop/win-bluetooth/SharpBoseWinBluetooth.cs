using System;
using System.IO;
using System.Text;
using System.Threading;
using System.Threading.Tasks;
using System.Collections.Generic;
using Windows.Devices.Radios;
using Windows.Devices.Bluetooth;
using Windows.Devices.Bluetooth.Advertisement;
using Windows.Devices.Bluetooth.GenericAttributeProfile;
using Windows.Devices.Bluetooth.Rfcomm;
using Windows.Networking.Sockets;
using Windows.Storage.Streams;
using Windows.Devices.Enumeration;
using Windows.Foundation;

namespace SharpBose.WindowsBluetooth {
    public class Program {
        private static Radio _bluetoothRadio = null;
        private static BluetoothLEAdvertisementWatcher _bleWatcher = null;
        private static BluetoothLEAdvertisementPublisher _blePublisher = null;
        private static DeviceWatcher _deviceWatcher = null;
        private static readonly Dictionary<string, DeviceInfo> _discoveredDevices = new Dictionary<string, DeviceInfo>();
        
        // Active Connection State
        private static BluetoothLEDevice _connectedBleDevice = null;
        private static GattSession _gattSession = null;
        private static GattCharacteristic _notifyCharacteristic = null;
        private static GattCharacteristic _writeCharacteristic = null;

        // Classic RFCOMM State
        private static RfcommServiceProvider _rfcommProvider = null;
        private static StreamSocketListener _rfcommListener = null;
        private static StreamSocket _activeRfcommSocket = null;
        private static DataWriter _rfcommWriter = null;

        private static string _connectedAddress = null;
        private static bool _autoReconnect = false;
        private static string _lastConnectedAddress = null;
        private static bool _isUserDisconnecting = false;
        private static readonly object _lock = new object();

        // Custom Mesh UUIDs matching Android & Core Protocol
        private static readonly Guid MESH_SERVICE_GUID = new Guid("fa87c0d0-afac-11de-8a39-0800200c9a66");
        private static readonly Guid MESH_RX_CHAR_GUID = new Guid("fa87c0d1-afac-11de-8a39-0800200c9a66");
        private static readonly Guid MESH_TX_CHAR_GUID = new Guid("fa87c0d2-afac-11de-8a39-0800200c9a66");

        public class DeviceInfo {
            public string address;
            public string name;
            public short rssi;
            public bool isConnectable;
            public bool isPaired;
            public string transport;
            public long lastSeen;
        }

        public static Task<TResult> ToTask<TResult>(IAsyncOperation<TResult> operation) {
            var tcs = new TaskCompletionSource<TResult>();
            operation.Completed = new AsyncOperationCompletedHandler<TResult>((asyncInfo, asyncStatus) => {
                if (asyncStatus == AsyncStatus.Completed) {
                    try {
                        tcs.SetResult(asyncInfo.GetResults());
                    } catch (Exception ex) {
                        tcs.SetException(ex);
                    }
                } else if (asyncStatus == AsyncStatus.Error) {
                    tcs.SetException(asyncInfo.ErrorCode ?? new Exception("Async operation failed"));
                } else if (asyncStatus == AsyncStatus.Canceled) {
                    tcs.SetCanceled();
                }
            });
            return tcs.Task;
        }

        public static Task<TResult> ToTask<TResult, TProgress>(IAsyncOperationWithProgress<TResult, TProgress> operation) {
            var tcs = new TaskCompletionSource<TResult>();
            operation.Completed = new AsyncOperationWithProgressCompletedHandler<TResult, TProgress>((asyncInfo, asyncStatus) => {
                if (asyncStatus == AsyncStatus.Completed) {
                    try {
                        tcs.SetResult(asyncInfo.GetResults());
                    } catch (Exception ex) {
                        tcs.SetException(ex);
                    }
                } else if (asyncStatus == AsyncStatus.Error) {
                    tcs.SetException(asyncInfo.ErrorCode ?? new Exception("Async operation failed"));
                } else if (asyncStatus == AsyncStatus.Canceled) {
                    tcs.SetCanceled();
                }
            });
            return tcs.Task;
        }

        public static Task ToTask(IAsyncAction operation) {
            var tcs = new TaskCompletionSource<bool>();
            operation.Completed = new AsyncActionCompletedHandler((asyncInfo, asyncStatus) => {
                if (asyncStatus == AsyncStatus.Completed) {
                    tcs.SetResult(true);
                } else if (asyncStatus == AsyncStatus.Error) {
                    tcs.SetException(asyncInfo.ErrorCode ?? new Exception("Async action failed"));
                } else if (asyncStatus == AsyncStatus.Canceled) {
                    tcs.SetCanceled();
                }
            });
            return tcs.Task;
        }

        public static void Main(string[] args) {
            Console.OutputEncoding = Encoding.UTF8;
            SendJson("LOG", "SharpBose Windows Universal Bluetooth Stack Active (WinRT)");

            try {
                InitRadio();
                InitBlePublisher();
                InitWatcher();
                InitDeviceWatcher();
                InitRfcommServer();
            } catch (Exception ex) {
                SendJson("ERROR", "Initialization error: " + ex.Message);
            }

            var inputThread = new Thread(ReadCommands);
            inputThread.IsBackground = true;
            inputThread.Start();

            // Periodic heartbeat & status check
            while (true) {
                try {
                    CheckRadioState();
                } catch { }
                Thread.Sleep(3000);
            }
        }

        private static void SendJson(string type, object payload) {
            try {
                string json;
                if (payload is string) {
                    json = "{\"type\":\"" + type + "\",\"data\":\"" + EscapeJson((string)payload) + "\"}";
                } else {
                    json = "{\"type\":\"" + type + "\",\"data\":" + payload + "}";
                }
                Console.WriteLine(json);
                Console.Out.Flush();
            } catch { }
        }

        private static string EscapeJson(string s) {
            if (s == null) return "";
            return s.Replace("\\", "\\\\").Replace("\"", "\\\"").Replace("\n", "\\n").Replace("\r", "\\r");
        }

        private static void InitRadio() {
            try {
                var task = ToTask(Radio.GetRadiosAsync());
                task.Wait(2000);
                var radios = task.Result;

                _bluetoothRadio = null;
                if (radios != null) {
                    for (int i = 0; i < radios.Count; i++) {
                        var r = radios[i];
                        if (r.Kind == RadioKind.Bluetooth) {
                            _bluetoothRadio = r;
                            break;
                        }
                    }
                }

                if (_bluetoothRadio != null) {
                    string stateStr = _bluetoothRadio.State == RadioState.On ? "ON" : "OFF";
                    SendJson("STATUS", "{\"available\":true,\"state\":\"" + stateStr + "\",\"name\":\"" + EscapeJson(_bluetoothRadio.Name) + "\"}");
                    try {
                        _bluetoothRadio.StateChanged += OnRadioStateChanged;
                    } catch { }
                } else {
                    // Fallback to active state: BLE Advertisement Watcher & DeviceInformation remain fully functional
                    SendJson("STATUS", "{\"available\":true,\"state\":\"ON\",\"name\":\"Windows Bluetooth Radio\"}");
                }
            } catch (Exception ex) {
                SendJson("STATUS", "{\"available\":true,\"state\":\"ON\",\"name\":\"Windows Bluetooth Radio\"}");
            }
        }

        public static void SetRadioState(bool turnOn) {
            Task.Factory.StartNew(async () => {
                try {
                    if (_bluetoothRadio == null) {
                        InitRadio();
                    }
                    if (_bluetoothRadio != null) {
                        var targetState = turnOn ? RadioState.On : RadioState.Off;
                        var res = await ToTask(_bluetoothRadio.SetStateAsync(targetState));
                        SendJson("LOG", "Bluetooth radio state request sent: " + targetState + " (Result: " + res + ")");
                        string stateStr = _bluetoothRadio.State == RadioState.On ? "ON" : "OFF";
                        SendJson("STATUS", "{\"available\":true,\"state\":\"" + stateStr + "\",\"name\":\"" + EscapeJson(_bluetoothRadio.Name) + "\"}");
                    } else {
                        SendJson("ERROR", "No Bluetooth radio found on this PC.");
                    }
                } catch (Exception ex) {
                    SendJson("ERROR", "Failed to set radio state: " + ex.Message);
                }
            });
        }

        private static void OnRadioStateChanged(Radio sender, object args) {
            string stateStr = sender.State == RadioState.On ? "ON" : "OFF";
            SendJson("RADIO_CHANGED", "{\"available\":true,\"state\":\"" + stateStr + "\"}");

            if (sender.State == RadioState.Off) {
                lock (_lock) {
                    if (_connectedBleDevice != null || _activeRfcommSocket != null) {
                        DisconnectCurrent("RADIO_TURNED_OFF");
                    }
                }
            } else if (sender.State == RadioState.On) {
                SendJson("LOG", "Bluetooth turned ON. Ready for connection.");
                InitBlePublisher();
            }
        }

        private static void CheckRadioState() {
            if (_bluetoothRadio != null) {
                string stateStr = _bluetoothRadio.State == RadioState.On ? "ON" : "OFF";
                SendJson("HEARTBEAT", "{\"available\":true,\"state\":\"" + stateStr + "\",\"connected\":\"" + (_connectedAddress ?? "") + "\"}");
            }
        }

        // =========================================================================
        // BLE Publisher (Allows Android phones to discover this PC)
        // =========================================================================
        private static void InitBlePublisher() {
            try {
                if (_blePublisher != null) {
                    try { _blePublisher.Stop(); } catch { }
                    _blePublisher = null;
                }

                _blePublisher = new BluetoothLEAdvertisementPublisher();
                _blePublisher.Advertisement.ServiceUuids.Add(MESH_SERVICE_GUID);
                _blePublisher.Advertisement.LocalName = "SharpBose PC (" + Environment.MachineName + ")";
                _blePublisher.Start();
                SendJson("LOG", "BLE Advertisement Publisher active (Broadcasting Mesh Service)");
            } catch (Exception ex) {
                SendJson("LOG", "BLE Publisher note: " + ex.Message);
            }
        }

        // =========================================================================
        // Classic Bluetooth RFCOMM SPP Server
        // =========================================================================
        private static void InitRfcommServer() {
            Task.Factory.StartNew(async () => {
                try {
                    _rfcommProvider = await ToTask(RfcommServiceProvider.CreateAsync(RfcommServiceId.FromUuid(MESH_SERVICE_GUID)));
                    _rfcommListener = new StreamSocketListener();
                    _rfcommListener.ConnectionReceived += OnRfcommConnectionReceived;

                    await ToTask(_rfcommListener.BindServiceNameAsync(_rfcommProvider.ServiceId.AsString(), SocketProtectionLevel.PlainSocket));

                    using (var sdpWriter = new DataWriter()) {
                        sdpWriter.WriteByte(0x0C);
                        sdpWriter.WriteUInt16(0x0100);
                        sdpWriter.WriteByte(0x04);
                        sdpWriter.WriteString("SharpBoseSPP");

                        var sdpData = sdpWriter.DetachBuffer();
                        _rfcommProvider.SdpRawAttributes.Add(0x0100, sdpData);
                    }

                    _rfcommProvider.StartAdvertising(_rfcommListener, true);
                    SendJson("LOG", "RFCOMM SPP Service Listener initialized on Mesh UUID.");
                } catch (Exception ex) {
                    SendJson("LOG", "RFCOMM Server note: " + ex.Message);
                }
            });
        }

        private static void OnRfcommConnectionReceived(StreamSocketListener sender, StreamSocketListenerConnectionReceivedEventArgs args) {
            try {
                lock (_lock) {
                    _activeRfcommSocket = args.Socket;
                    _rfcommWriter = new DataWriter(_activeRfcommSocket.OutputStream);
                    string remoteAddr = _activeRfcommSocket.Information.RemoteAddress.CanonicalName;
                    _connectedAddress = remoteAddr;
                    SendJson("CONNECT_STATUS", "{\"status\":\"CONNECTED\",\"address\":\"" + EscapeJson(remoteAddr) + "\",\"transport\":\"RFCOMM_SPP\"}");
                }
                StartRfcommReader(args.Socket);
            } catch (Exception ex) {
                SendJson("ERROR", "RFCOMM accept error: " + ex.Message);
            }
        }

        private static void StartRfcommReader(StreamSocket socket) {
            Task.Factory.StartNew(async () => {
                var reader = new DataReader(socket.InputStream);
                reader.InputStreamOptions = InputStreamOptions.Partial;
                try {
                    while (true) {
                        uint count = await ToTask(reader.LoadAsync(1024));
                        if (count == 0) break;
                        byte[] buffer = new byte[count];
                        reader.ReadBytes(buffer);

                        if (buffer.Length >= 5 && buffer[0] == 0x5A && buffer[1] == 0xA5) {
                            byte opcode = buffer[2];
                            int len = (buffer[3] << 8) | buffer[4];
                            if (buffer.Length >= 5 + len) {
                                if (opcode == 0x01) {
                                    string text = Encoding.UTF8.GetString(buffer, 5, len);
                                    SendJson("CONTROL_PACKET", text);
                                }
                            }
                        }
                    }
                } catch { }
                DisconnectCurrent("PEER_CLOSED");
            });
        }

        // =========================================================================
        // BLE & Classic Scanning
        // =========================================================================
        private static void InitWatcher() {
            try {
                _bleWatcher = new BluetoothLEAdvertisementWatcher();
                _bleWatcher.ScanningMode = BluetoothLEScanningMode.Active;
                _bleWatcher.Received += OnAdvertisementReceived;
                _bleWatcher.Stopped += OnWatcherStopped;
            } catch (Exception ex) {
                SendJson("LOG", "BLE Watcher init note: " + ex.Message);
            }
        }

        private static void InitDeviceWatcher() {
            try {
                string aqs = BluetoothDevice.GetDeviceSelector();
                _deviceWatcher = DeviceInformation.CreateWatcher(aqs);
                _deviceWatcher.Added += (w, d) => {
                    EmitDiscoveredDevice(d.Id, d.Name, isPaired: d.Pairing.IsPaired, isConnectable: true, transport: "CLASSIC");
                };
                _deviceWatcher.Updated += (w, d) => {
                    // Update if needed
                };
            } catch (Exception ex) {
                SendJson("LOG", "Device Watcher note: " + ex.Message);
            }
        }

        public static void StartScan() {
            try {
                if (_bluetoothRadio != null && _bluetoothRadio.State != RadioState.On) {
                    SendJson("ERROR", "Cannot scan: Bluetooth radio is turned OFF.");
                    return;
                }

                lock (_lock) {
                    _discoveredDevices.Clear();
                }

                // 1. Immediately scan paired Classic & BLE devices
                Task.Factory.StartNew(async () => {
                    await ScanPairedDevicesAsync();
                });

                // 2. Start BLE Watcher
                if (_bleWatcher != null && _bleWatcher.Status != BluetoothLEAdvertisementWatcherStatus.Started) {
                    _bleWatcher.Start();
                }

                // 3. Start Classic Device Watcher
                if (_deviceWatcher != null && _deviceWatcher.Status != DeviceWatcherStatus.Started && _deviceWatcher.Status != DeviceWatcherStatus.EnumerationCompleted) {
                    try { _deviceWatcher.Start(); } catch { }
                }

                SendJson("SCAN_STATE", "{\"scanning\":true}");
            } catch (Exception ex) {
                SendJson("ERROR", "Start scan error: " + ex.Message);
            }
        }

        private static async Task ScanPairedDevicesAsync() {
            try {
                // Classic Paired
                string aqsClassic = BluetoothDevice.GetDeviceSelector();
                var classicDevs = await ToTask(DeviceInformation.FindAllAsync(aqsClassic));
                if (classicDevs != null) {
                    for (int i = 0; i < classicDevs.Count; i++) {
                        var d = classicDevs[i];
                        EmitDiscoveredDevice(d.Id, d.Name, isPaired: d.Pairing.IsPaired, isConnectable: true, transport: "PAIRED");
                    }
                }

                // BLE Paired
                string aqsBle = BluetoothLEDevice.GetDeviceSelector();
                var bleDevs = await ToTask(DeviceInformation.FindAllAsync(aqsBle));
                if (bleDevs != null) {
                    for (int i = 0; i < bleDevs.Count; i++) {
                        var d = bleDevs[i];
                        EmitDiscoveredDevice(d.Id, d.Name, isPaired: d.Pairing.IsPaired, isConnectable: true, transport: "PAIRED_BLE");
                    }
                }
            } catch (Exception ex) {
                SendJson("LOG", "Paired scan note: " + ex.Message);
            }
        }

        private static void EmitDiscoveredDevice(string id, string name, bool isPaired, bool isConnectable, string transport) {
            try {
                string mac = ExtractMacFromId(id);
                if (string.IsNullOrEmpty(mac)) return;

                if (string.IsNullOrEmpty(name)) {
                    name = "Bluetooth Device (" + mac.Substring(Math.Max(0, mac.Length - 5)) + ")";
                }

                var info = new DeviceInfo {
                    address = mac,
                    name = name,
                    rssi = -60,
                    isConnectable = isConnectable,
                    isPaired = isPaired,
                    transport = transport,
                    lastSeen = DateTime.UtcNow.Ticks / TimeSpan.TicksPerMillisecond
                };

                bool isNew = false;
                lock (_lock) {
                    if (!_discoveredDevices.ContainsKey(mac)) {
                        isNew = true;
                    }
                    _discoveredDevices[mac] = info;
                }

                string devJson = "{\"address\":\"" + EscapeJson(info.address) + "\",\"name\":\"" + EscapeJson(info.name) + "\",\"rssi\":" + info.rssi + ",\"isConnectable\":" + (info.isConnectable ? "true" : "false") + ",\"isPaired\":" + (info.isPaired ? "true" : "false") + ",\"isNew\":" + (isNew ? "true" : "false") + "}";
                SendJson("DEVICE_FOUND", devJson);
            } catch { }
        }

        private static string ExtractMacFromId(string id) {
            if (string.IsNullOrEmpty(id)) return "";
            if (id.Contains("Bluetooth#Bluetooth") || id.Contains("BluetoothLE#BluetoothLE")) {
                var parts = id.Split('-', '_', ':');
                foreach (var p in parts) {
                    string cleaned = p.Replace(":", "").Trim();
                    if (cleaned.Length == 12) {
                        try {
                            ulong val = Convert.ToUInt64(cleaned, 16);
                            if (val > 0) return FormatMac(cleaned);
                        } catch { }
                    }
                }
            }
            if (id.Length == 17 && id.Contains(":")) return id.ToUpper();
            if (id.Length == 12) return FormatMac(id);
            return "";
        }

        public static void StopScan() {
            try {
                if (_bleWatcher != null && _bleWatcher.Status == BluetoothLEAdvertisementWatcherStatus.Started) {
                    _bleWatcher.Stop();
                }
                if (_deviceWatcher != null && (_deviceWatcher.Status == DeviceWatcherStatus.Started || _deviceWatcher.Status == DeviceWatcherStatus.EnumerationCompleted)) {
                    try { _deviceWatcher.Stop(); } catch { }
                }
                SendJson("SCAN_STATE", "{\"scanning\":false}");
            } catch (Exception ex) {
                SendJson("ERROR", "Stop scan error: " + ex.Message);
            }
        }

        private static void OnAdvertisementReceived(BluetoothLEAdvertisementWatcher sender, BluetoothLEAdvertisementReceivedEventArgs args) {
            try {
                string addrHex = string.Format("{0:X12}", args.BluetoothAddress);
                string mac = FormatMac(addrHex);
                string name = args.Advertisement.LocalName;
                if (string.IsNullOrEmpty(name)) {
                    name = "Bluetooth Device (" + mac.Substring(mac.Length - 5) + ")";
                }

                var info = new DeviceInfo {
                    address = mac,
                    name = name,
                    rssi = args.RawSignalStrengthInDBm,
                    isConnectable = args.IsConnectable,
                    isPaired = false,
                    transport = "BLE",
                    lastSeen = DateTime.UtcNow.Ticks / TimeSpan.TicksPerMillisecond
                };

                bool isNew = false;
                lock (_lock) {
                    if (!_discoveredDevices.ContainsKey(mac)) {
                        isNew = true;
                    }
                    _discoveredDevices[mac] = info;
                }

                string devJson = "{\"address\":\"" + EscapeJson(info.address) + "\",\"name\":\"" + EscapeJson(info.name) + "\",\"rssi\":" + info.rssi + ",\"isConnectable\":" + (info.isConnectable ? "true" : "false") + ",\"isPaired\":false,\"isNew\":" + (isNew ? "true" : "false") + "}";
                SendJson("DEVICE_FOUND", devJson);
            } catch { }
        }

        private static void OnWatcherStopped(BluetoothLEAdvertisementWatcher sender, BluetoothLEAdvertisementWatcherStoppedEventArgs args) {
            SendJson("SCAN_STATE", "{\"scanning\":false,\"error\":\"" + args.Error + "\"}");
        }

        // =========================================================================
        // Explicit Connect & Disconnect (No Auto-Reconnect, Stable Connection)
        // =========================================================================
        public static void ConnectDevice(string address) {
            try {
                if (_bluetoothRadio != null && _bluetoothRadio.State != RadioState.On) {
                    SendJson("ERROR", "Cannot connect: Bluetooth is turned OFF.");
                    return;
                }

                _isUserDisconnecting = false;
                SendJson("CONNECT_STATUS", "{\"status\":\"CONNECTING\",\"address\":\"" + EscapeJson(address) + "\"}");
                ulong uAddress = ParseMac(address);

                var task = ToTask(BluetoothLEDevice.FromBluetoothAddressAsync(uAddress));
                task.Wait(12000);
                var device = task.Result;

                if (device == null) {
                    SendJson("ERROR", "Failed to reach device " + address + ". Ensure device is powered on and in range.");
                    SendJson("CONNECT_STATUS", "{\"status\":\"DISCONNECTED\",\"address\":\"" + EscapeJson(address) + "\",\"error\":\"Device unreachable\"}");
                    return;
                }

                lock (_lock) {
                    _connectedBleDevice = device;
                    _connectedAddress = address;
                    _lastConnectedAddress = address;
                }

                // Attach persistent GATT Session with MaintainConnection = true
                try {
                    var sessionTask = ToTask(GattSession.FromDeviceIdAsync(device.BluetoothDeviceId));
                    sessionTask.Wait(4000);
                    _gattSession = sessionTask.Result;
                    if (_gattSession != null) {
                        _gattSession.MaintainConnection = true;
                        SendJson("LOG", "Persistent GATT session established for " + address);
                    }
                } catch (Exception gattEx) {
                    SendJson("LOG", "GATT session note: " + gattEx.Message);
                }

                try {
                    device.ConnectionStatusChanged += OnDeviceConnectionStatusChanged;
                } catch { }

                // Discover GATT Services & Characteristics
                Task.Factory.StartNew(async () => {
                    try {
                        var gattServices = await ToTask(device.GetGattServicesAsync(BluetoothCacheMode.Uncached));
                        if (gattServices.Status == GattCommunicationStatus.Success) {
                            var servicesList = gattServices.Services;
                            for (int i = 0; i < servicesList.Count; i++) {
                                var service = servicesList[i];
                                if (service.Uuid == MESH_SERVICE_GUID) {
                                    var charsResult = await ToTask(service.GetCharacteristicsAsync(BluetoothCacheMode.Uncached));
                                    if (charsResult.Status == GattCommunicationStatus.Success) {
                                        var charsList = charsResult.Characteristics;
                                        for (int j = 0; j < charsList.Count; j++) {
                                            var ch = charsList[j];
                                            if (ch.Uuid == MESH_TX_CHAR_GUID) {
                                                _notifyCharacteristic = ch;
                                                await ToTask(ch.WriteClientCharacteristicConfigurationDescriptorAsync(GattClientCharacteristicConfigurationDescriptorValue.Notify));
                                                try {
                                                    ch.ValueChanged += OnGattCharacteristicValueChanged;
                                                } catch { }
                                            } else if (ch.Uuid == MESH_RX_CHAR_GUID) {
                                                _writeCharacteristic = ch;
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    } catch (Exception ex) {
                        SendJson("LOG", "GATT Service Discovery note: " + ex.Message);
                    }
                });

                string devName = device.Name;
                if (string.IsNullOrEmpty(devName)) devName = address;

                SendJson("CONNECT_STATUS", "{\"status\":\"CONNECTED\",\"address\":\"" + EscapeJson(address) + "\",\"name\":\"" + EscapeJson(devName) + "\"}");
            } catch (Exception ex) {
                SendJson("ERROR", "Connection failed: " + ex.Message);
                SendJson("CONNECT_STATUS", "{\"status\":\"DISCONNECTED\",\"address\":\"" + EscapeJson(address) + "\",\"error\":\"" + EscapeJson(ex.Message) + "\"}");
            }
        }

        private static void OnGattCharacteristicValueChanged(GattCharacteristic sender, GattValueChangedEventArgs args) {
            try {
                var reader = DataReader.FromBuffer(args.CharacteristicValue);
                byte[] data = new byte[reader.UnconsumedBufferLength];
                reader.ReadBytes(data);

                if (data.Length >= 5 && data[0] == 0x5A && data[1] == 0xA5) {
                    byte opcode = data[2];
                    int len = (data[3] << 8) | data[4];
                    if (data.Length >= 5 + len) {
                        if (opcode == 0x01) {
                            string text = Encoding.UTF8.GetString(data, 5, len);
                            SendJson("CONTROL_PACKET", text);
                        }
                    }
                }
            } catch { }
        }

        private static void OnDeviceConnectionStatusChanged(BluetoothLEDevice sender, object args) {
            try {
                if (sender.ConnectionStatus == BluetoothConnectionStatus.Disconnected) {
                    if (_isUserDisconnecting) return;

                    string addr = _connectedAddress;
                    SendJson("LOG", "Bluetooth device disconnected: " + (addr ?? sender.Name));
                    DisconnectCurrent("UNEXPECTED_DISCONNECT");
                } else if (sender.ConnectionStatus == BluetoothConnectionStatus.Connected) {
                    SendJson("CONNECT_STATUS", "{\"status\":\"CONNECTED\",\"address\":\"" + EscapeJson(_connectedAddress) + "\",\"name\":\"" + EscapeJson(sender.Name) + "\"}");
                }
            } catch { }
        }

        public static void DisconnectCurrent(string reason = "USER_REQUESTED") {
            lock (_lock) {
                if (reason == "USER_REQUESTED") {
                    _isUserDisconnecting = true;
                }

                if (_gattSession != null) {
                    try {
                        _gattSession.MaintainConnection = false;
                        _gattSession.Dispose();
                    } catch { }
                    _gattSession = null;
                }

                if (_connectedBleDevice != null) {
                    try {
                        _connectedBleDevice.Dispose();
                    } catch { }
                    _connectedBleDevice = null;
                }

                if (_activeRfcommSocket != null) {
                    try {
                        _activeRfcommSocket.Dispose();
                    } catch { }
                    _activeRfcommSocket = null;
                }

                string oldAddr = _connectedAddress;
                _connectedAddress = null;
                SendJson("CONNECT_STATUS", "{\"status\":\"DISCONNECTED\",\"address\":\"" + EscapeJson(oldAddr ?? "") + "\",\"reason\":\"" + EscapeJson(reason) + "\"}");
            }
        }

        // =========================================================================
        // Windows Device Pairing & Unpairing
        // =========================================================================
        public static void PairDevice(string address) {
            Task.Factory.StartNew(async () => {
                try {
                    ulong uAddress = ParseMac(address);
                    var device = await ToTask(BluetoothLEDevice.FromBluetoothAddressAsync(uAddress));
                    if (device != null && device.DeviceInformation != null) {
                        var pairingResult = await ToTask(device.DeviceInformation.Pairing.Custom.PairAsync(DevicePairingKinds.ConfirmOnly));
                        SendJson("PAIR_RESULT", "{\"address\":\"" + EscapeJson(address) + "\",\"status\":\"" + pairingResult.Status + "\"}");
                    } else {
                        SendJson("ERROR", "Cannot pair: device information unavailable");
                    }
                } catch (Exception ex) {
                    SendJson("ERROR", "Pair error: " + ex.Message);
                }
            });
        }

        public static void UnpairDevice(string address) {
            Task.Factory.StartNew(async () => {
                try {
                    ulong uAddress = ParseMac(address);
                    var device = await ToTask(BluetoothLEDevice.FromBluetoothAddressAsync(uAddress));
                    if (device != null && device.DeviceInformation != null) {
                        var unpairResult = await ToTask(device.DeviceInformation.Pairing.UnpairAsync());
                        SendJson("UNPAIR_RESULT", "{\"address\":\"" + EscapeJson(address) + "\",\"status\":\"" + unpairResult.Status + "\"}");
                    }
                } catch (Exception ex) {
                    SendJson("ERROR", "Unpair error: " + ex.Message);
                }
            });
        }

        private static string FormatMac(string hex) {
            if (hex.Length < 12) hex = hex.PadLeft(12, '0');
            var sb = new StringBuilder();
            for (int i = 0; i < 12; i += 2) {
                if (i > 0) sb.Append(":");
                sb.Append(hex.Substring(i, 2));
            }
            return sb.ToString();
        }

        private static ulong ParseMac(string mac) {
            string cleaned = mac.Replace(":", "").Replace("-", "");
            return Convert.ToUInt64(cleaned, 16);
        }

        private static void ReadCommands() {
            using (var reader = new StreamReader(Console.OpenStandardInput(), Encoding.UTF8)) {
                string line;
                while ((line = reader.ReadLine()) != null) {
                    try {
                        line = line.Trim();
                        if (string.IsNullOrEmpty(line)) continue;

                        if (line.StartsWith("SCAN:START")) {
                            StartScan();
                        } else if (line.StartsWith("SCAN:STOP")) {
                            StopScan();
                        } else if (line.StartsWith("RADIO:ON")) {
                            SetRadioState(true);
                        } else if (line.StartsWith("RADIO:OFF")) {
                            SetRadioState(false);
                        } else if (line.StartsWith("CONNECT:")) {
                            string addr = line.Substring(8).Trim();
                            Task.Factory.StartNew(() => ConnectDevice(addr));
                        } else if (line.StartsWith("DISCONNECT")) {
                            DisconnectCurrent("USER_REQUESTED");
                        } else if (line.StartsWith("PAIR:")) {
                            string addr = line.Substring(5).Trim();
                            PairDevice(addr);
                        } else if (line.StartsWith("UNPAIR:")) {
                            string addr = line.Substring(7).Trim();
                            UnpairDevice(addr);
                        } else if (line.StartsWith("STATUS")) {
                            InitRadio();
                        } else if (line.StartsWith("AUTO_RECONNECT:")) {
                            string val = line.Substring(15).Trim().ToLower();
                            _autoReconnect = (val == "true" || val == "1" || val == "yes");
                            SendJson("CONFIG", "{\"autoReconnect\":" + (_autoReconnect ? "true" : "false") + "}");
                        }
                    } catch (Exception ex) {
                        SendJson("ERROR", "Command processing failed: " + ex.Message);
                    }
                }
            }
        }
    }
}
