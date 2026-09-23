@echo off
title Sharp-Bose Tactical Mesh
cd /d "%~dp0"
echo [*] Starting Sharp-Bose Embedded Tactical Mesh Hub...
start "" /B node main.js --headless
timeout /t 1 /nobreak >nul
echo [*] Launching lightweight terminal window...
start msedge --app=http://localhost:3000 --window-size=1120,740
