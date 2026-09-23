Add-Type -AssemblyName System.Runtime.WindowsRuntime
[Windows.Devices.Radios.Radio,Windows.System.Devices,ContentType=WindowsRuntime] | Out-Null
[Windows.Devices.Bluetooth.BluetoothDevice,Windows.Devices.Bluetooth,ContentType=WindowsRuntime] | Out-Null
[Windows.Devices.Bluetooth.Advertisement.BluetoothLEAdvertisementWatcher,Windows.Devices.Bluetooth,ContentType=WindowsRuntime] | Out-Null

$watcher = New-Object Windows.Devices.Bluetooth.Advertisement.BluetoothLEAdvertisementWatcher
$watcher.ScanningMode = [Windows.Devices.Bluetooth.Advertisement.BluetoothLEScanningMode]::Active

$action = {
    param($sender, $eventArgs)
    $addrHex = "{0:X12}" -f $eventArgs.BluetoothAddress
    $mac = ($addrHex -replace '..(?!$)', '$0:').Substring(0, 17)
    $name = $eventArgs.Advertisement.LocalName
    $rssi = $eventArgs.RawSignalStrengthInDBm
    Write-Host "DEVICE_FOUND: MAC=$mac, Name='$name', RSSI=$rssi"
}

Register-ObjectEvent -InputObject $watcher -EventName "Received" -Action $action | Out-Null

Write-Host "Starting BLE scan for 5 seconds..."
$watcher.Start()
Start-Sleep -Seconds 5
$watcher.Stop()
Write-Host "Scan completed."
