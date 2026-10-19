#!/usr/bin/env bash
# Uso: scripts/nodo_python.sh <ip_servidor> [hilos]
cd "$(dirname "$0")/.."
exec python3 python/node.py --server "${1:-127.0.0.1}" --port 6000 --threads "${2:-$(nproc)}"
