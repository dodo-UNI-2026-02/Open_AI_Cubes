#!/usr/bin/env bash
# Servidor central: puerto 5000 (clientes) y 6000 (nodos).
cd "$(dirname "$0")/.."
# Memoria: en una VM chica (Azure B1s, 1 GB) usar JAVA_MEM=-Xmx600m scripts/servidor.sh
exec java ${JAVA_MEM:--Xmx2g} -cp out cubes.server.CentralServer "$@"
