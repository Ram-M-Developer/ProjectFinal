@echo off
echo ========================================================
echo  BarberEase - Starting MySQL Database Service (MySQL80)
echo ========================================================
echo.
echo Requesting Administrator privileges to start MySQL80...
echo.

powershell -Command "Start-Process cmd -ArgumentList '/c net start MySQL80 && echo. && echo MySQL80 service started successfully! && echo. && pause' -Verb RunAs"

echo If a Windows UAC prompt appeared, please click 'Yes' to start MySQL.
echo.
pause
