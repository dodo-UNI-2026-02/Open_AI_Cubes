@echo off
rem Servidor central: puerto 5000 (clientes) y 6000 (nodos).
cd /d "%~dp0\.."
if "%JAVA_MEM%"=="" set JAVA_MEM=-Xmx2g
java %JAVA_MEM% -cp out cubes.server.CentralServer %*
