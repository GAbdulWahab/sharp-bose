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
using Windows.Devices.Enumeration;
using Windows.Foundation;

namespace SharpBose.WindowsBluetooth {
    public class Program {
        private static Radio _bluetoothRadio = null;
        private static BluetoothLEAdvertisementWatcher _bleWatcher = null;
        private static readonly Dictionary<string, DeviceInfo> _discoveredDevices = new Dictionary<string, DeviceInfo>();
        private static BluetoothLEDevice _connectedDevice = null;
        private static GattSession _gattSession = null;
        private static string _connectedAddress = null;
        private static bool _autoReconnect = true;
        private static string _lastConnectedAddress = null;
        private static bool _isUserDisconnecting = false;
        private static readonly object _lock = new object();

        public class DeviceInfo {
            public string address;
            public string name;
            public short rssi;
            public bool isConnectable;
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

        public static void Main(string[] args) {
            Console.OutputEncoding = Encoding.UTF8;
            SendJson("LOG", "Windows Bluetooth Service Active");

            try {
                InitRadio();
                InitWatcher();
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
                Thread.Sleep(2000);
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
                task.Wait(4000);
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

                if (_bluetoothRadio == null) {
                    SendJson("STATUS", "{\"available\":false,\"state\":\"UNAVAILABLE\"}");
                } else {
                    string stateStr = _bluetoothRadio.State == RadioState.On ? "ON" : "OFF";
                    SendJson("STATUS", "{\"available\":true,\"state\":\"" + stateStr + "\",\"name\":\"" + EscapeJson(_bluetoothRadio.Name) + "\"}");
                    
                    _bluetoothRadio.StateChanged += OnRadioStateChanged;
                }
            } catch (Exception ex) {
                SendJson("ERROR", "Radio init failed: " + ex.Message);
                SendJson("STATUS", "{\"available\":false,\"state\":\"ERROR\",\"error\":\"" + EscapeJson(ex.Message) + "\"}");
            }
        }

        private static void OnRadioStateChanged(Radio sender, object args) {
            string stateStr = sender.State == RadioState.On ? "ON" : "OFF";
            SendJson("RADIO_CHANGED", "{\"available\":true,\"state\":\"" + stateStr + "\"}");

            if (sender.State == RadioState.Off) {
                lock (_lock) {
                    if (_connectedDevice != null) {
                        DisconnectCurrent("RADIO_TURNED_OFF");
                    }
                }
            } else if (sender.State == RadioState.On) {
                SendJson("LOG", "Bluetooth turned ON. Refreshing scanner...");
                StartScan();
                if (_autoReconnect && !string.IsNullOrEmpty(_lastConnectedAddress)) {
                    string addr = _lastConnectedAddress;
                    Task.Factory.StartNew(() => {
                        Thread.Sleep(1500);
                        ConnectDevice(addr);
                    });
                }
            }
        }

        private static void CheckRadioState() {
            if (_bluetoothRadio != null) {
                string stateStr = _bluetoothRadio.State == RadioState.On ? "ON" : "OFF";
                SendJson("HEARTBEAT", "{\"available\":true,\"state\":\"" + stateStr + "\",\"connected\":\"" + (_connectedAddress ?? "") + "\"}");
            }
        }

        private static void InitWatcher() {
            _bleWatcher = new BluetoothLEAdvertisementWatcher();
            _bleWatcher.ScanningMode = BluetoothLEScanningMode.Active;
            _bleWatcher.Received += OnAdvertisementReceived;
            _bleWatcher.Stopped += OnWatcherStopped;
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

                if (_bleWatcher != null && _bleWatcher.Status != BluetoothLEAdvertisementWatcherStatus.Started) {
                    _bleWatcher.Start();
                    SendJson("SCAN_STATE", "{\"scanning\":true}");
                }
            } catch (Exception ex) {
                SendJson("ERROR", "Start scan error: " + ex.Message);
            }
        }

