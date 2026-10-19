# Especificación de la CNN (contrato común Java / Python / Go)

Todas las implementaciones deben calcular lo mismo (salvo errores de redondeo de punto flotante).

## Datos de entrada

- Formato binario CIFAR-10: cada registro = 1 byte de etiqueta + 3072 bytes de píxeles.
  Los píxeles son 3 planos de 32×32 (primero 1024 bytes de R, luego G, luego B), fila por fila.
- Las clases están en `classes.txt` (una por línea) junto al `.bin`. Por defecto: `avion, automovil, gato, barco`.
- Normalización: `x = byte / 127.5 - 1` (rango [-1, 1]).
- Disposición de tensores por imagen: `[canal][fila][columna]` en un arreglo plano.

## Arquitectura

| Capa | Entrada | Salida | Detalle |
|---|---|---|---|
| conv1 + ReLU | 3×32×32 | 16×32×32 | kernel 3×3, stride 1, padding 1 (ceros) |
| pool1 | 16×32×32 | 16×16×16 | max 2×2, stride 2 |
| conv2 + ReLU | 16×16×16 | 32×16×16 | kernel 3×3, stride 1, padding 1 |
| pool2 | 32×16×16 | 32×8×8 | max 2×2, stride 2 |
| flatten | 32×8×8 | 2048 | mismo orden [canal][fila][columna] |
| fc1 + ReLU | 2048 | 128 | |
| fc2 | 128 | K | K = número de clases |
| softmax | K | K | pérdida: entropía cruzada |

Convolución: `out[f][y][x] = b[f] + Σ_c Σ_ky Σ_kx W[f][c][ky][kx] · in[c][y+ky-1][x+kx-1]`
(fuera de la imagen vale 0).

Max pooling: empates → gana la primera posición en el orden (0,0), (0,1), (1,0), (1,1)
(comparación estricta `>`). El gradiente va solo a la posición ganadora.

Derivada de ReLU: 1 si la salida activada es > 0, si no 0.

## Vector de pesos (orden fijo)

Un solo arreglo de float32 con este orden:

| Bloque | Forma | Cantidad |
|---|---|---|
| W1 | [16][3][3][3] | 432 |
| b1 | [16] | 16 |
| W2 | [32][16][3][3] | 4608 |
| b2 | [32] | 32 |
| W3 | [128][2048] | 262144 |
| b3 | [128] | 128 |
| W4 | [K][128] | 128·K |
| b4 | [K] | K |

Total = 267360 + 129·K (con K = 4: 267876). K se deduce del tamaño: `K = (total - 267360) / 129`.

## Gradientes

- Un nodo devuelve la **suma** de los gradientes de sus n imágenes (no el promedio), en el mismo orden que los pesos.
- `dLogits = softmax - onehot(etiqueta)` por imagen.
- Pérdida por imagen: `-ln(max(p[etiqueta], 1e-12))`. El nodo devuelve la suma.
- Aciertos: `argmax(p) == etiqueta` (empate → índice menor).

## Optimizador (solo lo usa quien actualiza pesos: el servidor, o el modo bench local)

SGD con momentum: `v = 0.9·v + g/n ; w = w - lr·v`, con `lr = 0.01`, lote 64, `v` inicial en cero.

## Archivo de pesos (`.bin`, big-endian)

```
int32 magic = 0x4F435731 ("OCW1")
int32 count
float32[count]
```

`data/init_weights.bin` (semilla 42) es el punto de partida común de todos los benchmarks.

## Archivo de referencia (para verificar Python y Go contra Java)

Generado con `java -cp out cubes.tools.RefDump data/train.bin data/init_weights.bin 8 data/ref.bin`.
Calcula con los pesos iniciales las 8 primeras imágenes de `train.bin`. Big-endian:

```
int32   magic = 0x4F435231 ("OCR1")
int32   n
float64 lossSum
int32   correct
int32   count            (tamaño del vector de gradientes)
float32 grad[count]      (suma de gradientes)
int32   K
float32 probs[n][K]      (softmax de cada imagen)
```

Criterio de igualdad: `|a - b| <= 1e-3 + 1e-3·|b|` elemento por elemento (float32 vs float64 y
distinto orden de suma dan pequeñas diferencias).

## Micro chunks y paralelismo dentro de un nodo

- **Conv chunk**: rango de filtros (forward y gradiente de pesos) o de canales de entrada (gradiente de la entrada).
- **MLP chunk**: rango de neuronas (forward y gradiente de pesos) o de entradas (gradiente de la entrada).
- **Pool chunk**: rango de canales.
- Java y Go reparten esos rangos entre H hilos/goroutines con una barrera por capa.
- Python (por el GIL) usa H procesos con `multiprocessing` y reparte las imágenes del lote; cada
  proceso ejecuta todos los chunks para sus imágenes y se suman los gradientes.

## Modo benchmark local (filas "secuencial" y "paralelo" de la matriz)

Cada nodo tiene un modo que entrena sin red y escribe una fila CSV con estas columnas:

```
escenario,lenguaje,maquinas,hilos,imagenes,epocas,lote,segundos,img_por_s,perdida,acierto_train,acierto_test
```

`acierto_test` puede ser `NaN` si no se evalúa. La salida estándar termina con `TOTAL <segundos> s`.
