@echo off
set CSC=C:\Windows\Microsoft.NET\Framework64\v4.0.30319\csc.exe
set WINMD_DEV=C:\Windows\System32\WinMetadata\Windows.Devices.winmd
set WINMD_FND=C:\Windows\System32\WinMetadata\Windows.Foundation.winmd
set WINMD_NET=C:\Windows\System32\WinMetadata\Windows.Networking.winmd
set WINMD_STO=C:\Windows\System32\WinMetadata\Windows.Storage.winmd
set REF_RT=C:\Windows\Microsoft.NET\assembly\GAC_MSIL\System.Runtime\v4.0_4.0.0.0__b03f5f7f11d50a3a\System.Runtime.dll
set REF_WINRT=C:\Windows\Microsoft.NET\assembly\GAC_MSIL\System.Runtime.WindowsRuntime\v4.0_4.0.0.0__b77a5c561934e089\System.Runtime.WindowsRuntime.dll
set REF_TASKS=C:\Windows\Microsoft.NET\assembly\GAC_MSIL\System.Threading.Tasks\v4.0_4.0.0.0__b03f5f7f11d50a3a\System.Threading.Tasks.dll
set REF_INTEROP=C:\Windows\Microsoft.NET\assembly\GAC_MSIL\System.Runtime.InteropServices.WindowsRuntime\v4.0_4.0.0.0__b03f5f7f11d50a3a\System.Runtime.InteropServices.WindowsRuntime.dll

"%CSC%" /nologo /target:exe /out:"%~dp0SharpBoseWinBluetoothCore.exe" /r:"%WINMD_DEV%" /r:"%WINMD_FND%" /r:"%WINMD_NET%" /r:"%WINMD_STO%" /r:"%REF_RT%" /r:"%REF_WINRT%" /r:"%REF_TASKS%" /r:"%REF_INTEROP%" "%~dp0SharpBoseWinBluetooth.cs"

if %ERRORLEVEL% EQU 0 (
  echo BUILD_SUCCESS
) else (
  echo BUILD_FAILED
)
