# Pruebas en vivo: qué pueden pedir y qué tener listo

El enunciado dice: *"Se consultará una pregunta en su clúster u otro"*. En la exposición hay que
poder levantar el sistema en pocos minutos, en nuestro clúster o en máquinas ajenas, y responder
pidiendo una prueba concreta. Esta guía junta las pruebas más probables con los comandos
exactos, lo que se debe ver en pantalla y lo que conviene explicar.

En los comandos, `S` es la IP del servidor y `H` es el número de hilos por nodo.

---

## 1. Antes del día (checklist)

- [ ] **Kit compilado:** `scripts/empaquetar.sh kit` → `dist/OpenAICubes_kit.zip` (72 MB). Trae
      las clases Java 8 en `out/`, `nodo-go` para Linux, `node.py` y `train.bin`. Llevarlo en un
      USB **y** en el Drive. En una máquina ajena basta descomprimir; no hace falta compilar ni
      tener Go:
      `unzip OpenAICubes_kit.zip && cd OpenAICubes && chmod +x go/nodo-go scripts/*.sh`.
- [ ] **Clúster del lab 07 probado de punta a punta:** servidor + 3 nodos (Java, Go, Python),
      chat, entrenamiento, carga y caída de un nodo. Anotar las IP de cada VM y un nombre de host
      distinto por VM (`sudo hostnamectl set-hostname nodo1`).
- [ ] **Requisitos de cada máquina:** Java 8 o superior (`java -version`) y Python 3.8 o superior
      (`python3 --version`). Nada más: el nodo Go viene compilado.
- [ ] **Firewall:** en Ubuntu, `sudo ufw status`; si está activo,
      `sudo ufw allow 5000/tcp && sudo ufw allow 6000/tcp` en la máquina del servidor. En
      VirtualBox, las VM en "Red interna" o "Adaptador puente" deben verse entre sí (`ping`).
- [ ] **Resultados ya medidos** en el clúster real, con sus gráficas (`results/*.png`) puestas en
      el informe y la presentación. En vivo se repite solo una parte, porque el resto ya está
      medido.
- [ ] **Fotos para el chat:** 2 o 3 fotos reales por clase (avión, auto, gato, barco), una foto
      "trampa" de otra cosa (por ejemplo un panda, como en el dibujo del enunciado) y una carpeta
      con unas 20 fotos para "Identificar carpeta". Ya hay una buena del gato en
      `Concurrente/PARCIAL/AiCube_Remotion_Para_Claude/foto_gato/gato_1264.jpg`.
- [ ] **Plan B sin red:** una laptop con todo local (`scripts/demo_local.sh 2`, o
      `scripts\demo_local.bat 2` en Windows).
- [ ] **Plan nube (opcional):** VM de Azure lista y apagada; se enciende 10 minutos antes. Ver
      [NUBE_AZURE.md](NUBE_AZURE.md) y probar antes que la red de la UNI deja salir por 5000/6000.
- [ ] Repasar la tabla de conceptos (sección 4): **todos** los integrantes deben saber dónde está
      cada cosa en el código.

## 2. Arranque rápido (5 minutos)

| Dónde | Comando |
|---|---|
| Máquina servidor | `scripts/servidor.sh` (consola: `estado`, `verbose`, `salir`) |
| VM 1 | `scripts/nodo_java.sh S H` |
| VM 2 | `scripts/nodo_go.sh S H` |
| VM 3 | `scripts/nodo_python.sh S H` |
| Cualquiera | `scripts/cliente.sh S` (chat) |
| Cualquiera | `java -cp out cubes.client.AdminClient --host S estado` |

Al terminar, `estado` debe listar los 3 nodos con su lenguaje, host, hilos y velocidad.

## 3. Pruebas probables

### P1. "Muestren que funciona en el clúster con los 3 lenguajes"
- Levantar todo como en la sección 2 y mostrar `estado`.
- Correr `AdminClient --host S crosscheck`: los 3 nodos calculan el mismo lote y se comparan con
  la referencia en Java. Los 3 deben decir **OK** (misma pérdida y mismo gradiente).
