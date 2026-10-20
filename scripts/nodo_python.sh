#!/usr/bin/env bash
# Uso: scripts/nodo_python.sh <ip_servidor> [hilos]
cd "$(dirname "$0")/.."
PY=python3; $PY -c "" >/dev/null 2>&1 || PY=python   # en Windows "python3" puede ser un acceso directo vacio
exec $PY python/node.py --server "${1:-127.0.0.1}" --port 6000 --threads "${2:-$(nproc)}"
