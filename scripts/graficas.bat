@echo off
rem Genera las graficas PNG del informe a partir de results\*.csv
cd /d "%~dp0\.."
java -cp out cubes.tools.Charts results
