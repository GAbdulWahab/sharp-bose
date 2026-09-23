using System;
using System.Threading.Tasks;
using System.Collections.Generic;
using Windows.Devices.Radios;
using Windows.Devices.Bluetooth;
using Windows.Devices.Bluetooth.Advertisement;

public class WinBluetoothCheck {
    public static async Task RunCheck() {
        try {
            var radios = await Radio.GetRadiosAsync();
            Console.WriteLine("Radios found: " + radios.Count);
            Radio btRadio = null;
            foreach (var r in radios) {
                Console.WriteLine("Radio: " + r.Name + " | Kind: " + r.Kind + " | State: " + r.State);
                if (r.Kind == RadioKind.Bluetooth) {
                    btRadio = r;
                }
            }
            if (btRadio == null) {
                Console.WriteLine("BT_STATUS:NOT_AVAILABLE");
            } else {
                Console.WriteLine("BT_STATUS:" + (btRadio.State == RadioState.On ? "ON" : "OFF"));
            }
        } catch (Exception ex) {
            Console.WriteLine("ERROR: " + ex.ToString());
        }
    }

    public static void Main(string[] args) {
        RunCheck().GetAwaiter().GetResult();
    }
}
