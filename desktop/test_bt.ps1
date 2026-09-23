$winmd = 'C:\Windows\System32\WinMetadata\Windows.Devices.winmd'
$winmd2 = 'C:\Windows\System32\WinMetadata\Windows.Foundation.winmd'
$rt = [System.IO.Path]::Combine([System.Runtime.InteropServices.RuntimeEnvironment]::GetRuntimeDirectory(), 'System.Runtime.WindowsRuntime.dll')

if (-not (Test-Path $rt)) {
    # Search for System.Runtime.WindowsRuntime.dll in GAC or .NET Framework
    $rt = (Get-ChildItem -Path "C:\Windows\Microsoft.NET" -Filter "System.Runtime.WindowsRuntime.dll" -Recurse -ErrorAction SilentlyContinue | Select-Object -First 1).FullName
}

Write-Host "Referenced RT: $rt"

$code = Get-Content -Raw -Path 'c:\Users\wahab\Documents\antigravity\sharp-bose\desktop\test_bt.cs'

Add-Type -TypeDefinition $code -ReferencedAssemblies $winmd, $winmd2, $rt
[WinBluetoothCheck]::Main(@())
