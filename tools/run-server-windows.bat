@echo off
REM ============================================================================
REM Run the SavePoint Radio Minecraft server on WINDOWS-NATIVE Java 25 (off WSL).
REM
REM WHY: the Minecraft server's audio-delivery thread must NOT run inside the WSL2
REM guest VM, which the Windows host freezes ~815 ms every ~33 s (see the build
REM journal). Running it here, on the Windows host, escapes that freeze -> no 30 s
REM hitch, and the mod's `hostFreezes` counter should read 0.
REM
REM PREREQUISITES (important, because of WSL2 mirrored networking):
REM   1. No WSL Minecraft server may be running/holding ports 25565/24454/25575/1936.
REM      After a WSL server has run, mirrored networking can leave a "ghost" port
REM      reservation on the Windows side that blocks this server from binding. If
REM      this .bat fails with "FAILED TO BIND / Address already in use", run
REM          wsl --shutdown
REM      from a Windows terminal (this clears the ghost but ALSO restarts your whole
REM      WSL, incl. the SavePoint stack), then bring SavePoint back up and re-run this.
REM   2. SavePoint (its RTMP push to 127.0.0.1:1936 / HLS) must be reachable. With
REM      mirrored networking, localhost is shared, so a WSL SavePoint reaches this
REM      Windows server on 127.0.0.1 - verify audio connects after launch.
REM
REM Java: uses the Zulu 25 JRE that Modrinth downloaded for 26.2.
REM ============================================================================
setlocal
set "JAVA=%APPDATA%\ModrinthApp\meta\java_versions\zulu25.34.17-ca-jre25.0.3-win_x64\bin\java.exe"
if not exist "%JAVA%" (
  echo Zulu 25 JRE not found at "%JAVA%".
  echo Adjust JAVA in this script to a Windows Java 25 install.
  pause
  exit /b 1
)
cd /d "D:\_Outfits\GBTI\Repos\minecraft-savepoint-radio\.server"
echo Starting self-healing RTMP relay (local test harness)...
start "SavePoint RTMP Relay" /min cmd /c "D:\_Outfits\GBTI\Repos\minecraft-savepoint-radio\.server\rtmp-relay.bat"
echo Launching SavePoint Radio server on Windows-native Java 25 ...
"%JAVA%" -Xms1G -Xmx2G -jar fabric-server-launch.jar nogui
echo.
echo Server exited. Press a key to close.
pause >nul
