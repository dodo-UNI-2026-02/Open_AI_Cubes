#!/usr/bin/env bash
# Uso: scripts/nodo_java.sh <ip_servidor> [hilos]
cd "$(dirname "$0")/.."
exec java -Xmx1g -cp out cubes.node.JavaNode --server "${1:-127.0.0.1}" --port 6000 --threads "${2:-$(nproc)}"
