@echo off
rem Prueba de carga escalonada. Uso: carga.bat ^<ip_servidor^> ["etiqueta"] ["niveles"] [peticiones_por_cliente]
rem Ejemplo: carga.bat 192.168.1.10 "12 nodos" "100,400,800,1600" 20
cd /d "%~dp0\.."
set L=%~2
if "%L%"=="" set L=carga
set R=%~3
if "%R%"=="" set R=10,50,100,200,400,800
set N=%~4
if "%N%"=="" set N=20
java -Xmx2g -cp out cubes.client.LoadGenerator --host %1 --label "%L%" --ramp %R% --requests %N% --min-delay 0 --max-delay 200 --test data\test.bin --csv results\carga.csv
