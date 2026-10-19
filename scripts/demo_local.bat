@echo off
rem Levanta todo en ESTA maquina para probar: servidor + un nodo de cada lenguaje,
rem cada uno en su propia ventana. Es el equivalente de demo_local.sh (Linux/macOS).
rem Uso: scripts\demo_local.bat [hilos]
rem Para detener todo: cerrar las ventanas del servidor y de los nodos.
cd /d "%~dp0\.."
set H=%1
if "%H%"=="" set H=2
start "Servidor central" cmd /k java -Xmx2g -cp out cubes.server.CentralServer
start "Nodo Java" cmd /k java -cp out cubes.node.JavaNode --threads %H%
start "Nodo Python" cmd /k python python\node.py --threads %H%
if exist go\nodo-go.exe (start "Nodo Go" cmd /k go\nodo-go.exe --threads %H%) else (echo AVISO: no existe go\nodo-go.exe, se omite el nodo Go)
echo Esperando a que los nodos se conecten...
timeout /t 8 /nobreak >nul
java -cp out cubes.client.AdminClient estado
java -cp out cubes.client.AdminClient crosscheck
echo.
echo Todo listo. Abre el chat con: scripts\cliente.bat
echo Para detener todo, cierra las ventanas del servidor y de los nodos.
pause