- **Qué explicar:** es la misma CNN implementada 3 veces sin librerías, y da el mismo resultado
  numérico. Por eso los nodos son intercambiables.

### P2. "Identifiquen esta imagen" (chat)
- En el chat: *Identificar imagen* (foto real), *imagen de prueba* (una al azar de `test.bin`),
  *Identificar carpeta* (muchas a la vez) o arrastrar y soltar.
- Se ve la respuesta del Bot con la clase y la confianza, y en el servidor qué nodo la resolvió.
- **Pregunta trampa:** una foto de un panda o de una persona. El modelo **siempre** responde una de
  las 4 clases (softmax sobre 4 salidas); no sabe decir "ninguna". Explicarlo con naturalidad.
- **Qué explicar:** la foto se reduce a 32×32 (`ImagePrep`), viaja con el protocolo propio
  (`IDENTIFY`), entra a la cola productor-consumidor (`InferenceService`, lotes de hasta 16) y la
  resuelve un nodo.

### P3. "Corran el Caso 1, el Caso 2 y el Caso 3"
```bash
java -cp out cubes.client.AdminClient --host S entrenar --images 1024 --langs java   --label caso1
java -cp out cubes.client.AdminClient --host S entrenar --images 1024 --langs python --label caso2
java -cp out cubes.client.AdminClient --host S entrenar --images 1024 --langs all    --label caso3
```
- **Qué explicar:** con `all`, el reparto es **proporcional a la velocidad** medida de cada nodo;
  Python recibe menos imágenes porque es más lento. La pérdida es la misma en los 3 casos porque
  los gradientes se suman igual.

### P4. "Muestren los 4 escenarios" (secuencial, paralelo, distribuido, paralelo distribuido)
```bash
REPS=1 THREADS="1 2 4" scripts/bench_local.sh 512        # secuencial y paralelo (1 máquina)
# nodos con 1 hilo en cada VM:
REPS=1 scripts/bench_distribuido.sh S distribuido "1 2 3" 512
# nodos con H hilos en cada VM:
REPS=1 scripts/bench_distribuido.sh S paralelo_distribuido "1 2 3" 512
scripts/graficas.sh
```
- En vivo conviene `REPS=1` y 512 imágenes para que tarde poco. Para el informe, `REPS=3` y 1024
  o más imágenes.
- **Qué explicar:** speedup, eficiencia y costo de comunicación (sección 5).

### P5. "Apaguen un nodo en pleno trabajo" (tolerancia a fallos)
- **Durante el entrenamiento:** lanzar uno largo
  (`entrenar --images 8192 --epochs 2 --langs all`) y, a la mitad, cerrar un nodo con `Ctrl+C`
  o `kill -9`. El servidor imprime *"nodo X falló; se reasignan N imágenes al nodo Y"* y el
  entrenamiento termina igual.
- **Durante el chat o la carga:** apagar un nodo; ese lote se reasigna. Si se desconecta el cable
  o se congela la VM, el **heartbeat** lo marca caído a los 10 s (`NodeRegistry.TIMEOUT_MS`).
- **Si se caen todos:** el servidor responde el chat él solo; `estado` lo cuenta como "en
  servidor (sin nodos)".
- **Qué explicar:** detección por excepción del socket (inmediata) y por heartbeat (cuelgues).
  La reasignación toma al nodo vivo más rápido.

### P6. "Agreguen un nodo nuevo sin reiniciar"
- Con el sistema corriendo, conectar otro nodo, por ejemplo uno Java en otra máquina. Aparece en
  `estado` y la siguiente tarea ya lo usa.
- **Qué explicar:** el registro de nodos es dinámico (`NodeRegistry`); el servidor acepta nodos en
  el puerto 6000 en cualquier momento.

