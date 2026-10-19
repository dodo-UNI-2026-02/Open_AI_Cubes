@echo off
rem Cliente de escritorio (chat). Uso: cliente.bat [ip_servidor]
cd /d "%~dp0\.."
set S=%1
if "%S%"=="" set S=127.0.0.1
start javaw -cp out cubes.client.ChatClient --host %S% --test data\test.bin
