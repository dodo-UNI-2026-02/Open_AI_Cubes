#!/usr/bin/env bash
# Filas "distribuido" y "paralelo_distribuido" de la matriz.
# Antes de correrlo: servidor encendido y en cada maquina un nodo Java, uno Python y uno Go
#   - para "distribuido":          nodos con 1 hilo   (scripts/nodo_X.sh <ip> 1)
#   - para "paralelo_distribuido": nodos con H hilos  (scripts/nodo_X.sh <ip> H)
# Uso: scripts/bench_distribuido.sh <ip_servidor> <distribuido|paralelo_distribuido> "<maquinas>" [imagenes]
# Ejemplo: scripts/bench_distribuido.sh 192.168.1.10 distribuido "1 2 4" 1024
# Con red lenta (WiFi o 100 Mbps) conviene un lote mayor para que pese menos la comunicacion:
#   BATCH=256 scripts/bench_distribuido.sh ...  (usar el mismo BATCH en bench_local.sh)
cd "$(dirname "$0")/.."
HOST="${1:-127.0.0.1}"; LABEL="${2:-distribuido}"; MACHINES="${3:-1 2 4}"; IMG="${4:-1024}"
for M in $MACHINES; do
  for LANGS in java python go all; do
    # "all" = clúster mixto (Caso 3); con 1 máquina no hay mezcla posible
    if [ "$LANGS" = "all" ] && [ "$M" = "1" ]; then continue; fi
    echo "== $LABEL | nodos=$LANGS | maquinas=$M"
    java -cp out cubes.client.AdminClient --host "$HOST" entrenar --images "$IMG" --epochs 1 --batch "${BATCH:-64}" \
      --langs "$LANGS" --max-nodes "$M" --label "$LABEL"
  done
done
echo "Resultados en results/distribuido.csv (en la maquina del servidor)"
