# Open AI Cubes: CNN distribuida (CC4P1, Parcial 2026-II)

Sistema distribuido que entrena y sirve una red neuronal convolucional (CNN) para reconocer
imágenes. Los clientes y el servidor central están en **Java** y los nodos de cómputo en **Java,
Python y Go**. Todo usa solo la librería estándar de cada lenguaje: sockets TCP e hilos, con un
protocolo binario propio. No se usa HTTP, WebSocket, Socket.IO, middleware ni librerías externas.

```
 Clientes (Java)                Servidor central (Java)                 Nodos de cómputo
 ┌──────────────┐  TCP 5000   ┌──────────────────────────┐  TCP 6000   ┌──────────────┐
 │ Chat Swing   │────────────►│ Backend (sesiones,       │◄────────────│ Nodo Java    │
 │ Gen. de carga│             │ historial, cola)         │             │ Nodo Python  │
 │ Consola admin│             │ Coordinador (registro,   │             │ Nodo Go      │
 └──────────────┘             │ reparto, gradientes)     │             └──────────────┘
                              └──────────────────────────┘
```

## Versión mínima (en prueba)

La carpeta [`minimo/`](minimo/) tiene la misma idea en 6 archivos y unas 1 500 líneas, en lugar de unas 9 200. Usa la misma CNN y el mismo `data/model.bin`, con un protocolo más simple; ver su [README](minimo/README.md). No es compatible con los nodos de esta versión: cada servidor trabaja con sus propios nodos. Si el equipo la aprueba, reemplazará a esta versión, que seguirá disponible en el Release [v1.0](../../releases/tag/v1.0).

## Videos

