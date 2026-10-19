@echo off
rem Filas distribuido / paralelo_distribuido. Uso: bench_distribuido.bat ^<ip_servidor^> ^<distribuido^|paralelo_distribuido^> "1 2 4" [imagenes]
rem "all" = cluster mixto (Caso 3); con 1 maquina no hay mezcla posible y se omite
cd /d "%~dp0\.."
set HOST=%1
set LABEL=%2
set IMG=%4
if "%IMG%"=="" set IMG=1024
rem Red lenta: "set BATCH=256" antes de correrlo (el mismo BATCH en bench_local.bat)
if "%BATCH%"=="" set BATCH=64
for %%M in (%~3) do for %%L in (java python go all) do (
  if not "%%L%%M"=="all1" (
    echo == %LABEL% ^| nodos=%%L ^| maquinas=%%M
    java -cp out cubes.client.AdminClient --host %HOST% entrenar --images %IMG% --epochs 1 --batch %BATCH% --langs %%L --max-nodes %%M --label %LABEL%
  )
)
