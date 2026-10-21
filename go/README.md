# Nodo de cómputo Go (`nodo-go`)

Nodo de Open AI Cubes escrito en Go usando **solo la biblioteca estándar** (sin módulos
externos). Calcula la misma CNN que Java (`docs/CNN_SPEC.md`) y habla el protocolo OC/1
(`docs/PROTOCOLO.md`) con el servidor central.

Requisito: Go 1.18 o posterior.

```
cd go
go build -o nodo-go .          # en Windows: go build -o nodo-go.exe .
```

## Archivos

| Archivo | Contenido | Equivalente en Java |
|---|---|---|
| `main.go` | argumentos (`flag`) y selección de modo | — |
| `params.go` | vector de pesos, archivo OCW1, dataset CIFAR, SGD | `Params`, `Dataset`, `Sgd` |
| `executor.go` | reparte un rango entre H goroutines y espera (barrera por capa) | `ChunkExecutor` |
| `chunks.go` | micro chunks de convolución, MLP y pooling | `ConvMicroChunk`, `MlpMicroChunk`, `PoolMicroChunk` |
| `cnn.go` | forward, backward, softmax, predicción | `Cnn` |
| `bench.go` | `--verify` y `--bench` (entrenamiento local) | `RefDump`, `LocalTrain` |
| `protocol.go` | tramas OC/1 (cabecera de 12 bytes, big-endian) | `cubes.net.*` |
| `node.go` | nodo en red: registro, latidos, cola de mensajes, reconexión | `JavaNode` |

## Modos

### 1. Verificación contra Java

Compara pérdida, aciertos, cada gradiente y las probabilidades con `data/ref.bin`
(tolerancia `|a-b| <= 1e-3 + 1e-3·|b|`). Termina con `OK` o `FAIL` (código de salida 1).

```
./nodo-go --verify ../data/ref.bin --init ../data/init_weights.bin --train ../data/train.bin --threads 1
./nodo-go --verify ../data/ref.bin --init ../data/init_weights.bin --train ../data/train.bin --threads 4
```

### 2. Benchmark local (sin red)

Misma salida que `LocalTrain` de Java (una línea por época y al final `TOTAL <s> s`) y la
misma fila CSV (`lenguaje=go`, `maquinas=1`).

```
./nodo-go --bench --train ../data/train.bin --init ../data/init_weights.bin \
          --images 1024 --epochs 1 --batch 64 --threads 4 --lr 0.01 \
          --test ../data/test.bin --test-images 1000 \
          --csv resultados.csv --label paralelo --save /tmp/modelo-go.bin
```

- `--images 0` (por defecto) usa todo el archivo de entrenamiento.
- Sin `--test` no se evalúa y `acierto_test` queda `NaN`.
- `--label` por defecto: `secuencial` si `--threads 1`, si no `paralelo`.

### 3. Nodo en red (modo por defecto)

```
./nodo-go --server 192.168.1.10 --port 6000 --threads 4
```

- Envía `REGISTER` (lenguaje `go`, núcleos, hilos, hostname) y `HEARTBEAT` cada 2 s desde
  otra goroutine (las escrituras al socket van protegidas con un mutex).
- Una goroutine lee las tramas y las encola; otra las procesa **en orden**:
  `SET_WEIGHTS` guarda pesos y versión por slot (0 = servicio, 1 = entrenamiento),
  `TRAIN_TASK` responde `GRADIENTS`, `INFER_TASK` responde `INFER_RESULT`.
  Si la versión no coincide con la del slot responde `ERROR "version"` con el id de la tarea.
- Si el servidor no está o se corta la conexión, reintenta cada `--retry` segundos (3 por
  defecto). Al reconectar se descartan los pesos guardados.
- Ctrl+C envía `BYE` y cierra.
- `--threads` por defecto: número de núcleos. `--quiet` evita una línea por mensaje.

## Opciones

```
./nodo-go -h
```

Los flags aceptan `-flag` o `--flag`, con el valor separado por espacio o por `=`. Los booleanos
(`--bench`, `--quiet`) van solos o como `--bench=true`.
