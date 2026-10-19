#!/usr/bin/env bash
# Servidor central: puerto 5000 (clientes) y 6000 (nodos).
cd "$(dirname "$0")/.."
exec java -Xmx2g -cp out cubes.server.CentralServer "$@"