### P7. "Saturen el sistema con cientos de clientes"
```bash
scripts/carga.sh S "en vivo" 10,100,400,800 20      # clientes por paso, peticiones por cliente
```
- Cada cliente espera un delay aleatorio de 0 a 200 ms entre peticiones, como pide el enunciado.
- Se ve, por paso, resp/s, latencia p50/p95/p99, errores y cuántas resolvió cada lenguaje. En el
  servidor, `estado` muestra la cola y las rechazadas.
- **Qué explicar:** al principio las resp/s suben con los clientes; luego se aplanan (**límite de
  throughput**) mientras la latencia sigue creciendo, porque las peticiones esperan en la cola.
  Ese punto de quiebre es el límite del sistema. Si la cola se llena, se rechaza en vez de caerse.

### P8. "Muestren el protocolo" / "¿qué viaja por la red?"
- Tener abierto [PROTOCOLO.md](PROTOCOLO.md): formato de la trama (cabecera + tipo + id +
  longitud + payload), tipos de mensaje y secuencias.
- En Linux, para ver los bytes reales: `sudo tcpdump -i any -X port 6000 -c 20`.
- **Qué explicar:** se usan solo sockets TCP del SDK de cada lenguaje, con un framing propio. Sin
  websocket, socket.io ni middleware.

### P9. "Cambien algo en vivo"
Lo más típico es cambiar los hilos, el lote, el puerto o agregar un mensaje. Saber dónde está:
- **Hilos:** argumento de `nodo_*.sh` (`--threads`).
- **Lote y tasa de aprendizaje:** `entrenar --batch --lr`.
- **Puertos:** `--client-port` / `--node-port` en el servidor y `--port` en nodos y clientes.
- **Micro chunks:** `ChunkExecutor.forRange` (Java), `go/executor.go` y el pool de procesos de
  `node.py`.
- **Recompilar:** `scripts/compilar.sh` (unos 5 s).

### P10. "Corran el servidor en la nube" (opcional)
- Servidor en Azure, nodos en el lab y chat desde una laptop. Ver [NUBE_AZURE.md](NUBE_AZURE.md).
- **Qué explicar:** por internet aparece la latencia de red. Se compara `carga_latencia.png` LAN
  vs nube.

## 4. Dónde está cada concepto (para responder rápido)

| Concepto (semana) | Dónde verlo |
|---|---|
| Micro chunk + barrera por capa (Sem02/05) | `cnn/ChunkExecutor.forRange` (espera todos los `Future`), `go/executor.go` (`sync.WaitGroup`), `node.py` (pool de procesos) |
| `synchronized` / monitor (Sem05) | `ClientHandler.send`, `ServerConnection.*`, `DistributedTrainer.run`, `ModelStore.publish`, `InferenceService` (`servingLock`) |
| `volatile` (Sem05) | `ModelStore.current` (modelo publicado que leen todos los hilos), `JavaNode.out`, `Log.verbose` |
| Atómicos (Sem05) | `Backend` (contadores), `DistributedTrainer.versions`, `InferenceService.served/rejected`, `LoadGenerator` |
| Colecciones concurrentes | `Backend.sessions` (`ConcurrentHashMap`), historial con `Collections.synchronizedList` |
| Productor-consumidor / `BlockingQueue` (Sem05) | `InferenceService.queue` (`LinkedBlockingDeque`, capacidad limitada → rechazo en vez de caída) |
| `Future` / `CompletableFuture` (Sem05) | `NodeHandle.request` (respuesta asíncrona por id), `DistributedTrainer.dispatch`, `ClientHandler` (lotes del chat) |
| Barrera de arranque | `LoadGenerator` (`CountDownLatch` ready/go/done: todos los clientes arrancan a la vez) |
| Exclusión mutua en Go y Python | `go/node.go` (`wmu sync.Mutex` en el socket), `node.py` (`write_lock`, `pending_lock`) |
| Reparto sin condición de carrera | Cada nodo calcula su gradiente aparte y el servidor los suma en un solo hilo (`DistributedTrainer`) |
| Starvation deliberada | Reparto proporcional: con lotes pequeños, Python puede recibir 0 imágenes (es lento); no es un error |
| Heartbeat / fallos | `NodeRegistry` (TIMEOUT 10 s), reasignación en `DistributedTrainer` e `InferenceService` |
| Latencia, throughput, saturación (Sem06) | `LoadGenerator` + `carga_latencia.png` / `carga_throughput.png` |
| Speedup, eficiencia, costo de paralelizar (Sem06) | `escalabilidad_*.png` y `costo_comunicacion.png` (columnas `computo_s` / `comunicacion_s`) |

