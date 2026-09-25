@echo off
title Sharp-Bose Tactical Mesh
cd /d "%~dp0"
echo [*] Cleaning stale instances and starting Sharp-Bose Embedded Tactical Mesh Hub...
taskkill /F /IM node.exe >nul 2>&1
timeout /t 1 /nobreak >nul
start "" /B node main.js --headless
timeout /t 1 /nobreak >nul
echo [*] Launching lightweight terminal window...
start msedge --app=http://localhost:3000 --window-size=1120,740

