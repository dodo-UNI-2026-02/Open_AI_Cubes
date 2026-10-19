@echo off
rem Filas distribuido / paralelo_distribuido. Uso: bench_distribuido.bat ^<ip_servidor^> ^<distribuido^|paralelo_distribuido^> "1 2 3" [imagenes]
rem "1 2 3" = cuantos nodos usar (uno por maquina mientras alcancen); "all" = cluster mixto (Caso 3), se omite con 1
rem Opcionales: set REPS=3 (repeticiones, se usa la mediana); red lenta o nube: set BATCH=256 (el mismo en bench_local.bat)
cd /d "%~dp0\.."
set HOST=%1
set LABEL=%2
set IMG=%4
if "%IMG%"=="" set IMG=1024
if "%BATCH%"=="" set BATCH=64
if "%REPS%"=="" set REPS=3
for /l %%R in (1,1,%REPS%) do for %%M in (%~3) do for %%L in (java python go all) do (
  if not "%%L%%M"=="all1" (
    echo == [rep %%R/%REPS%] %LABEL% ^| nodos=%%L ^| maquinas=%%M
    java -cp out cubes.client.AdminClient --host %HOST% entrenar --images %IMG% --epochs 1 --batch %BATCH% --langs %%L --max-nodes %%M --label %LABEL%
  )
)
