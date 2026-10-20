# Open AI Cubes: CNN distribuida (CC4P1, Parcial 2026-II)

**Curso:** Programación Concurrente y Distribuida (CC4P1) · UNI 2026-2 · Parcial

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

El equipo lo despliega con el **servidor en Google Cloud** (IP 34.176.215.27) y un **nodo por
laptop**, cada integrante con su lenguaje. También funciona con el servidor en una de las
laptops.

## Documentos

| Documento | Para qué |
|---|---|
| `docs/informe/Informe_OpenAICubes.pdf` | Informe |
| `docs/informe/Presentacion_OpenAICubes.pdf` | Presentación |
| `docs/informe/Notas_exposicion.pdf` | Notas para la exposición |
| [docs/PRUEBAS_EN_VIVO.md](docs/PRUEBAS_EN_VIVO.md) | Qué pueden pedir en la exposición y qué tener listo |
| [docs/NUBE_GCP.md](docs/NUBE_GCP.md) | Servidor en Google Cloud (punto opcional del enunciado). Al inicio están los comandos del día a día: `tmux attach -t oac` para volver a la consola del servidor y `Ctrl+B` y luego `D` para salir dejándolo trabajando |
| [docs/CNN_SPEC.md](docs/CNN_SPEC.md) | La CNN: capas, pesos y fórmulas |
| [docs/PROTOCOLO.md](docs/PROTOCOLO.md) | El protocolo OC/1: tramas, mensajes y secuencias |

## Videos

