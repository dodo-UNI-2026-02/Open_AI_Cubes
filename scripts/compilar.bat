@echo off
rem Compila Java (compatible con JDK 8) y el nodo Go. Ejecutar desde la carpeta del proyecto.
cd /d "%~dp0\.."
if exist out rmdir /s /q out
mkdir out
dir /s /b java\src\*.java > fuentes.txt
javac --release 8 -Xlint:-options -encoding UTF-8 -d out @fuentes.txt 2>nul || javac -encoding UTF-8 -d out @fuentes.txt
del fuentes.txt
echo Java compilado en out\
where go >nul 2>nul && (cd go && go build -o nodo-go.exe . && cd .. && echo Nodo Go compilado en go\nodo-go.exe)
where go >nul 2>nul || echo AVISO: no se encontro go; el nodo Go no se compilo
