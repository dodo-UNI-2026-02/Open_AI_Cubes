@echo off
rem Uso: nodo_java.bat ^<ip_servidor^> [hilos]
cd /d "%~dp0\.."
set S=%1
if "%S%"=="" set S=127.0.0.1
set H=%2
if "%H%"=="" set H=%NUMBER_OF_PROCESSORS%
java -Xmx1g -cp out cubes.node.JavaNode --server %S% --port 6000 --threads %H%
