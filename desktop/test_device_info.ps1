Add-Type -AssemblyName System.Runtime.WindowsRuntime
[Windows.Devices.Radios.Radio,Windows.System.Devices,ContentType=WindowsRuntime] | Out-Null
[Windows.Devices.Bluetooth.BluetoothDevice,Windows.Devices.Bluetooth,ContentType=WindowsRuntime] | Out-Null
[Windows.Devices.Bluetooth.BluetoothLEDevice,Windows.Devices.Bluetooth,ContentType=WindowsRuntime] | Out-Null
[Windows.Devices.Enumeration.DeviceInformation,Windows.Devices.Enumeration,ContentType=WindowsRuntime] | Out-Null

$asTaskGeneric = [System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object { 
    $_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1' 
} | Select-Object -First 1

# 1. Test BluetoothDevice selector
$selector = [Windows.Devices.Bluetooth.BluetoothDevice]::GetDeviceSelector()
Write-Host "BT Selector: $selector"

$op = [Windows.Devices.Enumeration.DeviceInformation]::FindAllAsync($selector)
$typedTask = $asTaskGeneric.MakeGenericMethod([Windows.Devices.Enumeration.DeviceInformationCollection]).Invoke($null, @($op))
$typedTask.Wait()
$devices = $typedTask.Result

Write-Host "Found $($devices.Count) Classic Bluetooth devices:"
foreach ($d in $devices) {
    Write-Host "DEVICE: Name='$($d.Name)', Id='$($d.Id)', Kind='$($d.Kind)'"
}

# 2. Test BluetoothLE selector
$leSelector = [Windows.Devices.Bluetooth.BluetoothLEDevice]::GetDeviceSelector()
Write-Host "BLE Selector: $leSelector"

$op2 = [Windows.Devices.Enumeration.DeviceInformation]::FindAllAsync($leSelector)
$typedTask2 = $asTaskGeneric.MakeGenericMethod([Windows.Devices.Enumeration.DeviceInformationCollection]).Invoke($null, @($op2))
$typedTask2.Wait()
$leDevices = $typedTask2.Result

Write-Host "Found $($leDevices.Count) BLE devices:"
foreach ($d in $leDevices) {
    Write-Host "BLE DEVICE: Name='$($d.Name)', Id='$($d.Id)', Kind='$($d.Kind)'"
}
