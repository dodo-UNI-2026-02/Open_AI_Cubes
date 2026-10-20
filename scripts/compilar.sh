#!/usr/bin/env bash
# Compila Java (compatible con JDK 8) y el nodo Go. Ejecutar desde la carpeta del proyecto.
set -e
cd "$(dirname "$0")/.."
rm -rf out && mkdir -p out
if javac --release 8 -version >/dev/null 2>&1; then REL="--release 8 -Xlint:-options"; else REL=""; fi
javac $REL -encoding UTF-8 -d out $(find java/src -name '*.java')
echo "Java compilado en out/"
if command -v go >/dev/null 2>&1; then
  (cd go && go build -o nodo-go . && echo "Nodo Go compilado en go/nodo-go")
else
  echo "AVISO: no se encontro 'go'; el nodo Go no se compilo"
fi
