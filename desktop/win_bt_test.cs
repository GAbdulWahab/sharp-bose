using System;
using System.Threading;
using System.Threading.Tasks;
using System.Collections.Generic;
using Windows.Devices.Radios;
using Windows.Devices.Bluetooth;
using Windows.Devices.Bluetooth.Advertisement;
using Windows.Devices.Enumeration;

namespace SharpBose.WinBluetooth {
    public class Program {
        public static void Main(string[] args) {
            Console.WriteLine("C# WinRT Bluetooth Bridge Started");
            try {
                CheckRadio().GetAwaiter().GetResult();
                StartScanner();
                Thread.Sleep(3000);
            } catch (Exception ex) {
                Console.WriteLine("ERROR: " + ex.Message);
            }
        }

        private static async Task CheckRadio() {
            var radios = await Radio.GetRadiosAsync();
            foreach (var r in radios) {
                Console.WriteLine("RADIO:" + r.Name + "|" + r.Kind + "|" + r.State);
                if (r.Kind == RadioKind.Bluetooth) {
                    r.StateChanged += (sender, e) => {
                        Console.WriteLine("RADIO_STATE_CHANGED:" + sender.State);
                    };
                }
            }
        }

        private static void StartScanner() {
            var watcher = new BluetoothLEAdvertisementWatcher();
            watcher.ScanningMode = BluetoothLEScanningMode.Active;
            watcher.Received += (sender, args) => {
                string mac = string.Format("{0:X12}", args.BluetoothAddress);
                mac = string.Join(":", EnumerableRange(mac, 2));
                string name = args.Advertisement.LocalName;
                Console.WriteLine("DISCOVERED:" + mac + "|" + name + "|" + args.RawSignalStrengthInDBm);
            };
            watcher.Start();
            Console.WriteLine("SCANNER_STARTED");
        }

        private static string[] EnumerableRange(string str, int chunkSize) {
            int count = str.Length / chunkSize;
            string[] res = new string[count];
            for (int i = 0; i < count; i++) {
                res[i] = str.Substring(i * chunkSize, chunkSize);
            }
            return res;
        }
    }
}
