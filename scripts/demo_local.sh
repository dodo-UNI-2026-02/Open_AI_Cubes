#!/usr/bin/env bash
# Levanta todo en una sola maquina para probar: servidor + un nodo de cada lenguaje.
# Ctrl+C detiene todo.
cd "$(dirname "$0")/.."
PY=python3; $PY -c "" >/dev/null 2>&1 || PY=python   # en Windows "python3" puede ser un acceso directo vacio
H="${1:-2}"
trap 'trap - TERM; kill 0' EXIT INT TERM
java -cp out cubes.server.CentralServer < /dev/null &
sleep 2
java -cp out cubes.node.JavaNode --threads "$H" &
$PY python/node.py --threads "$H" &
go/nodo-go --threads "$H" &
sleep 3
java -cp out cubes.client.AdminClient estado
java -cp out cubes.client.AdminClient crosscheck
echo "Todo listo. Abre el chat con: scripts/cliente.sh"
wait
