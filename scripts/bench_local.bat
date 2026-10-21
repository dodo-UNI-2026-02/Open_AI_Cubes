@echo off
rem Filas secuencial y paralelo de la matriz en ESTA maquina. Uso: bench_local.bat [imagenes] [hilos_paralelo]
rem Opcionales: set REPS=3 (repeticiones, se usa la mediana), set THREADS=1 2 4 8 (barrido de hilos), set BATCH=64
cd /d "%~dp0\.."
set IMG=%1
if "%IMG%"=="" set IMG=1024
set H=%2
if "%H%"=="" set H=%NUMBER_OF_PROCESSORS%
if "%BATCH%"=="" set BATCH=64
if "%REPS%"=="" set REPS=3
if "%THREADS%"=="" set THREADS=1 %H%
if not exist results mkdir results
set COMMON=--train data\train.bin --init data\init_weights.bin --images %IMG% --epochs 1 --batch %BATCH% --csv results\local.csv
for /l %%R in (1,1,%REPS%) do for %%T in (%THREADS%) do call :fila %%T %%R
echo Resultados en results\local.csv
goto :eof
:fila
set L=paralelo
if "%1"=="1" set L=secuencial
echo == [rep %2/%REPS%] Java %L% (%1 hilos)
java -Xmx2g -cp out cubes.bench.LocalTrain %COMMON% --test none --threads %1 --label %L%
echo == [rep %2/%REPS%] Go %L% (%1 hilos)
go\nodo-go.exe --bench %COMMON% --threads %1 --label %L%
echo == [rep %2/%REPS%] Python %L% (%1 procesos)
python python\node.py --bench %COMMON% --threads %1 --label %L%
goto :eof
