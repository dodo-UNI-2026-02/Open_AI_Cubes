#!/usr/bin/env bash
# Arma los dos comprimidos del proyecto en dist/ (sin historial de git ni archivos de más).
# Uso: scripts/empaquetar.sh entrega   -> dist/OpenAICubes_entrega.zip  (para Univirtual)
#      scripts/empaquetar.sh kit       -> dist/OpenAICubes_kit.zip      (listo para correr en las laptops Windows o en Linux)
#
# entrega: solo fuentes (.java .go .py), scripts, pesos y datos de prueba, docs y los PDF de
#          informe, presentación y notas. Es lo que pide el enunciado; nada de .git, out/ ni binarios.
#          train.bin (61 MB) no va por defecto: está en el Release v1.0. Para incluirlo: CON_TRAIN=1
# kit:     lo mismo pero ya compilado: clases Java 8 en out/ y nodo Go para Windows y Linux x86-64, más
#          train.bin si existe. Se copia a cada laptop y no hace falta compilar.
set -e
cd "$(dirname "$0")/.."
MODO="${1:-entrega}"
PY=python3; $PY -c "" >/dev/null 2>&1 || PY=python
STAGE="dist/_tmp"; NOMBRE="OpenAICubes"
rm -rf "$STAGE" && mkdir -p "$STAGE/$NOMBRE"
D="$STAGE/$NOMBRE"

copiar() { mkdir -p "$D/$(dirname "$1")" && cp -r "$1" "$D/$1"; }

copiar README.md
copiar java/src
mkdir -p "$D/go" && cp go/*.go go/go.mod go/README.md "$D/go/" 2>/dev/null || cp go/*.go go/go.mod "$D/go/"
mkdir -p "$D/python" && cp python/node.py "$D/python/"
mkdir -p "$D/scripts" && cp scripts/*.sh scripts/*.bat "$D/scripts/"
mkdir -p "$D/docs/informe" && cp docs/*.md "$D/docs/" && cp docs/informe/*.pdf "$D/docs/informe/"
mkdir -p "$D/data" && cp data/classes.txt data/model.bin data/init_weights.bin data/ref.bin data/test.bin "$D/data/"
mkdir -p "$D/results/ejemplo_vm" && cp results/ejemplo_vm/*.csv "$D/results/ejemplo_vm/"

if [ "$MODO" = "kit" ]; then
  bash scripts/compilar.sh >/dev/null
  cp -r out "$D/out"
  if command -v go >/dev/null 2>&1; then
    # las laptops del equipo usan Windows (nodo_go.bat); Linux por si se corre en otro clúster o en la nube
    (cd go && GOOS=windows GOARCH=amd64 CGO_ENABLED=0 go build -o "../$D/go/nodo-go.exe" .)
    (cd go && GOOS=linux GOARCH=amd64 CGO_ENABLED=0 go build -o "../$D/go/nodo-go" .)
  else
    echo "AVISO: sin 'go' no se incluye el nodo Go compilado (en cada máquina: scripts/compilar.sh)"
  fi
  [ -f data/train.bin ] && cp data/train.bin "$D/data/"
  SALIDA="dist/${NOMBRE}_kit.zip"
else
  [ "${CON_TRAIN:-0}" = "1" ] && [ -f data/train.bin ] && cp data/train.bin "$D/data/"
  SALIDA="dist/${NOMBRE}_entrega.zip"
fi

rm -f "$SALIDA"
(cd "$STAGE" && $PY -m zipfile -c "../$(basename "$SALIDA")" "$NOMBRE")
rm -rf "$STAGE"
echo "Listo: $SALIDA ($(du -h "$SALIDA" | cut -f1))"
$PY -m zipfile -l "$SALIDA" | awk 'NR>1{print $1}' | sed 's#/[^/]*$##' | sort | uniq -c | sort -k2 | head -40