| Video | Enlace |
|---|---|
| One AI, three computers, zero libraries | [TikTok](https://vt.tiktok.com/ZSgMETeo5/) |
| Java, Go and Python training one AI. That's concurrency. | [TikTok](https://vt.tiktok.com/ZSgrRQUQM/) |

## Informe y presentación

`docs/informe/Informe_OpenAICubes.pdf`, `docs/informe/Presentacion_OpenAICubes.pdf` y las notas de
exposición `docs/informe/Notas_exposicion.pdf`.

Guías: [pruebas en vivo](docs/PRUEBAS_EN_VIVO.md) (qué pueden pedir en la exposición y qué tener listo) y
servidor en la nube (punto opcional del enunciado) con [Google Cloud](docs/NUBE_GCP.md), la opción elegida; [AWS](docs/NUBE_AWS.md) y
[Azure](docs/NUBE_AZURE.md) quedan como alternativas.
El servidor del equipo corre en Google Cloud (IP 34.176.215.27); los comandos del día a día, incluido
`tmux attach -t oac` para volver a su consola, están al inicio de [NUBE_GCP.md](docs/NUBE_GCP.md).

## Contenido

| Carpeta | Qué hay |
|---|---|
| `java/src/cubes/cnn` | CNN y micro chunks (convolución, MLP, pooling), dataset, optimizador |
| `java/src/cubes/net` | Protocolo OC/1 (tramas y payloads) |
| `java/src/cubes/server` | Servidor central: backend, registro de nodos, inferencia y entrenamiento distribuido |
| `java/src/cubes/node` | Nodo de cómputo Java |
| `java/src/cubes/client` | Chat Swing (frontend), generador de carga, consola admin |
| `java/src/cubes/bench` | Entrenamiento local (escenarios secuencial y paralelo en Java) |
| `java/src/cubes/tools` | Constructor del dataset, gráficas, verificaciones |
| `python/node.py` | Nodo de cómputo Python |
| `go/` | Nodo de cómputo Go |
| `scripts/` | Scripts `.sh` (Linux/macOS) y `.bat` (Windows) |
| `docs/` | Especificación de la CNN y del protocolo, guía de la nube, guía de pruebas en vivo, informe |
| `data/` | Dataset (`train.bin`, `test.bin`, `classes.txt`) y pesos (`init_weights.bin`, `model.bin`) |

## Requisitos

- Java 8 o superior (JDK, para compilar).
- Python 3.8 o superior (nodo Python).
- Go 1.18 o superior (solo para compilar el nodo Go).

## Compilar

```bash
scripts/compilar.sh          # Windows: scripts\compilar.bat
```

## Ejecutar en una sola máquina (prueba rápida)

```bash
scripts/demo_local.sh        # servidor + 3 nodos + prueba cruzada (Windows: scripts\demo_local.bat)
scripts/cliente.sh           # abre el chat
```

## Ejecutar en varias máquinas (clúster de laptops)

1. En la máquina servidor: `scripts/servidor.sh`. Abrir los puertos 5000 y 6000 en el firewall.
2. En cada máquina de cómputo (con la IP del servidor):
   `scripts/nodo_java.sh 192.168.1.10 4`, `scripts/nodo_python.sh 192.168.1.10 4`, `scripts/nodo_go.sh 192.168.1.10 4`.
   Cada máquina puede tener los tres nodos esperando; el servidor elige cuáles trabajan.
3. Clientes, desde cualquier máquina: `scripts/cliente.sh 192.168.1.10`. En el chat, las fotos se envían con los botones,
   arrastrándolas a la ventana o pegándolas con Ctrl+V. "Identificar carpeta" manda muchas fotos en una sola petición y
   el servidor las reparte entre todos los nodos (si la carpeta tiene subcarpetas con el nombre de la clase, por ejemplo
   `fotos/gato/`, el chat marca cuáles acertó).
4. Consola admin: `java -cp out cubes.client.AdminClient --host 192.168.1.10 estado` (o `crosscheck`, o `entrenar ...`).

## Evaluación (matriz de 12 casos)

| Escenario | Caso 1: Java | Caso 2: Python | Caso 3: Go / mixto |
|---|---|---|---|
| Secuencial | `bench_local.sh` | `bench_local.sh` | `bench_local.sh` |
| Paralelo | `bench_local.sh` | `bench_local.sh` | `bench_local.sh` |
| Distribuido (nodos con 1 hilo) | `bench_distribuido.sh <ip> distribuido "1 2 3"` | ídem | ídem |
| Paralelo distribuido (nodos con H hilos) | `bench_distribuido.sh <ip> paralelo_distribuido "1 2 3"` | ídem | ídem |

Luego `scripts/carga.sh <ip>` para la prueba de saturación y `scripts/graficas.sh` para las gráficas.
Todos los resultados quedan en `results/*.csv` y las gráficas en `results/*.png`.

Cómo se mide:
- Cada caso se repite `REPS` veces (3 por defecto) y las gráficas usan la **mediana**. Java hace un lote de
  calentamiento sin medir para que el JIT no infle la primera corrida.
- `THREADS="1 2 4 8" scripts/bench_local.sh` barre varios números de hilos y muestra cómo escala el escenario paralelo.
- El **speedup** se calcula con img/s respecto al secuencial del mismo lenguaje, y la **eficiencia** es speedup / núcleos.
- En las corridas distribuidas el servidor separa, lote a lote, el **cómputo** (lo que tarda el nodo más lento) de la
  **comunicación y agregación** (enviar pesos e imágenes, esperar, sumar gradientes): columnas `computo_s` y
  `comunicacion_s` de `distribuido.csv` y gráfica `costo_comunicacion.png`. La columna `maquinas` cuenta nodos usados
  y `hosts` cuántas máquinas distintas los aportaron.
- Gráficas: `matriz_tiempos`, `escalabilidad_tiempo`, `escalabilidad_speedup`, `escalabilidad_eficiencia`,
  `costo_comunicacion`, `carga_latencia` y `carga_throughput`.
- En laptops con núcleos rápidos y lentos (Intel 12.ª/13.ª gen.) el secuencial puede caer en un núcleo lento y dar un
  speedup falso; medir en el clúster o con el plan de energía en "Alto rendimiento".
La carpeta `results/ejemplo_vm/` trae los CSV de la corrida de prueba en una sola máquina de 4 núcleos, separados para que no se
mezclen con los de las laptops del equipo. Sus gráficas se regeneran con `java -cp out cubes.tools.Charts results/ejemplo_vm`.

## Empaquetar

```bash
scripts/empaquetar.sh entrega   # dist/OpenAICubes_entrega.zip: fuentes, scripts, pesos, docs y PDF (para Univirtual)
scripts/empaquetar.sh kit       # dist/OpenAICubes_kit.zip: ya compilado (Java 8 + nodo Go Windows/Linux) para copiar a cada laptop
```

## Dataset

`data/train.bin` (61 MB) va en un archivo aparte, `datos_train.zip`, por su tamaño. Se descarga desde la sección *Releases* del repositorio y se descomprime en la raíz del proyecto (el zip ya trae la carpeta `data/`).
Solo hace falta para entrenar y para los benchmarks; el chat funciona sin él con `data/model.bin`.

Por defecto se usan 4 clases de CIFAR-10 (avión, automóvil, gato, barco): 20 000 imágenes de entrenamiento y
4 000 de prueba, de 32×32 píxeles. Para regenerarlo, o para agregar clases propias (por ejemplo fotos de pandas):

```bash
java -cp out cubes.tools.DatasetBuilder <carpeta_con_subcarpetas> data/train.bin airplane=avion cat=gato panda=panda
```

## Entrenar el modelo del chat

- Local: `java -cp out cubes.bench.LocalTrain --threads 4 --epochs 8 --save data/model.bin` (unos 5 minutos con 4 núcleos, 82 % de acierto en prueba).
- En el clúster, con todos los nodos conectados: `scripts/entrenar_cluster.sh <ip> [epocas] [lenguajes]`
  (por defecto 8 épocas y todos los nodos; `java,go` deja fuera a los nodos Python, que son más lentos). El servidor
  publica el modelo para el chat sin reiniciar y lo guarda en `data/model.bin`; el anterior queda en `data/model_anterior.bin`.
