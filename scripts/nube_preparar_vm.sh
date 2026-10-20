#!/usr/bin/env bash
# Prepara una VM Ubuntu en la nube (Google Cloud, AWS, Azure u otra) para correr el SERVIDOR CENTRAL.
# Se ejecuta DENTRO de la VM, una sola vez:
#   curl -fsSL https://raw.githubusercontent.com/dodo-UNI-2026-02/Open_AI_Cubes/main/scripts/nube_preparar_vm.sh | bash
# o, si ya se clonó el repo:  bash scripts/nube_preparar_vm.sh
# Guías: docs/NUBE_GCP.md (elegida), docs/NUBE_AWS.md y docs/NUBE_AZURE.md
set -e
REPO=https://github.com/dodo-UNI-2026-02/Open_AI_Cubes.git
ZIP=https://github.com/dodo-UNI-2026-02/Open_AI_Cubes/releases/download/v1.0/datos_train.zip

echo "== 1/5 Paquetes: JDK (para compilar con --release 8), unzip, git, tmux"
sudo apt-get update -qq
sudo apt-get install -y -qq openjdk-17-jdk-headless unzip git tmux curl >/dev/null

echo "== 2/5 Swap de 1 GB (la VM B1s solo tiene 1 GB de RAM)"
if [ ! -f /swapfile ]; then
  sudo fallocate -l 1G /swapfile && sudo chmod 600 /swapfile && sudo mkswap /swapfile >/dev/null && sudo swapon /swapfile
  echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab >/dev/null
fi

echo "== 3/5 Código"
if [ -f scripts/compilar.sh ]; then
  DIR="$(pwd)"
else
  [ -d "$HOME/Open_AI_Cubes" ] || git clone -q "$REPO" "$HOME/Open_AI_Cubes"
  DIR="$HOME/Open_AI_Cubes"
fi
cd "$DIR"

echo "== 4/5 Datos de entrenamiento (Release v1.0, 54 MB)"
if [ ! -f data/train.bin ]; then
  curl -fsSL -o /tmp/datos_train.zip "$ZIP" && unzip -oq /tmp/datos_train.zip && rm /tmp/datos_train.zip
fi
ls -lh data/*.bin

echo "== 5/5 Compilar (solo Java: en la nube corre el servidor; los nodos van en el laboratorio)"
bash scripts/compilar.sh

IP=$(curl -fsS https://api.ipify.org 2>/dev/null || echo "<IP_PUBLICA>")
cat <<EOF

Listo. Para arrancar el servidor y que siga vivo al cerrar SSH:
  cd $DIR
  tmux new -s oac            # (para volver luego: tmux attach -t oac; para salir sin cerrarlo: Ctrl+B y luego D)
  JAVA_MEM=-Xmx600m scripts/servidor.sh

Desde cada laptop, en Windows (puertos 5000 y 6000 abiertos en el firewall de la nube):
  scripts\\nodo_java.bat   $IP 4
  scripts\\nodo_go.bat     $IP 4
  scripts\\nodo_python.bat $IP 4
  scripts\\cliente.bat     $IP
(en Linux o macOS, los mismos scripts con .sh)
EOF
