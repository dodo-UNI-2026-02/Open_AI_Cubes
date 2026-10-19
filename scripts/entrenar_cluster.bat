@echo off
rem Entrena el modelo del chat usando el cluster (todos los nodos conectados) y lo publica:
rem el chat pasa a usarlo de inmediato y el servidor lo guarda en data\model.bin.
rem Uso: entrenar_cluster.bat ^<ip_servidor^> [epocas] [lenguajes]
rem   lenguajes: all (por defecto), o por ejemplo "java,go" (con comillas) para dejar fuera a los nodos Python
cd /d "%~dp0\.."
set E=%2
if "%E%"=="" set E=8
set L=%~3
if "%L%"=="" set L=all
java -cp out cubes.client.AdminClient --host %1 entrenar --images 1000000 --epochs %E% --batch 64 --lr 0.01 --langs %L% --label modelo --publish
