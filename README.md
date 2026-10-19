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

## Videos

| Video | Enlace |
|---|---|
| One AI, three computers, zero libraries | [TikTok](https://vt.tiktok.com/ZSgMETeo5/) |
| Java, Go and Python training one AI. That's concurrency. | [TikTok](https://vt.tiktok.com/ZSgrRQUQM/) |

## Informe y presentación

`docs/informe/Informe_OpenAICubes.pdf` y `docs/informe/Presentacion_OpenAICubes.pdf`.

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
| `docs/` | Especificación de la CNN, del protocolo y de la arquitectura |
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

## Ejecutar en varias máquinas (clúster)

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
| Distribuido (nodos con 1 hilo) | `bench_distribuido.sh <ip> distribuido "1 2 4"` | ídem | ídem |
| Paralelo distribuido (nodos con H hilos) | `bench_distribuido.sh <ip> paralelo_distribuido "1 2 4"` | ídem | ídem |

Luego `scripts/carga.sh <ip>` para la prueba de saturación y `scripts/graficas.sh` para las gráficas.
Todos los resultados quedan en `results/*.csv` y las gráficas en `results/*.png`.
La carpeta `results/ejemplo_vm/` trae los CSV de la corrida de prueba en una sola máquina de 4 núcleos, separados para que no se
mezclen con los del laboratorio. Sus gráficas se regeneran con `java -cp out cubes.tools.Charts results/ejemplo_vm`.

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
