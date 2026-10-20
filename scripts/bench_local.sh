#!/usr/bin/env bash
# Filas "secuencial" y "paralelo" de la matriz: Java, Python y Go en ESTA maquina, sin red.
# Uso: scripts/bench_local.sh [imagenes] [hilos_paralelo]
# Variables opcionales:
#   REPS=3            repeticiones de cada caso (las graficas usan la mediana)
#   THREADS="1 2 4 8" barrido de hilos para ver como escala el escenario paralelo
#   BATCH=64          tamano de lote (usar el mismo en bench_distribuido.sh)
cd "$(dirname "$0")/.."
IMG="${1:-1024}"; H="${2:-$(nproc)}"
REPS="${REPS:-3}"; THREADS="${THREADS:-1 $H}"
PY=python3; $PY -c "" >/dev/null 2>&1 || PY=python   # en Windows "python3" puede ser un acceso directo vacio
mkdir -p results
COMMON="--train data/train.bin --init data/init_weights.bin --images $IMG --epochs 1 --batch ${BATCH:-64} --csv results/local.csv"
for R in $(seq 1 "$REPS"); do
  for T in $THREADS; do
    if [ "$T" = "1" ]; then L=secuencial; else L=paralelo; fi
    echo "== [rep $R/$REPS] Java $L ($T hilos)";   java -Xmx2g -cp out cubes.bench.LocalTrain $COMMON --test none --threads "$T" --label "$L"
    echo "== [rep $R/$REPS] Go $L ($T hilos)";     go/nodo-go --bench $COMMON --threads "$T" --label "$L"
    echo "== [rep $R/$REPS] Python $L ($T procesos)"; $PY python/node.py --bench $COMMON --threads "$T" --label "$L"
  done
done
echo "Resultados en results/local.csv"
