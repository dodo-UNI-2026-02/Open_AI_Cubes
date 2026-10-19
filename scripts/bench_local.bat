@echo off
rem Filas secuencial y paralelo de la matriz en ESTA maquina. Uso: bench_local.bat [imagenes]
cd /d "%~dp0\.."
set IMG=%1
if "%IMG%"=="" set IMG=1024
set H=%NUMBER_OF_PROCESSORS%
if "%BATCH%"=="" set BATCH=64
if not exist results mkdir results
set COMMON=--train data\train.bin --init data\init_weights.bin --images %IMG% --epochs 1 --batch %BATCH% --csv results\local.csv
for %%T in (1 %H%) do call :fila %%T
echo Resultados en results\local.csv
goto :eof
:fila
set L=paralelo
if "%1"=="1" set L=secuencial
echo == Java %L% (%1 hilos)
java -Xmx2g -cp out cubes.bench.LocalTrain %COMMON% --test none --threads %1 --label %L%
echo == Go %L% (%1 hilos)
go\nodo-go.exe --bench %COMMON% --threads %1 --label %L%
echo == Python %L% (%1 hilos)
python python\node.py --bench %COMMON% --threads %1 --label %L%
goto :eof
