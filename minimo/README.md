# Open AI Cubes · versión mínima

**Curso:** Programación Concurrente y Distribuida (CC4P1) · UNI 2026-2 · Parcial

La misma idea que la versión completa, en 6 archivos (más `graficas.py`, que no es parte del sistema). Hay 1 servidor Java y nodos de cómputo en Java, Go y Python, conectados por sockets TCP con un protocolo binario propio y sin librerías externas. La CNN, la arquitectura y el formato de los pesos son los mismos, así que usa `../data/model.bin` (82,3 % de acierto) sin cambios.

| Archivo | Líneas | Qué hace |
|---|---|---|
| `Cnn.java` | 405 | La CNN: convolución, pooling y MLP como *micro chunks* repartidos entre hilos, con una barrera por capa. Entrenamiento local (secuencial y paralelo) y prueba del modelo |
| `Servidor.java` | 343 | Recibe a nodos (puerto 6000) y clientes (puerto 5000). Pone las consultas en una cola compartida, reparte lotes a los nodos, entrena de forma distribuida y reasigna el trabajo si cae un nodo |
| `Nodo.java` | 60 | Nodo Java |
| `nodo.go` | 374 | Nodo Go: misma CNN, con goroutines |
| `nodo.py` | 236 | Nodo Python: misma CNN, con procesos (el GIL impide calcular en paralelo con hilos) |
| `Cliente.java` | 295 | Chat de escritorio (Swing) con burbujas: eliges o arrastras una foto, pulsas Identificar y el bot responde con la clase, la confianza, el nodo que la calculó y la latencia. También hace la prueba de carga |

## Cómo funciona

- **Dentro de cada nodo:** cada capa de la red se parte en rangos de filtros o neuronas, y cada hilo o goroutine calcula uno (*micro chunk*). En Python, cada proceso calcula una parte de las imágenes.
- **Consultas del chat (inferencia):** cada cliente es un hilo productor que pone su foto en una cola. El hilo de cada nodo saca lotes de hasta 16 fotos. Un nodo rápido vuelve antes por más trabajo, así que el reparto se balancea solo.
- **Entrenamiento distribuido:** cada lote de 64 fotos se reparte entre los nodos según su velocidad medida. Cada nodo devuelve la suma de los gradientes de su parte, y el servidor los combina y actualiza los pesos (SGD con momento).
- **Tolerancia a fallos:** si un nodo se cae, sus consultas vuelven a la cola y su parte del lote pasa a otro nodo. Si no queda ningún nodo, el servidor responde por su cuenta.

Protocolo con los nodos (big-endian): al conectarse, el nodo envía su lenguaje y su número de hilos. Luego recibe `'W'` (pesos nuevos), `'I'` (fotos que debe identificar; responde con las probabilidades) o `'T'` (fotos con etiquetas para entrenar; responde con los gradientes, la pérdida y los aciertos). El cliente envía la foto de 32×32 (3 072 bytes) y recibe `clase;confianza;nodo`.

## Ejecutar en una sola PC

Desde esta carpeta, con JDK 8 o superior, Go y Python 3.8 o superior:

```bash
javac -encoding UTF-8 -d out *.java
go build -o nodo-go.exe nodo.go
```

```bash
java -cp out Servidor                  # terminal 1; acepta los comandos de abajo
java -cp out Nodo 127.0.0.1 4          # terminal 2: nodo Java con 4 hilos
nodo-go.exe 127.0.0.1 4                # terminal 3: nodo Go
python nodo.py 127.0.0.1 4             # terminal 4: nodo Python con 4 procesos
java -cp out Cliente 127.0.0.1         # chat
```

Comandos en la consola del servidor:
- `estado` muestra los nodos conectados y su velocidad.
- `entrenar <imágenes> <épocas> [java,go,python] [maxNodos] [guardar]` entrena de forma distribuida desde pesos iniciales fijos, para que los escenarios sean comparables. Con `guardar`, publica el modelo nuevo para el chat.

Para entrenar se necesita `../data/train.bin`, que viene en `datos_train.zip` en la sección *Releases* del repositorio.

## Los 4 escenarios del enunciado

| Escenario | Comando | Resultado de prueba (1 PC) |
|---|---|---|
| Secuencial | `java -cp out Cnn entrenar 1 1024` | 185 img/s |
| Paralelo | `java -cp out Cnn entrenar 4 1024` | 600 img/s |
| Distribuido | nodos con 1 hilo y `entrenar 1024 1` en el servidor | Java + Go + Python: 238 img/s |
| Paralelo distribuido | nodos con H hilos y `entrenar 1024 1` | Java + Go + Python, 2 hilos: 305 img/s |

Cada corrida agrega una fila a `resultados.csv`, y la prueba de carga escribe en `carga.csv`. Las gráficas se generan con:

```bash
pip install matplotlib
python graficas.py        # crea velocidad.png, aceleracion.png, carga.png y desempeno.png
```

`graficas.py` es un agregado para el informe: no forma parte del sistema distribuido, por eso puede usar matplotlib.

Prueba de carga (cientos de transacciones con un delay aleatorio de 0 a 200 ms):

```bash
java -cp out Cliente carga <ip_servidor> <clientes> <peticiones_por_cliente>
java -cp out Cliente carga 127.0.0.1 200 20
```

En una sola PC el distribuido rinde menos que el paralelo, porque los 3 nodos comparten los mismos núcleos y además pagan el envío de pesos e imágenes por la red (costo de paralelización y agregación). En un clúster cada nodo tiene su propia CPU.

Una prueba de carga con 10, 50, 100, 200 y 400 clientes (20 consultas cada uno) dio hasta 921 respuestas/s y 0 errores.

## Capturas

En `capturas/` están las capturas de una sesión real en una PC: el servidor, los 3 nodos, el chat y la prueba de carga (`vista_conjunto.png` las junta), más las gráficas y los CSV con que se hicieron. Las terminales se dibujaron a partir de la salida real de cada programa.

## Ejecutar en el clúster (máquinas virtuales Ubuntu del lab 07)

Los nodos no necesitan ningún archivo de datos: el servidor les envía los pesos y las imágenes por la red.

| Máquina | Qué corre | Qué copiar |
|---|---|---|
| Servidor (ej. dodo02) | `java -cp out Servidor` (y si quieres, también un nodo y la prueba de carga) | `out/` y la carpeta `data/` al lado de `minimo/` |
| Nodo 1 | `java -cp out Nodo <ip_servidor> <hilos>` | `out/` |
| Nodo 2 | `./nodo-go <ip_servidor> <hilos>` | `nodo-go` (binario Linux) |
| Nodo 3 | `python3 nodo.py <ip_servidor> <hilos>` | `nodo.py` |
| PC con Windows | `java -cp out Cliente <ip_servidor>` (chat con ventana) | `out/` |

Para preparar los archivos en Windows:

```bash
javac --release 8 -encoding UTF-8 -d out *.java
GOOS=linux GOARCH=amd64 CGO_ENABLED=0 go build -o nodo-go nodo.go
```

Con `--release 8` las clases corren en el Java 8 de las máquinas virtuales. El binario `nodo-go` no necesita Go instalado. Ubuntu 22.04 ya trae Python 3.10. Si `ufw` está activo, abre los puertos 5000 y 6000 en el servidor. Si los datos están en otra carpeta, se indica con `-Ddata=/ruta/a/data/`.
