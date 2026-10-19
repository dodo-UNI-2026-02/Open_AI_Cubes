# Nodo Python (Open AI Cubes)

Nodo de cómputo de la CNN en Python, **solo con la biblioteca estándar** (sin numpy).
Funciona con Python 3.8 o superior; con 3.12+ es más rápido porque usa `math.sumprod`.
Todo está en `node.py`. Los comandos se ejecutan desde la raíz del repositorio.

## Modos

```bash
# 1. Verificar contra la referencia de Java (debe decir VERIFICACION OK)
python3 python/node.py --verify data/ref.bin --init data/init_weights.bin --train data/train.bin [--threads 4]

# 2. Entrenamiento local sin red (filas "secuencial" y "paralelo" de la matriz)
python3 python/node.py --bench --train data/train.bin --init data/init_weights.bin \
    --images 256 --epochs 1 --batch 64 --threads 4 --lr 0.01 \
    [--test data/test.bin --test-images 500] [--csv results/x.csv --label paralelo] [--save model.bin]

# 3. Nodo de red (modo por defecto)
python3 python/node.py --server 127.0.0.1 --port 6000 --threads 4 [--verbose] [--once]
```

| Opción | Por defecto | Significado |
|---|---|---|
| `--threads H` | núcleos (red), 1 (bench/verify) | procesos de cálculo (con H > 1 se usan H procesos, ver abajo) |
| `--server`, `--port` | 127.0.0.1, 6000 | servidor central |
| `--once` | no | no reconectar al terminar la conexión |
| `--verbose` | no | una línea por cada mensaje procesado (pesos, tareas) |
| `--images`, `--epochs`, `--batch`, `--lr`, `--momentum` | todas, 1, 64, 0.01, 0.9 | bench |
| `--test`, `--test-images` | sin prueba, todas | conjunto de prueba (solo si se pasa `--test`) |
| `--csv`, `--label` | sin CSV, secuencial/paralelo | agrega una fila al CSV (`lenguaje=python`, `maquinas=1`) |

La salida del bench tiene el mismo formato que `cubes.bench.LocalTrain` de Java y termina
con `TOTAL <segundos> s`. Los números se redondean como el `printf` de Java, así las filas
del CSV coinciden con las de Java para el mismo entrenamiento.

## Nodo de red

- Envía `REGISTER` (lenguaje `python`, núcleos = `os.cpu_count()`, hilos = H, hostname).
- Un hilo envía `HEARTBEAT` cada 2 s con las tareas pendientes; las escrituras al socket
  usan un lock. Los mensajes se procesan de a uno, en el orden de llegada.
- `SET_WEIGHTS` guarda los pesos por slot junto con su versión. Si una tarea trae otra
  versión responde `ERROR "version"` con el id de la tarea.
- Si el servidor no está o se cae la conexión (también tras un `BYE` del servidor),
  reintenta cada 3 s. Los pesos guardados se borran al reconectar.
- Ctrl+C (o `kill`) envía `BYE` y termina.

## Cómo se logra la velocidad sin numpy

- **im2col + productos punto en C**: los parches de cada convolución se arman con
  `operator.itemgetter` sobre tablas de índices precalculadas, y cada salida es un producto
  punto calculado en C (`math.dist` con la identidad `w·x = (|w|²+|x|²-|w-x|²)/2`, o
  `math.sumprod`).
- **ReLU fusionada con el max pooling** y gradientes **dispersos** (solo la posición
  ganadora de cada ventana tiene gradiente).
- **Paralelismo con procesos** (el GIL impide usar hilos): las imágenes de cada tarea se
  reparten entre H procesos que viven todo el tiempo del nodo; cada uno ejecuta todos los
  micro chunks para sus imágenes y el proceso principal suma pérdidas y gradientes. Los
  pesos llegan a cada proceso una sola vez por versión, por una tubería.

## Rendimiento medido (4 núcleos Xeon 2.8 GHz, Python 3.13, 256 imágenes, lote 64)

| | Python | Java (misma máquina) |
|---|---|---|
| `--threads 1` | ~19 img/s (13.2 s) | ~134 img/s |
| `--threads 4` | ~52 img/s (4.9 s) | ~222 img/s |

Con Python 3.11 (sin `math.sumprod`) el mismo cálculo es ~30% más lento. La pérdida y el acierto son los mismos
que en Java (la verificación da errores máximos de ~6e-6 en gradientes).
