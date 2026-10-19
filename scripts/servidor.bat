@echo off
rem Servidor central: puerto 5000 (clientes) y 6000 (nodos).
cd /d "%~dp0\.."
java -Xmx2g -cp out cubes.server.CentralServer %*
