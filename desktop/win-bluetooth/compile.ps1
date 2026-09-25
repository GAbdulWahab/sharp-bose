$Assemblies = @(
    "System.Runtime.WindowsRuntime",
    "C:\Windows\System32\WinMetadata\Windows.Devices.winmd",
    "C:\Windows\System32\WinMetadata\Windows.Foundation.winmd",
    "C:\Windows\System32\WinMetadata\Windows.Networking.winmd",
    "C:\Windows\System32\WinMetadata\Windows.Storage.winmd"
)
$code = Get-Content -Raw "$PSScriptRoot\SharpBoseWinBluetooth.cs"
Add-Type -TypeDefinition $code -ReferencedAssemblies $Assemblies -OutputAssembly "$PSScriptRoot\SharpBoseWinBluetooth.exe" -OutputType ConsoleApplication
Write-Host "BUILD_COMPLETED_SUCCESSFULLY"
