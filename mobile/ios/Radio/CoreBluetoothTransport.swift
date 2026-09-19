import Foundation
import CoreBluetooth

/**
 * Native CoreBluetooth BLE L2CAP Channel Manager for iOS.
 * Opens low-latency binary streams (L2CAP CoC) for real-time Opus audio packets and mesh frames.
 */
public class CoreBluetoothTransport: NSObject, CBCentralManagerDelegate, CBPeripheralManagerDelegate, CBPeripheralDelegate {
    public static let MESH_SERVICE_UUID = CBUUID(string: "4D455348-0001-1000-8000-00805F9B34FB")
    public static let L2CAP_PSM_PORT: CBL2CAPPSM = 0x1001
    
    private var centralManager: CBCentralManager!
    private var peripheralManager: CBPeripheralManager!
    private var l2capChannel: CBL2CAPChannel?
    private var inputStream: InputStream?
    private var outputStream: OutputStream?
    
    public var onDataReceived: ((Data) -> Void)?
    
    public override init() {
        super.init()
        centralManager = CBCentralManager(delegate: self, queue: nil)
        peripheralManager = CBPeripheralManager(delegate: self, queue: nil)
    }
    
    public func startAdvertisingAndScanning(nickname: String) {
        if peripheralManager.state == .poweredOn {
            let adData: [String: Any] = [
                CBAdvertisementDataServiceUUIDsKey: [CoreBluetoothTransport.MESH_SERVICE_UUID],
                CBAdvertisementDataLocalNameKey: nickname
            ]
            peripheralManager.startAdvertising(adData)
            peripheralManager.publishL2CAPChannel(withEncryption: true)
        }
        
        if centralManager.state == .poweredOn {
            centralManager.scanForPeripherals(withServices: [CoreBluetoothTransport.MESH_SERVICE_UUID], options: [
                CBCentralManagerScanOptionAllowDuplicatesKey: false
            ])
        }
    }
    
    public func sendPacket(_ data: Data) {
        guard let output = outputStream, output.hasSpaceAvailable else { return }
        data.withUnsafeBytes { ptr in
            if let base = ptr.bindMemory(to: UInt8.self).baseAddress {
                output.write(base, maxLength: data.count)
            }
        }
    }
    
    // MARK: - CBCentralManagerDelegate
    public func centralManagerDidUpdateState(_ central: CBCentralManager) {
        if central.state == .poweredOn {
            central.scanForPeripherals(withServices: [CoreBluetoothTransport.MESH_SERVICE_UUID], options: nil)
        }
    }
    
    public func centralManager(_ central: CBCentralManager, didDiscover peripheral: CBPeripheral, advertisementData: [String : Any], rssi RSSI: NSNumber) {
        // Discovered nearby mesh peer
        peripheral.delegate = self
        central.connect(peripheral, options: nil)
    }
    
    public func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        peripheral.openL2CAPChannel(CoreBluetoothTransport.L2CAP_PSM_PORT)
    }
    
    // MARK: - CBPeripheralDelegate
    public func peripheral(_ peripheral: CBPeripheral, didOpen channel: CBL2CAPChannel?, error: Error?) {
        guard let channel = channel, error == nil else { return }
        self.l2capChannel = channel
        self.inputStream = channel.inputStream
        self.outputStream = channel.outputStream
        self.inputStream?.open()
        self.outputStream?.open()
    }
    
    // MARK: - CBPeripheralManagerDelegate
    public func peripheralManagerDidUpdateState(_ peripheral: CBPeripheralManager) {
        if peripheral.state == .poweredOn {
            peripheral.publishL2CAPChannel(withEncryption: true)
        }
    }
    
    public func peripheralManager(_ peripheral: CBPeripheralManager, didPublishL2CAPChannel PSM: CBL2CAPPSM, error: Error?) {
        print("[BLE] Published L2CAP Channel with PSM: \(PSM)")
    }
    
    public func peripheralManager(_ peripheral: CBPeripheralManager, didOpen channel: CBL2CAPChannel?, error: Error?) {
        guard let channel = channel, error == nil else { return }
        self.l2capChannel = channel
        self.inputStream = channel.inputStream
        self.outputStream = channel.outputStream
        self.inputStream?.open()
        self.outputStream?.open()
    }
}
