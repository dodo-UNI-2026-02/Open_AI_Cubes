#!/usr/bin/env bash
# Prueba de carga escalonada hasta saturar el servidor.
# Uso: scripts/carga.sh <ip_servidor> [etiqueta] [niveles] [peticiones_por_cliente]
cd "$(dirname "$0")/.."
java -Xmx2g -cp out cubes.client.LoadGenerator --host "${1:-127.0.0.1}" --label "${2:-carga}" \
  --ramp "${3:-10,50,100,200,400,800}" --requests "${4:-20}" --min-delay 0 --max-delay 200 \
  --test data/test.bin --csv results/carga.csv
