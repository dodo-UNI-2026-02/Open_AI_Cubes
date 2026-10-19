#!/usr/bin/env bash
# Uso: scripts/nodo_go.sh <ip_servidor> [hilos]
cd "$(dirname "$0")/.."
exec go/nodo-go --server "${1:-127.0.0.1}" --port 6000 --threads "${2:-$(nproc)}"
