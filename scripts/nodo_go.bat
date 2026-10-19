@echo off
rem Uso: nodo_go.bat ^<ip_servidor^> [hilos]
cd /d "%~dp0\.."
set H=%2
if "%H%"=="" set H=%NUMBER_OF_PROCESSORS%
go\nodo-go.exe --server %1 --port 6000 --threads %H%
