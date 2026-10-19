#!/usr/bin/env bash
# Filas "secuencial" y "paralelo" de la matriz: Java, Python y Go en ESTA maquina, sin red.
# Uso: scripts/bench_local.sh [imagenes] [hilos_paralelo]
cd "$(dirname "$0")/.."
IMG="${1:-1024}"; H="${2:-$(nproc)}"
mkdir -p results
COMMON="--train data/train.bin --init data/init_weights.bin --images $IMG --epochs 1 --batch ${BATCH:-64} --csv results/local.csv"
for T in 1 "$H"; do
  if [ "$T" = "1" ]; then L=secuencial; else L=paralelo; fi
  echo "== Java $L ($T hilos)";   java -Xmx2g -cp out cubes.bench.LocalTrain $COMMON --test none --threads "$T" --label "$L"
  echo "== Go $L ($T hilos)";     go/nodo-go --bench $COMMON --threads "$T" --label "$L"
  echo "== Python $L ($T hilos)"; python3 python/node.py --bench $COMMON --threads "$T" --label "$L"
done
echo "Resultados en results/local.csv"