        public static void StopScan() {
            try {
                if (_bleWatcher != null && _bleWatcher.Status == BluetoothLEAdvertisementWatcherStatus.Started) {
                    _bleWatcher.Stop();
                    SendJson("SCAN_STATE", "{\"scanning\":false}");
                }
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
                    lastSeen = DateTime.UtcNow.Ticks / TimeSpan.TicksPerMillisecond
                };

                bool isNew = false;
                lock (_lock) {
                    if (!_discoveredDevices.ContainsKey(mac)) {
                        isNew = true;
                    }
                    _discoveredDevices[mac] = info;
                }

                string devJson = "{\"address\":\"" + EscapeJson(info.address) + "\",\"name\":\"" + EscapeJson(info.name) + "\",\"rssi\":" + info.rssi + ",\"isConnectable\":" + (info.isConnectable ? "true" : "false") + ",\"isNew\":" + (isNew ? "true" : "false") + "}";
                SendJson("DEVICE_FOUND", devJson);
            } catch { }
        }

        private static void OnWatcherStopped(BluetoothLEAdvertisementWatcher sender, BluetoothLEAdvertisementWatcherStoppedEventArgs args) {
            SendJson("SCAN_STATE", "{\"scanning\":false,\"error\":\"" + args.Error + "\"}");
        }

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
                    _connectedDevice = device;
                    _connectedAddress = address;
                    _lastConnectedAddress = address;
                }

                // Attach persistent GATT Session with MaintainConnection = true to prevent Windows from auto-disconnecting
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

                device.ConnectionStatusChanged += OnDeviceConnectionStatusChanged;

                string devName = device.Name;
                if (string.IsNullOrEmpty(devName)) devName = address;

                SendJson("CONNECT_STATUS", "{\"status\":\"CONNECTED\",\"address\":\"" + EscapeJson(address) + "\",\"name\":\"" + EscapeJson(devName) + "\"}");
            } catch (Exception ex) {
                SendJson("ERROR", "Connection failed: " + ex.Message);
                SendJson("CONNECT_STATUS", "{\"status\":\"DISCONNECTED\",\"address\":\"" + EscapeJson(address) + "\",\"error\":\"" + EscapeJson(ex.Message) + "\"}");
            }
        }

        private static void OnDeviceConnectionStatusChanged(BluetoothLEDevice sender, object args) {
            try {
                if (sender.ConnectionStatus == BluetoothConnectionStatus.Disconnected) {
                    if (_isUserDisconnecting) return;

                    string addr = _connectedAddress;
                    SendJson("LOG", "Bluetooth device disconnected: " + (addr ?? sender.Name));
                    DisconnectCurrent("UNEXPECTED_DISCONNECT");
                    
                    if (_autoReconnect && !string.IsNullOrEmpty(addr)) {
                        SendJson("LOG", "Auto-reconnect active. Attempting reconnection to " + addr + " in 2.5 seconds...");
                        Task.Factory.StartNew(() => {
                            Thread.Sleep(2500);
                            if (_connectedDevice == null && _autoReconnect && !_isUserDisconnecting) {
                                ConnectDevice(addr);
                            }
                        });
                    }
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

                if (_connectedDevice != null) {
                    try {
                        _connectedDevice.Dispose();
                    } catch { }
                    _connectedDevice = null;
                }
                string oldAddr = _connectedAddress;
                _connectedAddress = null;
                SendJson("CONNECT_STATUS", "{\"status\":\"DISCONNECTED\",\"address\":\"" + EscapeJson(oldAddr ?? "") + "\",\"reason\":\"" + EscapeJson(reason) + "\"}");
            }
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
                        } else if (line.StartsWith("CONNECT:")) {
                            string addr = line.Substring(8).Trim();
                            Task.Factory.StartNew(() => ConnectDevice(addr));
                        } else if (line.StartsWith("DISCONNECT")) {
                            DisconnectCurrent("USER_REQUESTED");
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