## 5. Cómo leer los resultados (preguntas de desempeño)

- **Speedup** = img/s / img/s secuencial del mismo lenguaje. **Eficiencia** = speedup / núcleos.
  La eficiencia baja al subir los nodos: aumenta el costo fijo de mandar pesos (~1 MB por nodo y
  por lote) y de sumar gradientes, mientras el cómputo por nodo se achica.
- **Costo de comunicación:** el servidor ahora separa, lote a lote, el tiempo del nodo más lento
  (cómputo) del resto (enviar, esperar y agregar). En local, con 3 nodos, es ~15 %; en una red
  real o en la nube sube. Por eso un lote más grande (`BATCH=256`) mejora la eficiencia
  distribuida.
- **Clúster mixto (Caso 3):** es más lento que solo Go o solo Java, porque Python va unas 10 veces
  más lento. El reparto proporcional lo compensa en parte, pero cada lote espera al más lento.
- **Ojo con laptops con núcleos P y E** (como un Intel de 12.ª/13.ª generación). Con 1 hilo, el
  sistema operativo puede mandar el proceso a un núcleo lento (E). Medido en un i5-13420H: Java
  con 1 hilo tarda 7,4 s en un núcleo P y 15 s en uno E. Eso da un speedup falso de casi 3× con 2
  hilos. Las mediciones para el informe se hacen en el clúster (VM con núcleos iguales) o con el
  plan de energía en "Alto rendimiento".
- **Repeticiones:** los bench repiten cada caso (`REPS=3`) y las gráficas usan la **mediana**, que
  resiste mejor a una corrida rara (JIT, otra carga en la máquina).

## 6. Problemas comunes

| Síntoma | Causa y solución |
|---|---|
| `Address already in use` | Quedó un servidor viejo: `pkill -f CentralServer` (Linux), o cerrar la ventana |
| El nodo no conecta | IP equivocada, firewall (`ufw`) o VM en otra red. Probar `nc -vz S 6000` |
| `python3: command not found` | `sudo apt install python3`. En Windows usar `python` |
| `Permission denied` en `nodo-go` | `chmod +x go/nodo-go scripts/*.sh` (el zip no guarda permisos) |
| `UnsupportedClassVersionError` | Se compiló sin `--release 8`; usar las clases del kit o `scripts/compilar.sh` con JDK 9+ |
| Dos VM cuentan como una máquina | Mismo hostname y misma IP de salida (clones): `sudo hostnamectl set-hostname nodoN` |
| Python recibe 0 imágenes | Es el reparto proporcional con lotes chicos; con `--batch 256` le toca algo |
| El entrenamiento se nota lento por internet | Es lo esperado: entrenar en la LAN y dejar la nube para chat y carga |

## 7. Guion sugerido (10 minutos)

1. Arquitectura (1 min): diagrama del README, con 1 servidor y 3 nodos en 3 lenguajes.
2. `estado` + `crosscheck` (1 min).
3. Chat: foto real, foto trampa y carpeta (2 min).
4. Entrenamiento mixto corto y caída de un nodo con reasignación (2 min).
5. Prueba de carga hasta 400–800 clientes, explicando la saturación (2 min).
6. Gráficas del clúster real: speedup, eficiencia, costo de comunicación y conclusiones (2 min).
