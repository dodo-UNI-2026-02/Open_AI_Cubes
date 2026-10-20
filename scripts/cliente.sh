#!/usr/bin/env bash
# Cliente de escritorio (chat). Uso: scripts/cliente.sh [ip_servidor]
cd "$(dirname "$0")/.."
exec java -cp out cubes.client.ChatClient --host "${1:-127.0.0.1}" --test data/test.bin
