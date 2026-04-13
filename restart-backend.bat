@echo off
echo Stopping old backend...
taskkill /F /IM java.exe /T 2>nul
timeout /t 2 /nobreak >nul

echo Building...
cd /d c:\projects\tracker
call mvn package -DskipTests -q
if errorlevel 1 (
    echo BUILD FAILED
    pause
    exit /b 1
)

echo Starting backend on port 8080...
start "7max Backend" mvn spring-boot:run
echo Backend starting... check http://localhost:8080
timeout /t 3 /nobreak >nul
