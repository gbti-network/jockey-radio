@echo off
REM ============================================================================
REM Run the Jockey Radio dev server (.server\ beside tools\) on Windows-native Java 25.
REM
REM WHY: the audio-delivery thread must not run inside the WSL2 guest VM, which the
REM Windows host freezes for ~815 ms every ~33 s. Run listening and soak tests here;
REM the WSL panel (tools\server-panel) is fine for functional checks.
REM
REM PREREQUISITES:
REM   1. No WSL Minecraft server may hold the same ports: server-port and rcon.port in
REM      .server\server.properties, port in .server\config\voicechat\
REM      voicechat-server.properties, and every RTMP disc port. With mirrored
REM      networking, a WSL server that has exited can leave a stale reservation on
REM      the Windows side. If this fails with "FAILED TO BIND" or "Address already
REM      in use", move those ports. Do not restart WSL to clear it: that stops
REM      SavePoint and every other WSL service.
REM   2. A local SavePoint (HLS on 127.0.0.1:7101, RTMP push to a disc's port) is
REM      reachable on 127.0.0.1 through mirrored networking.
REM
REM Java: set JOCKEY_JAVA to a Windows Java 25 java.exe, or keep the default, the
REM Zulu 25 JRE the Modrinth App downloads for 26.2.
REM ============================================================================
setlocal
if defined JOCKEY_JAVA (
  set "JAVA=%JOCKEY_JAVA%"
) else (
  set "JAVA=%APPDATA%\ModrinthApp\meta\java_versions\zulu25.34.17-ca-jre25.0.3-win_x64\bin\java.exe"
)
if not exist "%JAVA%" (
  echo Java 25 not found at "%JAVA%".
  echo Set JOCKEY_JAVA to a Windows Java 25 java.exe.
  pause
  exit /b 1
)
set "SERVER_DIR=%~dp0..\.server"
if not exist "%SERVER_DIR%\fabric-server-launch.jar" (
  echo No dev server at "%SERVER_DIR%". Run "python3 tools/server-panel/panel.py --setup" in WSL first.
  pause
  exit /b 1
)
cd /d "%SERVER_DIR%"
if exist rtmp-relay.bat (
  echo Starting the local RTMP relay test harness...
  start "Jockey RTMP Relay" /min cmd /c "%SERVER_DIR%\rtmp-relay.bat"
)
echo Launching the Jockey Radio dev server on Windows-native Java 25 ...
"%JAVA%" -Xms1G -Xmx2G -jar fabric-server-launch.jar nogui
echo.
echo Server exited. Press a key to close.
pause >nul
