@echo off
rem Uso: nodo_go.bat ^<ip_servidor^> [hilos]
cd /d "%~dp0\.."
set S=%1
if "%S%"=="" set S=127.0.0.1
set H=%2
if "%H%"=="" set H=%NUMBER_OF_PROCESSORS%
go\nodo-go.exe --server %S% --port 6000 --threads %H%
