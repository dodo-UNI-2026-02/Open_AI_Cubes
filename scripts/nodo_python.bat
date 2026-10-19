@echo off
rem Uso: nodo_python.bat ^<ip_servidor^> [hilos]
cd /d "%~dp0\.."
set H=%2
if "%H%"=="" set H=%NUMBER_OF_PROCESSORS%
python python\node.py --server %1 --port 6000 --threads %H%
