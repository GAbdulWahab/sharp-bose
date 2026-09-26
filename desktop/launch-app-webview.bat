@echo off
title Sharp-Bose Tactical Mesh
cd /d "%~dp0"
echo [*] Cleaning stale instances and starting Sharp-Bose Embedded Tactical Mesh Hub...
taskkill /F /IM node.exe >nul 2>&1
timeout /t 1 /nobreak >nul
start "" /B node main.js --headless
timeout /t 1 /nobreak >nul
echo [*] Launching lightweight terminal window with microphone and audio hardware enabled...
start msedge --app=http://localhost:3000 --window-size=1120,740 --use-fake-ui-for-media-stream --autoplay-policy=no-user-gesture-required --unsafely-treat-insecure-origin-as-secure=http://localhost:3000,http://127.0.0.1:3000 --user-data-dir="%TEMP%\sharp_bose_edge_profile" --allow-running-insecure-content

