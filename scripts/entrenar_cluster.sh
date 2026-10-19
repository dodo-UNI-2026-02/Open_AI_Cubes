#!/usr/bin/env bash
# Entrena el modelo del chat usando el clúster (todos los nodos conectados) y lo publica:
# el chat pasa a usarlo de inmediato y el servidor lo guarda en data/model.bin.
# Uso: scripts/entrenar_cluster.sh <ip_servidor> [epocas] [lenguajes]
#   lenguajes: all (por defecto), o por ejemplo java,go para dejar fuera a los nodos Python (mas rapido)
cd "$(dirname "$0")/.."
java -cp out cubes.client.AdminClient --host "${1:-127.0.0.1}" entrenar --images 1000000 --epochs "${2:-8}" \
  --batch 64 --lr 0.01 --langs "${3:-all}" --label modelo --publish