| Video | Enlace |
|---|---|
| One AI, three computers, zero libraries | [TikTok](https://vt.tiktok.com/ZSgMETeo5/) |
| Java, Go and Python training one AI. That's concurrency. | [TikTok](https://vt.tiktok.com/ZSgrRQUQM/) |

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
| `python/` | Nodo de cómputo Python ([README](python/README.md)) |
| `go/` | Nodo de cómputo Go ([README](go/README.md)) |
| `scripts/` | Scripts `.bat` (Windows) y `.sh` (Linux/macOS) |
| `data/` | `test.bin`, `classes.txt` y pesos (`model.bin` del chat, `init_weights.bin` para las mediciones, `ref.bin` para verificar los nodos) |
| `imagenes_para_probar/` | Fotos para probar el chat |
| `results/ejemplo_vm/` | CSV de una corrida de prueba anterior (ver "Evaluación") |
| `docs/` | Documentos de la tabla de arriba |
| `minimo-alternativo/` | **Plan B:** la misma idea en 6 archivos (~1 500 líneas en vez de ~9 000), con su propio protocolo; ver su [README](minimo-alternativo/README.md). No se mezcla con esta versión |

## Requisitos

- Java 8 o superior (JDK para compilar; para usar el chat basta el JRE).
- Python 3.8 o superior (nodo Python). En Windows el comando es `python`; en Linux, `python3`.
- Go 1.18 o superior (solo para compilar el nodo Go; el kit trae el binario ya compilado).

## Compilar

```bash
scripts\compilar.bat          # Linux/macOS: scripts/compilar.sh
```

## Ejecutar en una sola máquina (prueba rápida)

```bash
scripts\demo_local.bat        # servidor + 3 nodos + prueba cruzada (Linux/macOS: scripts/demo_local.sh)
scripts\cliente.bat           # abre el chat
```

## Ejecutar con varias laptops

`S` es la IP del servidor: 34.176.215.27 si está en Google Cloud, o la IP de la laptop que lo corre
(`ipconfig`).

1. **Servidor:** en Google Cloud ya está preparado (ver [NUBE_GCP.md](docs/NUBE_GCP.md)). En una
   laptop: `scripts\servidor.bat`, aceptando el aviso del firewall de Windows para los puertos
   5000 y 6000.
2. **Nodos**, uno por laptop: `scripts\nodo_java.bat S 4`, `scripts\nodo_go.bat S 4` o
   `scripts\nodo_python.bat S 4`. Una misma laptop puede tener varios nodos esperando; el servidor
   elige cuáles trabajan.
3. **Clientes**, desde cualquier máquina: `scripts\cliente.bat S`.
   - En el chat, las fotos se envían con los botones, arrastrándolas a la ventana o pegándolas
     con Ctrl+V.
   - "Identificar carpeta" manda muchas fotos en una sola petición y el servidor las reparte
     entre todos los nodos. Si la carpeta tiene subcarpetas con el nombre de la clase (por
     ejemplo `fotos/gato/`), el chat marca cuáles acertó.
4. **Consola admin:** `java -cp out cubes.client.AdminClient --host S estado` (o `crosscheck`, o
   `entrenar ...`).

### Día de la presentación: abrir el firewall y cerrarlo después

Normalmente la regla de firewall `permitir-open-ai-cubes` de Google Cloud solo deja entrar a las
IP públicas del equipo. En la presentación, cualquier compañero o el profesor puede querer
conectarse desde la red de la UNI, así que **solo durante la exposición** se abre a todos:

1. **Antes de empezar:** consola de Google Cloud → ☰ → **Red de VPC → Firewall** →
   `permitir-open-ai-cubes` → **Editar**. En *Rangos de IPv4 de origen*, agregar `0.0.0.0/0` (deja
   las IP del equipo como están) → **Guardar**.
2. Encender la VM y arrancar el servidor (ver [NUBE_GCP.md](docs/NUBE_GCP.md)). Probar desde el
   aula: `Test-NetConnection 34.176.215.27 -Port 5000`.
3. **Al terminar la exposición:** misma regla → **Editar** → quitar `0.0.0.0/0` → **Guardar**.
   Después, detener la VM.

El protocolo no está cifrado y el login del chat es de demostración: por eso la regla no debe
quedar abierta a todo internet más tiempo del necesario.

## Evaluación (matriz de 12 casos)

| Escenario | Caso 1: Java | Caso 2: Python | Caso 3: Go / mixto |
|---|---|---|---|
| Secuencial | `bench_local` | `bench_local` | `bench_local` |
| Paralelo | `bench_local` | `bench_local` | `bench_local` |
| Distribuido (nodos con 1 hilo) | `bench_distribuido S distribuido "1 2 3"` | ídem | ídem |
| Paralelo distribuido (nodos con H hilos) | `bench_distribuido S paralelo_distribuido "1 2 3"` | ídem | ídem |

Luego `scripts\carga.bat S` para la prueba de saturación y `scripts\graficas.bat` para las
gráficas. Los resultados quedan en `results/*.csv` y las gráficas en `results/*.png`.

Cómo se mide:
- Cada caso se repite `REPS` veces (3 por defecto) y las gráficas usan la **mediana**. Java hace un
  lote de calentamiento sin medir, para que el JIT no infle la primera corrida.
- `THREADS="1 2 4 8"` (en Windows, `set THREADS=1 2 4 8`) barre varios números de hilos en
  `bench_local` y muestra cómo escala el escenario paralelo.
- El **speedup** se calcula con img/s respecto al secuencial del mismo lenguaje, y la
  **eficiencia** es speedup / núcleos.
- En las corridas distribuidas el servidor separa, lote a lote:
  - el **cómputo**: lo que tarda el nodo más lento;
  - la **comunicación y agregación**: enviar pesos e imágenes, esperar y sumar gradientes.

  Van en las columnas `computo_s` y `comunicacion_s` de `distribuido.csv` y en la gráfica
  `costo_comunicacion.png`. La columna `maquinas` cuenta los nodos usados, y `hosts`, cuántas
  máquinas distintas los aportaron.
- Gráficas: `matriz_tiempos`, `escalabilidad_tiempo`, `escalabilidad_speedup`,
  `escalabilidad_eficiencia`, `costo_comunicacion`, `carga_latencia` y `carga_throughput`.
- **Laptops:** medir enchufadas y con el plan de energía "Alto rendimiento". En procesadores con
  núcleos rápidos y lentos (Intel de 12.ª/13.ª gen.), el secuencial puede caer en un núcleo lento
  y dar un speedup falso; ver [PRUEBAS_EN_VIVO.md](docs/PRUEBAS_EN_VIVO.md), sección 5.

`results/ejemplo_vm/` trae los CSV de una corrida de prueba anterior, con varios nodos en una sola
VM de 4 núcleos (de ahí la etiqueta "6 nodos mixtos" de `carga.csv`). La fila `prueba10` es una
corrida larga de esa misma época. Sirven de ejemplo del formato; las mediciones del informe se
hacen con las laptops del equipo. Sus gráficas se regeneran con
`java -cp out cubes.tools.Charts results/ejemplo_vm`.

## Empaquetar

```bash
scripts/empaquetar.sh entrega   # dist/OpenAICubes_entrega.zip: fuentes, scripts, pesos, docs y PDF (para Univirtual)
scripts/empaquetar.sh kit       # dist/OpenAICubes_kit.zip: ya compilado (Java 8 + nodo Go Windows/Linux) para cada laptop
```

En Windows se corre desde Git Bash.

## Dataset

`data/train.bin` (61 MB) va aparte, en `datos_train.zip`, por su tamaño. Se descarga desde la
sección *Releases* del repositorio y se descomprime en la raíz del proyecto (el zip ya trae la
carpeta `data/`). Solo hace falta para entrenar y para los benchmarks; el chat funciona sin él,
con `data/model.bin`.

Se usan 4 clases de CIFAR-10 (avión, automóvil, gato, barco): 20 000 imágenes de entrenamiento y
4 000 de prueba, de 32×32 píxeles. Para regenerarlo, o para agregar clases propias (por ejemplo
fotos de pandas):

```bash
java -cp out cubes.tools.DatasetBuilder <carpeta_con_subcarpetas> data/train.bin airplane=avion cat=gato panda=panda
```

## Entrenar el modelo del chat

El modelo entregado (`data/model.bin`) acierta 3 292 de las 4 000 imágenes de prueba (82,3 %).

- **Local:** `java -cp out cubes.bench.LocalTrain --threads 4 --epochs 8 --save data/model.bin`.
  Tarda unos 5 minutos con 4 núcleos.
- **En el clúster**, con todos los nodos conectados: `scripts\entrenar_cluster.bat S [epocas] [lenguajes]`.
  - Por defecto: 8 épocas y todos los nodos. Con `java,go` quedan fuera los nodos Python, que son
    más lentos.
  - El servidor publica el modelo para el chat sin reiniciar y lo guarda en `data/model.bin`; el
    anterior queda en `data/model_anterior.bin`.
