#!/usr/bin/env bash
# Filas "distribuido" y "paralelo_distribuido" de la matriz.
# Antes de correrlo: servidor encendido y en cada maquina un nodo Java, uno Python y uno Go
#   - para "distribuido":          nodos con 1 hilo   (scripts/nodo_X.sh <ip> 1)
#   - para "paralelo_distribuido": nodos con H hilos  (scripts/nodo_X.sh <ip> H)
# Uso: scripts/bench_distribuido.sh <ip_servidor> <distribuido|paralelo_distribuido> "<maquinas>" [imagenes]
# Ejemplo: scripts/bench_distribuido.sh 192.168.1.10 distribuido "1 2 3" 1024
# "<maquinas>" = cuantos nodos usar; el servidor elige uno por maquina mientras alcancen
# (la columna "hosts" del CSV dice cuantas maquinas distintas aportaron nodos de verdad).
# Variables opcionales:
#   REPS=3     repeticiones de cada caso (las graficas usan la mediana)
#   BATCH=64   con red lenta (WiFi, 100 Mbps o la nube) conviene BATCH=256 para que pese menos
#              la comunicacion (usar el mismo BATCH en bench_local.sh)
cd "$(dirname "$0")/.."
HOST="${1:-127.0.0.1}"; LABEL="${2:-distribuido}"; MACHINES="${3:-1 2 3}"; IMG="${4:-1024}"
REPS="${REPS:-3}"
for R in $(seq 1 "$REPS"); do
  for M in $MACHINES; do
    for LANGS in java python go all; do
      # "all" = cluster mixto (Caso 3); con 1 nodo no hay mezcla posible
      if [ "$LANGS" = "all" ] && [ "$M" = "1" ]; then continue; fi
      echo "== [rep $R/$REPS] $LABEL | nodos=$LANGS | maquinas=$M"
      java -cp out cubes.client.AdminClient --host "$HOST" entrenar --images "$IMG" --epochs 1 --batch "${BATCH:-64}" \
        --langs "$LANGS" --max-nodes "$M" --label "$LABEL"
    done
  done
done
echo "Resultados en results/distribuido.csv (en la maquina del servidor)"
