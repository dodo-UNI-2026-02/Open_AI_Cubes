#!/usr/bin/env bash
# Genera las graficas PNG del informe a partir de results/*.csv
cd "$(dirname "$0")/.."
java -cp out cubes.tools.Charts results
