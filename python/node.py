#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Open AI Cubes - nodo de cómputo en Python (SOLO biblioteca estándar, Python 3.8+).

Modos de uso (ejecutar desde la raíz del repositorio):

  Nodo de red (modo por defecto; --threads por defecto = núcleos de la máquina):
    python3 python/node.py --server 127.0.0.1 --port 6000 --threads 4 [--verbose] [--once]

  Entrenamiento local sin red (filas "secuencial" / "paralelo" de la matriz):
    python3 python/node.py --bench --train data/train.bin --init data/init_weights.bin \
        --images 256 --epochs 1 --batch 64 --threads 4 --lr 0.01 \
        [--test data/test.bin --test-images 500] [--csv results/x.csv --label paralelo] [--save m.bin]

  Verificación contra la referencia de Java (docs/CNN_SPEC.md, "Archivo de referencia"):
    python3 python/node.py --verify data/ref.bin --init data/init_weights.bin --train data/train.bin [--threads 4]

La red y el vector de pesos están definidos en docs/CNN_SPEC.md y el protocolo en
docs/PROTOCOLO.md. La estructura sigue a la versión Java (java/src/cubes/cnn): Params,
ConvMicroChunk, PoolMicroChunk, MlpMicroChunk, Cnn, Sgd y LocalTrain, para poder
comparar los tres lenguajes lado a lado.

Decisiones propias de Python (por qué el código no es una traducción literal de Java):

1. Velocidad sin numpy. Un bucle de Python cuesta del orden de 50-100 ns por operación,
   así que los bucles anidados de Java serían cientos de veces más lentos. Todo el
   trabajo pesado se expresa como productos punto hechos en C (ver dot() y dots()):
     - im2col: cada parche 3x3xC de la convolución se arma con operator.itemgetter y
       una tabla de índices precalculada (en C), y cada salida es dot(filtro, parche).
     - dots(): muchos productos punto con math.dist y la identidad
       w.x = (|w|^2 + |x|^2 - |w-x|^2) / 2 (más rápido que math.sumprod).
     - Transposiciones con zip(*filas) o cortes con paso de un array('d'), en C.
   El sesgo se trata como un peso más: cada parche/vector de entrada lleva un 1.0 al
   final y cada fila de pesos lleva su sesgo al final.

2. ReLU fusionada con el max pooling. max(relu(a), relu(b), ...) == relu(max(a, b, ...)),
   así que se hace el pooling sobre la salida lineal de la convolución y la ReLU solo
   sobre el resultado (4 veces menos elementos). El gradiente es idéntico al de Java:
   va a la primera posición ganadora (orden (0,0), (0,1), (1,0), (1,1)) y solo si la
   salida es > 0. Las salidas de la convolución se calculan en "orden de bloques"
   (primero todas las posiciones (0,0) de cada ventana 2x2, luego las (0,1), ...) para
   que el pooling sea un max elemento a elemento de 4 listas contiguas.

3. Gradientes "dispersos" en las convoluciones: tras el pooling solo una de cada 4
   posiciones (la ganadora, y solo si es > 0) tiene gradiente distinto de cero. El
   gradiente de pesos dW[f] = suma(dZ * parche) usa solo los parches ganadores y el
   gradiente de la entrada de conv2 se salta los ceros de cada parche (compress).

4. Paralelismo con procesos. El GIL impide que varios hilos de Python calculen a la vez,
   así que con --threads H > 1 se crean H procesos (multiprocessing) que viven todo el
   tiempo del nodo. Cada tarea se reparte por IMÁGENES: cada proceso ejecuta todos los
   micro chunks para sus imágenes y el proceso principal suma pérdidas, aciertos y
   gradientes (ver docs/CNN_SPEC.md, "Micro chunks y paralelismo dentro de un nodo").

   Cómo llegan los pesos a los procesos (medido en esta máquina):
     a) Por tarea, junto con las imágenes: 1 MB extra por proceso y por tarea, y cada
        proceso rearma sus tuplas de pesos en cada tarea aunque los pesos no cambien.
     b) multiprocessing.shared_memory / Array: evita copiar 1 MB por la tubería (~1 ms),
        pero cada proceso igual tiene que convertir los floats a objetos de Python y
        reorganizarlos en tuplas (PreparedParams, ~45 ms: eso es lo caro), y además hay
        que gestionar el ciclo de vida del segmento compartido.
     c) (ELEGIDA) Una tubería (Pipe) por proceso: cuando llega SET_WEIGHTS se envía una
        sola vez a cada proceso como float32 crudo (1 MB, ~1 ms + ~5 ms de conversión) y
        cada proceso guarda los pesos por slot; las tuplas se preparan una vez por versión,
        la primera vez que una tarea las usa. Las tareas solo llevan etiquetas y píxeles.
        Cuesta lo mismo que (b) y es bastante más simple.

5. El recolector de ciclos (gc) se desactiva durante el cálculo (ver NoGc).
"""

import gc
import math
import multiprocessing
import os
import queue
import signal
import socket
import struct
import sys
import threading
import time
from array import array
from decimal import ROUND_HALF_UP, Decimal
from itertools import chain, compress, repeat
from math import dist
from operator import add, itemgetter, mul

# ---- Productos punto en C (el núcleo de todo el cálculo) ----
#
# dot(a, b): un producto punto suelto (se usa donde las normas no se reutilizarían).
# math.sumprod existe desde Python 3.12; antes se usa sum(map(mul, a, b)), ~2.5x más lento.
#
# dots(w, w_sq, xs, xs_sq): w . x para muchos x. Usa la identidad
#     |w - x|^2 = |w|^2 + |x|^2 - 2 w.x   =>   w.x = (|w|^2 + |x|^2 - dist(w, x)^2) / 2
# con math.dist (en C desde Python 3.8). Medido en esta máquina: dist cuesta ~9 ns por
# elemento, sumprod ~14 ns (calcula con precisión extendida) y sum(map(mul)) ~33 ns.
# Conviene cuando las normas |w|^2 y |x|^2 se reutilizan en muchos productos (cada
# parche se multiplica por todos los filtros, cada filtro por todos los parches). El
# error absoluto es ~1e-16 * (|w|^2 + |x|^2): despreciable frente a float32.
try:
    from math import sumprod as dot  # Python 3.12+
except ImportError:  # Python 3.8 - 3.11
    def dot(a, b):
        return sum(map(mul, a, b))


def sq_norms(vectors, k):
    """|v|^2 de cada vector de largo k (distancia al origen, en C)."""
    d = list(map(dist, repeat((0.0,) * k), vectors))
    return list(map(mul, d, d))


def dots(w, w_sq, xs, xs_sq):
    """[w . x for x in xs] con la identidad de la distancia (ver arriba)."""
    return [(w_sq + q - d * d) * 0.5 for q, d in zip(xs_sq, map(dist, repeat(w), xs))]


# ============================================================================
# Params: forma de la red y orden del vector de pesos (igual que Params.java)
# ============================================================================

IN_C, IN_H, IN_W = 3, 32, 32
IN_SIZE = IN_C * IN_H * IN_W                 # 3072 bytes por imagen
KS = 3
C1, H1, W1S = 16, 32, 32                     # salida conv1 (padding 1)
P1H, P1W = 16, 16                            # salida pool1
C2, H2, W2S = 32, 16, 16                     # salida conv2
P2H, P2W = 8, 8                              # salida pool2
FLAT = C2 * P2H * P2W                        # 2048
FC1 = 128

N_W1 = C1 * IN_C * KS * KS
N_W2 = C2 * C1 * KS * KS
N_W3 = FC1 * FLAT
FIXED = N_W1 + C1 + N_W2 + C2 + N_W3 + FC1  # parámetros que no dependen de K

O_W1 = 0
O_B1 = O_W1 + N_W1
O_W2 = O_B1 + C1
O_B2 = O_W2 + N_W2
O_W3 = O_B2 + C2
O_B3 = O_W3 + N_W3
O_W4 = O_B3 + FC1

K1 = IN_C * KS * KS     # 27 pesos por filtro de conv1 (el parche lleva además el 1.0 del sesgo)
K2 = C1 * KS * KS       # 144 pesos por filtro de conv2
K2T = C2 * KS * KS      # 288: parche del gradiente de la entrada de conv2 (convolución transpuesta)

WEIGHTS_MAGIC = 0x4F435731   # "OCW1"
REF_MAGIC = 0x4F435231       # "OCR1"
LITTLE = sys.byteorder == "little"

# Imágenes por sub-lote dentro de un proceso (limita la memoria de las activaciones).
SUB_BATCH = 64


def f32_from_be(buf):
    """float32 big-endian -> lista de floats de Python."""
    a = array("f")
    a.frombytes(buf)
    if LITTLE:
        a.byteswap()
    return a.tolist()


def f32_to_be(values):
    """Lista de floats -> float32 big-endian (redondea a float32)."""
    a = array("f", values)
    if LITTLE:
        a.byteswap()
    return a.tobytes()


def jfmt(x, decimals):
    """Como String.format(Locale.ROOT, "%.Nf") de Java: redondeo "half up" sobre el
    decimal más corto del double (Python redondea "half even": 0.40625 -> 0.4062, Java
    -> 0.4063). Así las salidas y el CSV coinciden con los de Java."""
    if math.isnan(x):
        return "NaN"
    if math.isinf(x):
        return "Infinity" if x > 0 else "-Infinity"
    return str(Decimal(repr(x)).quantize(Decimal(1).scaleb(-decimals), rounding=ROUND_HALF_UP))


def round_f32(values):
    """Redondea cada valor a float32 (como guarda Java los pesos)."""
    return array("f", values).tolist()


class Params:
    """Pesos de la CNN en una sola lista con desplazamientos fijos (ver Params.java):

      W1[16][3][3][3]  b1[16]       conv1
      W2[32][16][3][3] b2[32]       conv2
      W3[128][2048]    b3[128]      fc1 (MLP oculta)
      W4[K][128]       b4[K]        fc2 (salida, K clases)
    """

    def __init__(self, data):
        self.classes = Params.classes_from_size(len(data))
        self.o_b4 = O_W4 + FC1 * self.classes
        self.data = data
        self._prepared = None

    @staticmethod
    def size(classes):
        return FIXED + FC1 * classes + classes

    @staticmethod
    def classes_from_size(count):
        """Deduce el número de clases a partir del tamaño del vector de pesos."""
        k = (count - FIXED) // (FC1 + 1)
        if k <= 0 or Params.size(k) != count:
            raise ValueError("Tamano de pesos invalido: %d" % count)
        return k

    @staticmethod
    def load(path):
        with open(path, "rb") as f:
            raw = f.read()
        magic, count = struct.unpack_from(">ii", raw, 0)
        if magic != WEIGHTS_MAGIC:
            raise IOError("No es un archivo de pesos OCW1: %s" % path)
        if len(raw) < 8 + 4 * count:
            raise IOError("Archivo de pesos truncado: %s" % path)
        return Params(f32_from_be(raw[8:8 + 4 * count]))

    def save(self, path):
        parent = os.path.dirname(os.path.abspath(path))
        os.makedirs(parent, exist_ok=True)
        with open(path, "wb") as f:
            f.write(struct.pack(">ii", WEIGHTS_MAGIC, len(self.data)))
            f.write(f32_to_be(self.data))

    def prepared(self):
        """Pesos reorganizados en tuplas para dot(); se construyen una vez por versión."""
        if self._prepared is None:
            self._prepared = PreparedParams(self)
        return self._prepared


class PreparedParams:
    """Vistas de los pesos listas para los productos punto (dot / dots).

    w1, w2: una tupla por filtro: [c][ky][kx] + sesgo al final (igual que los parches).
    w3, w4: una tupla por neurona: fila de W + sesgo al final.
    *_sq:   |fila|^2 de cada tupla, para dots().
    Solo para el backward (se construyen con with_backward(), así un slot que solo se
    usa para inferencia no paga la transposición):
    w2t:    una tupla por canal de entrada c de conv2 con W2[f][c][ky][kx] en orden
            (f, ky, kx): gradiente respecto a la entrada (convolución transpuesta).
    w3t, w4t: matrices transpuestas (una tupla por entrada): gradiente de la entrada.
    """

    def __init__(self, p):
        d = p.data
        self.params = p
        self.classes = p.classes
        self.w1 = [tuple(d[O_W1 + f * K1:O_W1 + (f + 1) * K1]) + (d[O_B1 + f],) for f in range(C1)]
        self.w2 = [tuple(d[O_W2 + f * K2:O_W2 + (f + 1) * K2]) + (d[O_B2 + f],) for f in range(C2)]
        self.w3 = [tuple(d[O_W3 + j * FLAT:O_W3 + (j + 1) * FLAT]) + (d[O_B3 + j],) for j in range(FC1)]
        self.w4 = [tuple(d[O_W4 + c * FC1:O_W4 + (c + 1) * FC1]) + (d[p.o_b4 + c],) for c in range(p.classes)]
        self.w1_sq = sq_norms(self.w1, K1 + 1)
        self.w2_sq = sq_norms(self.w2, K2 + 1)
        self.w3_sq = sq_norms(self.w3, FLAT + 1)
        self.w4_sq = sq_norms(self.w4, FC1 + 1)
        self.w2t = self.w3t = self.w4t = None

    def with_backward(self):
        if self.w3t is None:
            d = self.params.data
            w2 = d[O_W2:O_W2 + N_W2]
            self.w2t = [g(w2) for g in tables().w2t]
            self.w3t = transpose([row[:FLAT] for row in self.w3])
            self.w4t = transpose([row[:FC1] for row in self.w4])
            self.w3t_sq = sq_norms(self.w3t, FC1)
            self.w4t_sq = sq_norms(self.w4t, self.classes)
        return self


# ============================================================================
# Tablas de índices para im2col (se construyen una vez por proceso)
# ============================================================================

def _block_positions(h, w):
    """Posiciones (y, x) de una salida h x w en "orden de bloques": primero la esquina
    (0,0) de cada ventana 2x2 del pooling, luego (0,1), (1,0) y (1,1); dentro de cada
    bloque, fila por fila de ventanas. Así el pooling compara 4 listas contiguas."""
    pos = []
    for dy, dx in ((0, 0), (0, 1), (1, 0), (1, 1)):
        for py in range(h // 2):
            for px in range(w // 2):
                pos.append((2 * py + dy, 2 * px + dx))
    return pos


def _im2col_indices(in_c, h, w):
    """Índices para armar los parches de una convolución 3x3, padding 1.

    La entrada es la imagen [c][y][x] con dos valores extra al final: 0.0 (índice
    in_c*h*w, para las posiciones fuera de la imagen) y 1.0 (índice in_c*h*w + 1, para
    el sesgo). Cada parche tiene in_c*9 + 1 valores en orden (c, ky, kx, sesgo), el
    mismo orden que los pesos de un filtro. Los parches van en orden de bloques."""
    zero = in_c * h * w
    one = zero + 1
    idx = []
    for y, x in _block_positions(h, w):
        for c in range(in_c):
            for ky in range(KS):
                iy = y + ky - 1
                for kx in range(KS):
                    ix = x + kx - 1
                    if 0 <= iy < h and 0 <= ix < w:
                        idx.append(c * h * w + iy * w + ix)
                    else:
                        idx.append(zero)
        idx.append(one)
    return idx


def _im2col_t_indices(out_c, h, w):
    """Índices para el gradiente respecto a la entrada de una convolución 3x3 (padding 1):

        dIn[c][y][x] = sum_{f,ky,kx} W[f][c][ky][kx] * dZ[f][y-ky+1][x-kx+1]

    La fuente es dZ de una imagen en orden de bloques ([f][bloque][py][px]) con un 0.0
    extra al final (índice out_c*h*w). Un parche por posición (y, x) de la entrada, en
    orden natural, con out_c*9 valores en orden (f, ky, kx)."""
    zero = out_c * h * w
    hw4 = (h // 2) * (w // 2)
    idx = []
    for y in range(h):
        for x in range(w):
            for f in range(out_c):
                for ky in range(KS):
                    iy = y - ky + 1
                    for kx in range(KS):
                        ix = x - kx + 1
                        if 0 <= iy < h and 0 <= ix < w:
                            block = (iy & 1) * 2 + (ix & 1)
                            idx.append(f * h * w + block * hw4 + (iy >> 1) * (w // 2) + (ix >> 1))
                        else:
                            idx.append(zero)
    return idx


class Tables:
    def __init__(self):
        self.im2col1 = itemgetter(*_im2col_indices(IN_C, H1, W1S))   # 1024 parches x 28
        self.im2col2 = itemgetter(*_im2col_indices(C1, H2, W2S))     # 256 parches x 145
        self.im2col2t = itemgetter(*_im2col_t_indices(C2, H2, W2S))  # 256 parches x 288
        # W2[f][c][ky][kx] reordenado por canal de entrada c, en orden (f, ky, kx)
        self.w2t = [itemgetter(*[((f * C1 + c) * KS + ky) * KS + kx
                                 for f in range(C2) for ky in range(KS) for kx in range(KS)])
                    for c in range(C1)]
        # Normalización x = byte / 127.5 - 1, redondeada a float32 como en Java
        self.norm = round_f32([v - 1.0 for v in round_f32([b / 127.5 for b in range(256)])])


_TABLES = None


def tables():
    global _TABLES
    if _TABLES is None:
        _TABLES = Tables()
    return _TABLES


def transpose(rows):
    """Transpone una matriz (lista de filas del mismo largo) a una lista de tuplas columna.

    Se pasa por un array('d') plano y se corta con paso (en C) para que los floats de
    cada columna sean objetos nuevos y contiguos en memoria. Con zip(*rows) las columnas
    apuntan a floats dispersos y los productos punto posteriores son ~2x más lentos por
    los fallos de caché."""
    m = len(rows[0])
    flat = array("d", list(chain.from_iterable(rows)))
    return [tuple(flat[i::m]) for i in range(m)]


def im2col(gather, x, k):
    """Aplica la tabla de índices (en C) y agrupa de k en k: lista de parches (tuplas)."""
    return list(zip(*[iter(gather(x))] * k))


# ============================================================================
# Micro chunks (mismos nombres y rangos que ConvMicroChunk, PoolMicroChunk y
# MlpMicroChunk de Java). Cada método trabaja sobre un rango de filtros, canales o
# neuronas para todas las imágenes del lote. En Python todos los rangos de una capa
# los ejecuta el mismo proceso (el paralelismo es por imágenes, ver arriba), pero
# los chunks se mantienen para que el concepto sea el mismo en los tres lenguajes.
# ============================================================================

class ConvMicroChunk:
    """Convolución 3x3, stride 1, padding 1. La ReLU se aplica después del pooling."""

    @staticmethod
    def forward(patches, patches_sq, wrows, wrows_sq, out, f0, f1):
        """Salida lineal (sin ReLU) de los filtros [f0, f1).
        patches[n]: parches de la imagen n (orden de bloques), patches_sq[n] sus |parche|^2.
        out[n][f]: lista de salidas del filtro f."""
        for n in range(len(patches)):
            pts = patches[n]
            pts_sq = patches_sq[n]
            o = out[n]
            for f in range(f0, f1):
                o[f] = dots(wrows[f], wrows_sq[f], pts, pts_sq)

    @staticmethod
    def backward_weights(patches, win_idx, win_val, grad, o_w, o_b, k, f0, f1):
        """Gradiente de pesos y sesgo de los filtros [f0, f1), SUMA sobre el lote.

        Solo las posiciones ganadoras del pooling tienen dZ != 0, así que
        dW[f] = sum_{ganadoras} dZ * parche. win_idx[n][f] / win_val[n][f] vienen de
        PoolMicroChunk.backward. Se procesa imagen por imagen (los parches de una imagen
        caben en la caché). Escribe (no acumula) en grad, que viene en cero."""
        acc = [None] * (f1 - f0)
        for n in range(len(patches)):
            get = patches[n].__getitem__
            idx_n = win_idx[n]
            val_n = win_val[n]
            for f in range(f0, f1):
                idx = idx_n[f]
                if not idx:
                    continue
                g = list(map(dot, repeat(val_n[f], k + 1), zip(*map(get, idx))))   # k pesos + sesgo
                a = acc[f - f0]
                acc[f - f0] = g if a is None else list(map(add, a, g))
        for f in range(f0, f1):
            g = acc[f - f0]
            if g is not None:
                grad[o_w + f * k:o_w + (f + 1) * k] = g[:k]
                grad[o_b + f] = g[k]

    @staticmethod
    def backward_input(dz, gather_t, wt, out, k_t, hw, c0, c1):
        """Gradiente respecto a la entrada para los canales [c0, c1) (sobrescribe out).
        dz[n]: gradiente de la salida en orden de bloques, con un 0.0 extra al final.

        Tras el max pooling ~85% de dZ son ceros, así que para cada posición se toman
        solo los valores distintos de cero del parche (compress, en C) y los pesos
        correspondientes de cada canal (itemgetter, en C): ~2x más rápido que denso."""
        sel = range(k_t)
        wts = wt[c0:c1]
        nc = c1 - c0
        zeros = [0.0] * nc
        for n in range(len(dz)):
            res = []      # por posición: gradiente de los canales [c0, c1)
            for pt in im2col(gather_t, dz[n], k_t):
                nz = list(compress(sel, pt))
                if len(nz) > 1:
                    vals = list(compress(pt, pt))
                    res.append(list(map(dot, repeat(vals, nc), map(itemgetter(*nz), wts))))
                elif nz:
                    i = nz[0]
                    res.append([pt[i] * w[i] for w in wts])
                else:
                    res.append(zeros)
            o = out[n]
            for c, col in zip(range(c0, c1), zip(*res)):
                o[c * hw:(c + 1) * hw] = col


class PoolMicroChunk:
    """Max pooling 2x2, stride 2, por rango de canales [c0, c1), fusionado con la ReLU.
    Empates: gana la primera posición en el orden (0,0), (0,1), (1,0), (1,1)."""

    @staticmethod
    def forward(z, win, out, c0, c1):
        """z[n][c]: salida lineal de la conv. en orden de bloques (4 * win valores).
        Escribe relu(max) en out[n][c*win:(c+1)*win] (orden natural [c][py][px])."""
        for n in range(len(z)):
            zn = z[n]
            o = out[n]
            for c in range(c0, c1):
                zc = zn[c]
                # max() devuelve el PRIMER máximo: mismo desempate que Java (">" estricto)
                o[c * win:(c + 1) * win] = map(max, zc[:win], zc[win:2 * win], zc[2 * win:3 * win],
                                               zc[3 * win:], repeat(0.0, win))

    @staticmethod
    def backward(dout, pooled, z, win, win_idx, win_val, dense, c0, c1):
        """Enruta dOut a la posición ganadora de cada ventana con salida > 0.

        Guarda en win_idx[n][c] el índice (dentro de los parches de la imagen) de cada
        ganadora y en win_val[n][c] su gradiente. Si dense no es None también escribe el
        gradiente denso en dense[n][c*4*win + índice] (dense debe venir en cero)."""
        w2, w3 = 2 * win, 3 * win
        for n in range(len(dout)):
            d = dout[n]
            p = pooled[n]
            zn = z[n]
            dn = None if dense is None else dense[n]
            for c in range(c0, c1):
                zc = zn[c]
                base = c * win
                idx = []
                val = []
                for k in range(win):
                    pk = p[base + k]
                    if pk > 0.0:
                        if zc[k] == pk:
                            idx.append(k)
                        elif zc[k + win] == pk:
                            idx.append(k + win)
                        elif zc[k + w2] == pk:
                            idx.append(k + w2)
                        else:
                            idx.append(k + w3)
                        val.append(d[base + k])
                win_idx[n][c] = idx
                win_val[n][c] = val
                if dn is not None:
                    off = 4 * base
                    for i, v in zip(idx, val):
                        dn[off + i] = v


class MlpMicroChunk:
    """Capa totalmente conectada. forward y backward_weights trabajan sobre un rango de
    neuronas [j0, j1); backward_input sobre un rango de entradas [i0, i1)."""

    @staticmethod
    def forward(xs, wrows, wrows_sq, out, relu, j0, j1):
        """out[n][j] = W[j] . x[n] + b[j] (x[n] trae un 1.0 final para el sesgo)."""
        rows = wrows[j0:j1]
        rows_sq = wrows_sq[j0:j1]
        for n in range(len(xs)):
            x = xs[n]
            vals = dots(x, dist((0.0,) * len(x), x) ** 2, rows, rows_sq)
            if relu:
                out[n][j0:j1] = [v if v > 0.0 else 0.0 for v in vals]
            else:
                out[n][j0:j1] = vals

    @staticmethod
    def backward_weights(x_t, dz_t, grad, o_w, o_b, in_size, j0, j1):
        """Gradiente de W y b de las neuronas [j0, j1), SUMA sobre el lote.
        x_t: entradas transpuestas (in_size + 1 tuplas de n valores, la última son unos).
        dz_t: gradientes antes de la activación transpuestos (una tupla de n por neurona).
        Escribe (no acumula) en grad, que viene en cero."""
        x_sq = sq_norms(x_t, len(x_t[0]))
        zero = (0.0,) * len(x_t[0])
        for j in range(j0, j1):
            dz = dz_t[j]
            if not any(dz):
                continue
            g = dots(dz, dist(zero, dz) ** 2, x_t, x_sq)
            grad[o_w + j * in_size:o_w + (j + 1) * in_size] = g[:in_size]
            grad[o_b + j] = g[in_size]

    @staticmethod
    def backward_input(dz, wt, wt_sq, out, relu_act, i0, i1):
        """out[n][i] = sum_j W[j][i] * dz[n][j] para i en [i0, i1). Si relu_act no es None,
        multiplica por la derivada de la ReLU de la capa anterior (act > 0)."""
        cols = wt[i0:i1]
        cols_sq = wt_sq[i0:i1]
        for n in range(len(dz)):
            d = dz[n]
            vals = dots(d, dist((0.0,) * len(d), d) ** 2, cols, cols_sq)
            if relu_act is None:
                out[n][i0:i1] = vals
            else:
                out[n][i0:i1] = [v if a > 0.0 else 0.0 for v, a in zip(vals, relu_act[n][i0:i1])]


# ============================================================================
# Cnn: forward / backward completos (como Cnn.java)
# ============================================================================

class Stats:
    """Resultado de procesar un lote: suma de pérdidas y cantidad de aciertos."""

    def __init__(self, loss_sum=0.0, correct=0, count=0):
        self.loss_sum = loss_sum
        self.correct = correct
        self.count = count

    def add(self, o):
        self.loss_sum += o.loss_sum
        self.correct += o.correct
        self.count += o.count


class Acts:
    """Activaciones de un forward que hacen falta para el backward."""
    __slots__ = ("pt1", "z1", "p1", "pt2", "z2", "p2", "h", "probs")


class NoGc:
    """Desactiva el recolector de ciclos (gc) mientras se calcula. El cálculo crea
    millones de tuplas y el gc las recorre una y otra vez buscando ciclos (que no hay:
    la memoria se libera igual por conteo de referencias). Ahorra ~6% de tiempo."""

    def __enter__(self):
        self.was_enabled = gc.isenabled()
        gc.disable()

    def __exit__(self, *exc):
        if self.was_enabled:
            gc.enable()


class Cnn:
    """Conv(16) -> Pool -> Conv(32) -> Pool -> Dense(128) -> Dense(K) -> Softmax."""

    @staticmethod
    def normalize(raw):
        """Imagen cruda (planos R, G, B de 1024 bytes) -> lista de floats en [-1, 1]."""
        return list(map(tables().norm.__getitem__, raw))

    @staticmethod
    def forward(pp, images):
        """images: lista de imágenes crudas (3072 bytes cada una). pp: PreparedParams."""
        t = tables()
        n = len(images)
        k = pp.classes
        s = Acts()
        # conv1 + pool1 (+ ReLU)
        s.pt1 = []
        for img in images:
            x = Cnn.normalize(img)
            x.append(0.0)   # valor de las posiciones fuera de la imagen (padding)
            x.append(1.0)   # entrada del sesgo
            s.pt1.append(im2col(t.im2col1, x, K1 + 1))
        pt1_sq = [sq_norms(pts, K1 + 1) for pts in s.pt1]
        s.z1 = [[None] * C1 for _ in range(n)]
        ConvMicroChunk.forward(s.pt1, pt1_sq, pp.w1, pp.w1_sq, s.z1, 0, C1)
        s.p1 = [[0.0] * (C1 * P1H * P1W) + [0.0, 1.0] for _ in range(n)]   # + padding y sesgo
        PoolMicroChunk.forward(s.z1, P1H * P1W, s.p1, 0, C1)
        s.p1 = [array("d", p).tolist() for p in s.p1]   # floats contiguos (ver transpose)
        # conv2 + pool2 (+ ReLU); la salida del pool2 ya es el vector aplanado [c][y][x]
        s.pt2 = [im2col(t.im2col2, p, K2 + 1) for p in s.p1]
        pt2_sq = [sq_norms(pts, K2 + 1) for pts in s.pt2]
        s.z2 = [[None] * C2 for _ in range(n)]
        ConvMicroChunk.forward(s.pt2, pt2_sq, pp.w2, pp.w2_sq, s.z2, 0, C2)
        s.p2 = [[0.0] * FLAT + [1.0] for _ in range(n)]   # + entrada del sesgo de fc1
        PoolMicroChunk.forward(s.z2, P2H * P2W, s.p2, 0, C2)
        s.p2 = [array("d", p).tolist() for p in s.p2]
        # MLP: fc1 + ReLU, fc2
        s.h = [[0.0] * FC1 + [1.0] for _ in range(n)]     # + entrada del sesgo de fc2
        MlpMicroChunk.forward(s.p2, pp.w3, pp.w3_sq, s.h, True, 0, FC1)
        logits = [[0.0] * k for _ in range(n)]
        MlpMicroChunk.forward(s.h, pp.w4, pp.w4_sq, logits, False, 0, k)
        s.probs = [Cnn.softmax(z) for z in logits]
        return s

    @staticmethod
    def softmax(z):
        m = max(z)
        e = [math.exp(v - m) for v in z]
        total = sum(e)
        return [v / total for v in e]

    @staticmethod
    def argmax(v):
        """Índice del máximo; en empate gana el índice menor."""
        best = 0
        for i in range(1, len(v)):
            if v[i] > v[best]:
                best = i
        return best

    @staticmethod
    def predict(params, images):
        """Solo inferencia: probabilidades por clase de cada imagen."""
        probs = []
        with NoGc():
            pp = params.prepared()
            for a in range(0, len(images), SUB_BATCH):
                probs.extend(Cnn.forward(pp, images[a:a + SUB_BATCH]).probs)
        return probs

    @staticmethod
    def forward_backward(params, images, labels):
        """Forward + backward sobre un lote. Devuelve (Stats, grad) con grad = SUMA de los
        gradientes de las imágenes (no el promedio), en el orden del vector de pesos."""
        total = Stats()
        grad = None
        with NoGc():
            for a in range(0, len(images), SUB_BATCH):
                st, g = Cnn._forward_backward(params, images[a:a + SUB_BATCH], labels[a:a + SUB_BATCH])
                total.add(st)
                grad = g if grad is None else list(map(add, grad, g))
        if grad is None:
            grad = [0.0] * len(params.data)
        return total, grad

    @staticmethod
    def _forward_backward(params, images, labels):
        pp = params.prepared().with_backward()
        t = tables()
        k = params.classes
        n = len(images)
        s = Cnn.forward(pp, images)

        st = Stats(count=n)
        dl = []
        for i in range(n):
            pr = s.probs[i]
            y = labels[i]
            st.loss_sum += -math.log(max(pr[y], 1e-12))
            if Cnn.argmax(pr) == y:
                st.correct += 1
            d = list(pr)
            d[y] -= 1.0      # dLogits = softmax - onehot
            dl.append(d)

        grad = [0.0] * len(params.data)
        # MLP de salida y capa oculta
        MlpMicroChunk.backward_weights(transpose(s.h), list(zip(*dl)), grad, O_W4, params.o_b4, FC1, 0, k)
        dh = [[0.0] * FC1 for _ in range(n)]
        MlpMicroChunk.backward_input(dl, pp.w4t, pp.w4t_sq, dh, s.h, 0, FC1)
        MlpMicroChunk.backward_weights(transpose(s.p2), list(zip(*dh)), grad, O_W3, O_B3, FLAT, 0, FC1)
        dp2 = [[0.0] * FLAT for _ in range(n)]
        MlpMicroChunk.backward_input(dh, pp.w3t, pp.w3t_sq, dp2, None, 0, FLAT)
        # Bloque convolucional 2
        idx2 = [[None] * C2 for _ in range(n)]
        val2 = [[None] * C2 for _ in range(n)]
        dz2 = [[0.0] * (C2 * H2 * W2S + 1) for _ in range(n)]   # +1: el 0.0 del padding
        PoolMicroChunk.backward(dp2, s.p2, s.z2, P2H * P2W, idx2, val2, dz2, 0, C2)
        ConvMicroChunk.backward_weights(s.pt2, idx2, val2, grad, O_W2, O_B2, K2, 0, C2)
        dp1 = [[0.0] * (C1 * P1H * P1W) for _ in range(n)]
        ConvMicroChunk.backward_input(dz2, t.im2col2t, pp.w2t, dp1, K2T, P1H * P1W, 0, C1)
        # Bloque convolucional 1 (no hace falta el gradiente de la imagen de entrada)
        idx1 = [[None] * C1 for _ in range(n)]
        val1 = [[None] * C1 for _ in range(n)]
        PoolMicroChunk.backward(dp1, s.p1, s.z1, P1H * P1W, idx1, val1, None, 0, C1)
        ConvMicroChunk.backward_weights(s.pt1, idx1, val1, grad, O_W1, O_B1, K1, 0, C1)
        return st, grad


class Sgd:
    """SGD con momentum: v = momentum*v + g/n ; w = w - lr*v (g = SUMA de gradientes).
    Los pesos se redondean a float32 en cada paso (como en Sgd.java y en el protocolo);
    la velocidad se guarda en doble precisión."""

    def __init__(self, lr, momentum):
        self.lr = lr
        self.momentum = momentum
        self.velocity = None

    def step(self, w, grad_sum, n):
        """Devuelve la nueva lista de pesos."""
        if self.velocity is None:
            self.velocity = [0.0] * len(w)
        inv = 1.0 / n
        m = self.momentum
        lr = self.lr
        self.velocity = [m * v + g * inv for v, g in zip(self.velocity, grad_sum)]
        return round_f32([wi - lr * v for wi, v in zip(w, self.velocity)])


# ============================================================================
# Dataset (formato CIFAR binario: 1 byte de etiqueta + 3072 bytes de píxeles)
# ============================================================================

class Dataset:
    RECORD = 1 + IN_SIZE

    def __init__(self, labels, pixels, class_names):
        self.labels = labels          # bytes: una etiqueta por imagen
        self.pixels = pixels          # bytes: n * 3072 píxeles seguidos
        self.size = len(labels)
        self.class_names = class_names

    @staticmethod
    def load(path):
        with open(path, "rb") as f:
            raw = f.read()
        if len(raw) % Dataset.RECORD != 0:
            raise IOError("Tamano no multiplo de %d: %s" % (Dataset.RECORD, path))
        rec = Dataset.RECORD
        labels = raw[0::rec]
        mv = memoryview(raw)
        pixels = b"".join(mv[i * rec + 1:(i + 1) * rec] for i in range(len(labels)))
        names = []
        cpath = os.path.join(os.path.dirname(os.path.abspath(path)), "classes.txt")
        if os.path.exists(cpath):
            with open(cpath, encoding="utf-8") as f:
                names = [line.strip() for line in f if line.strip()]
        return Dataset(labels, pixels, names)

    def num_classes(self):
        if self.class_names:
            return len(self.class_names)
        return max(self.labels) + 1 if self.labels else 0

    def batch(self, start, n):
        """(etiquetas, píxeles) de las imágenes [start, start + n)."""
        return self.labels[start:start + n], self.pixels[start * IN_SIZE:(start + n) * IN_SIZE]


def split_images(pixels):
    n = len(pixels) // IN_SIZE
    return [pixels[i * IN_SIZE:(i + 1) * IN_SIZE] for i in range(n)]


# ============================================================================
# Motores de cálculo: H = 1 en este proceso, H > 1 con procesos
# ============================================================================

class LocalEngine:
    """Todo en el proceso actual (escenario secuencial). Guarda pesos por slot."""

    def __init__(self):
        self.slots = {}

    def threads(self):
        return 1

    def set_weights(self, slot, weights):
        """weights: lista de floats o array('f') en el orden del vector de pesos."""
        if isinstance(weights, array):
            weights = weights.tolist()
        self.slots[slot] = Params(weights)   # las tuplas se preparan al primer uso

    def train(self, slot, labels, pixels):
        return Cnn.forward_backward(self.slots[slot], split_images(pixels), labels)

    def infer(self, slot, pixels):
        return Cnn.predict(self.slots[slot], split_images(pixels))

    def close(self):
        pass


def _worker_main(conn):
    """Bucle de un proceso de cálculo: recibe pesos y tareas por su tubería."""
    signal.signal(signal.SIGINT, signal.SIG_IGN)   # Ctrl+C lo gestiona el proceso principal
    signal.signal(signal.SIGTERM, signal.SIG_DFL)
    engine = LocalEngine()
    while True:
        try:
            msg = conn.recv()
        except (EOFError, OSError):
            break
        kind = msg[0]
        if kind == "fin":
            break
        if kind == "pesos":   # sin respuesta: el proceso principal no la espera
            try:
                a = array("f")
                a.frombytes(msg[2])
                engine.set_weights(msg[1], a.tolist())
            except Exception:
                engine.slots.pop(msg[1], None)   # la próxima tarea de ese slot informará el error
            continue
        try:
            if kind == "train":
                st, grad = engine.train(msg[1], msg[2], msg[3])
                conn.send(("ok", st.loss_sum, st.correct, st.count, array("d", grad).tobytes()))
            elif kind == "infer":
                conn.send(("ok", engine.infer(msg[1], msg[2])))
        except Exception as e:  # el error se informa al proceso principal
            conn.send(("error", "%s: %s" % (type(e).__name__, e)))


class ProcessEngine:
    """H procesos de cálculo que viven todo el tiempo del nodo.

    Una tarea de n imágenes se divide en H partes contiguas; cada proceso ejecuta
    todos los micro chunks para sus imágenes y aquí se suman los resultados."""

    def __init__(self, h):
        ctx = multiprocessing.get_context()
        self.conns = []
        self.procs = []
        for i in range(h):
            parent, child = ctx.Pipe()
            p = ctx.Process(target=_worker_main, args=(child,), name="cubes-calc-%d" % i, daemon=True)
            p.start()
            child.close()
            self.conns.append(parent)
            self.procs.append(p)

    def threads(self):
        return len(self.procs)

    def set_weights(self, slot, weights):
        """weights: lista de floats o array('f'). Se envía una vez a cada proceso."""
        if not isinstance(weights, array):
            weights = array("f", weights)
        blob = weights.tobytes()   # float32 en el orden nativo de la máquina, 1 MB
        for c in self.conns:
            c.send(("pesos", slot, blob))

    def _split(self, n):
        parts = min(n, len(self.conns))
        return [(n * p // parts, n * (p + 1) // parts) for p in range(parts)]

    def _collect(self, count):
        out = [self.conns[p].recv() for p in range(count)]   # todas, para no desincronizar
        for p, r in enumerate(out):
            if r[0] != "ok":
                raise RuntimeError("proceso de calculo %d: %s" % (p, r[1]))
        return out

    def train(self, slot, labels, pixels):
        ranges = self._split(len(labels))
        for p, (a, b) in enumerate(ranges):
            self.conns[p].send(("train", slot, labels[a:b], pixels[a * IN_SIZE:b * IN_SIZE]))
        st = Stats()
        parts = []
        for r in self._collect(len(ranges)):
            st.add(Stats(r[1], r[2], r[3]))
            g = array("d")
            g.frombytes(r[4])
            parts.append(g)
        # suma por pares con map perezosos: una sola pasada en C sobre los H gradientes
        while len(parts) > 1:
            parts = [map(add, parts[i], parts[i + 1]) if i + 1 < len(parts) else parts[i]
                     for i in range(0, len(parts), 2)]
        return st, list(parts[0])

    def infer(self, slot, pixels):
        ranges = self._split(len(pixels) // IN_SIZE)
        for p, (a, b) in enumerate(ranges):
            self.conns[p].send(("infer", slot, pixels[a * IN_SIZE:b * IN_SIZE]))
        probs = []
        for r in self._collect(len(ranges)):
            probs.extend(r[1])
        return probs

    def close(self):
        for c in self.conns:
            try:
                c.send(("fin",))
            except OSError:
                pass
        for p in self.procs:
            p.join(timeout=1.0)
            if p.is_alive():
                p.terminate()


def create_engine(threads):
    return ProcessEngine(threads) if threads > 1 else LocalEngine()


# ============================================================================
# Argumentos con la forma --clave valor (como cubes.tools.Args)
# ============================================================================

class Args:
    def __init__(self, argv):
        self.values = {}
        i = 0
        while i < len(argv):
            a = argv[i]
            if a.startswith("--"):
                key = a[2:]
                if i + 1 < len(argv) and not argv[i + 1].startswith("--"):
                    self.values[key] = argv[i + 1]
                    i += 1
                else:
                    self.values[key] = "true"
            i += 1

    def get(self, key, default=None):
        return self.values.get(key, default)

    def get_int(self, key, default):
        v = self.values.get(key)
        return default if v is None else int(v)

    def get_float(self, key, default):
        v = self.values.get(key)
        return default if v is None else float(v)

    def has(self, key):
        return key in self.values


# ============================================================================
# Modo --bench: entrenamiento local sin red (como cubes.bench.LocalTrain)
# ============================================================================

def evaluate(engine, ds, count, batch=256):
    correct = 0
    for start in range(0, count, batch):
        n = min(batch, count - start)
        labels, pixels = ds.batch(start, n)
        probs = engine.infer(0, pixels)
        for i in range(n):
            if Cnn.argmax(probs[i]) == labels[i]:
                correct += 1
    return correct / count


def append_csv(path, label, threads, images, epochs, batch, sec, st, test_acc):
    header = not os.path.exists(path)
    parent = os.path.dirname(os.path.abspath(path))
    os.makedirs(parent, exist_ok=True)
    with open(path, "a", encoding="utf-8") as f:
        if header:
            f.write("escenario,lenguaje,maquinas,hilos,imagenes,epocas,lote,segundos,img_por_s,"
                    "perdida,acierto_train,acierto_test\n")
        f.write("%s,python,1,%d,%d,%d,%d,%s,%s,%s,%s,%s\n" % (
            label, threads, images, epochs, batch, jfmt(sec, 3), jfmt(images * epochs / sec, 2),
            jfmt(st.loss_sum / st.count, 4), jfmt(st.correct / st.count, 4), jfmt(test_acc, 4)))


def load_init(path, classes):
    if not os.path.exists(path):
        print("No existe el archivo de pesos iniciales %s (se genera con la version Java:"
              " java -cp out cubes.bench.LocalTrain ...)" % path)
        sys.exit(2)
    p = Params.load(path)
    if p.classes != classes:
        print("Los pesos de %s son para %d clases, el dataset tiene %d" % (path, p.classes, classes))
        sys.exit(2)
    return p


def bench(args):
    train = Dataset.load(args.get("train", "data/train.bin"))
    # A diferencia de Java, el conjunto de prueba solo se evalúa si se pasa --test
    # (en Python evaluar las 4000 imágenes por defecto tardaría demasiado).
    test_path = args.get("test")
    test = Dataset.load(test_path) if test_path and os.path.exists(test_path) else None
    threads = max(1, args.get_int("threads", 1))
    images = min(args.get_int("images", train.size), train.size)
    epochs = args.get_int("epochs", 1)
    batch = args.get_int("batch", 64)
    lr = args.get_float("lr", 0.01)
    momentum = args.get_float("momentum", 0.9)
    test_images = 0 if test is None else min(args.get_int("test-images", test.size), test.size)

    params = load_init(args.get("init", "data/init_weights.bin"), train.num_classes())
    weights = params.data
    sgd = Sgd(lr, momentum)

    print("LocalTrain: hilos=%d imagenes=%d epocas=%d lote=%d clases=%d"
          % (threads, images, epochs, batch, params.classes), flush=True)
    engine = create_engine(threads)
    try:
        t0 = time.perf_counter()
        last = None
        for e in range(1, epochs + 1):
            te = time.perf_counter()
            st = Stats()
            for start in range(0, images, batch):
                n = min(batch, images - start)
                labels, pixels = train.batch(start, n)
                engine.set_weights(1, weights)
                s, grad = engine.train(1, labels, pixels)
                st.add(s)
                weights = sgd.step(weights, grad, n)
            sec = time.perf_counter() - te
            print("  epoca %d: %s s, %s img/s, perdida %s, acierto entrenamiento %s%%"
                  % (e, jfmt(sec, 2), jfmt(images / sec, 1), jfmt(st.loss_sum / st.count, 4),
                     jfmt(100.0 * st.correct / st.count, 2)), flush=True)
            last = st
        train_sec = time.perf_counter() - t0

        test_acc = float("nan")
        if test_images > 0:
            engine.set_weights(0, weights)
            test_acc = evaluate(engine, test, test_images)
            print("  acierto en prueba (%d imagenes): %s%%" % (test_images, jfmt(100 * test_acc, 2)))
    finally:
        engine.close()

    if args.has("save"):
        Params(weights).save(args.get("save"))
        print("  pesos guardados en " + args.get("save"))
    if args.has("csv"):
        append_csv(args.get("csv"), args.get("label", "secuencial" if threads == 1 else "paralelo"),
                   threads, images, epochs, batch, train_sec, last, test_acc)
    print("TOTAL %s s" % jfmt(train_sec, 3))
    return 0


# ============================================================================
# Modo --verify: compara con data/ref.bin (generado por cubes.tools.RefDump)
# ============================================================================

def close_enough(a, b):
    return abs(a - b) <= 1e-3 + 1e-3 * abs(b)


def compare(name, got, ref):
    """Imprime el error máximo de una lista y devuelve True si todo está en tolerancia."""
    max_abs = 0.0
    worst = 0.0     # |a-b| / (1e-3 + 1e-3|b|): <= 1 significa dentro de tolerancia
    bad = 0
    for a, b in zip(got, ref):
        err = abs(a - b)
        max_abs = max(max_abs, err)
        worst = max(worst, err / (1e-3 + 1e-3 * abs(b)))
        if not close_enough(a, b):
            bad += 1
    ok = bad == 0 and len(got) == len(ref)
    print("  %-11s %s  n=%d  error max=%.3e  (error/tolerancia max=%.4f)  fuera de tolerancia=%d"
          % (name, "OK  " if ok else "FAIL", len(ref), max_abs, worst, bad))
    return ok


def verify(args):
    with open(args.get("verify"), "rb") as f:
        raw = f.read()
    magic, n = struct.unpack_from(">ii", raw, 0)
    if magic != REF_MAGIC:
        print("No es un archivo de referencia OCR1")
        return 2
    loss_ref, correct_ref, count = struct.unpack_from(">dii", raw, 8)
    off = 24
    grad_ref = f32_from_be(raw[off:off + 4 * count])
    off += 4 * count
    (k,) = struct.unpack_from(">i", raw, off)
    off += 4
    probs_ref = f32_from_be(raw[off:off + 4 * n * k])

    train = Dataset.load(args.get("train", "data/train.bin"))
    params = Params.load(args.get("init", "data/init_weights.bin"))
    threads = max(1, args.get_int("threads", 1))
    labels, pixels = train.batch(0, n)
    print("Verificacion: n=%d clases=%d pesos=%d hilos=%d" % (n, k, count, threads))
    engine = create_engine(threads)
    try:
        engine.set_weights(1, params.data)
        t0 = time.perf_counter()
        st, grad = engine.train(1, labels, pixels)
        t1 = time.perf_counter()
        probs = engine.infer(1, pixels)
    finally:
        engine.close()
    print("  forward+backward de %d imagenes: %.3f s" % (n, t1 - t0))
    ok = True
    ok &= compare("perdida", [st.loss_sum], [loss_ref])
    c_ok = st.correct == correct_ref
    print("  %-11s %s  python=%d java=%d" % ("aciertos", "OK  " if c_ok else "FAIL", st.correct, correct_ref))
    ok &= c_ok
    ok &= compare("gradientes", grad, grad_ref)
    ok &= compare("probs", [v for row in probs for v in row], probs_ref)
    print("VERIFICACION " + ("OK" if ok else "FALLIDA"))
    return 0 if ok else 1


# ============================================================================
# Protocolo OC/1 (lado del nodo) - ver docs/PROTOCOLO.md
# ============================================================================

REGISTER = 0x01
REGISTER_OK = 0x02
HEARTBEAT = 0x03
BYE = 0x04
SET_WEIGHTS = 0x10
TRAIN_TASK = 0x11
GRADIENTS = 0x12
INFER_TASK = 0x20
INFER_RESULT = 0x21
ERROR = 0x7F

HEADER = struct.Struct(">BBBBii")    # 'O' 'C' versión tipo idPetición longitud = 12 bytes
PROTO_VERSION = 1
MAX_PAYLOAD = 64 << 20
HEARTBEAT_SECONDS = 2.0
RETRY_SECONDS = 3.0


class ProtocolError(Exception):
    pass


def pack_str(s):
    b = s.encode("utf-8")[:65535]
    return struct.pack(">H", len(b)) + b


def unpack_str(buf, off=0):
    (n,) = struct.unpack_from(">H", buf, off)
    return bytes(buf[off + 2:off + 2 + n]).decode("utf-8", "replace"), off + 2 + n


def recv_exact(sock, n):
    buf = bytearray(n)
    view = memoryview(buf)
    pos = 0
    while pos < n:
        r = sock.recv_into(view[pos:], n - pos)
        if r == 0:
            raise ConnectionError("el servidor cerro la conexion")
        pos += r
    return bytes(buf)


def read_frame(sock):
    m1, m2, ver, typ, rid, length = HEADER.unpack(recv_exact(sock, HEADER.size))
    if m1 != 0x4F or m2 != 0x43:
        raise ProtocolError("cabecera invalida (no empieza con 'OC')")
    if ver != PROTO_VERSION:
        raise ProtocolError("version de protocolo %d no soportada" % ver)
    if length < 0 or length > MAX_PAYLOAD:
        raise ProtocolError("longitud de payload invalida: %d" % length)
    return typ, rid, recv_exact(sock, length) if length else b""


class Node:
    """Nodo de cómputo: se conecta al servidor, se registra y atiende tareas en orden.

    Hilos: el principal procesa los mensajes uno por uno (en el orden de llegada); un
    hilo lector los recibe del socket y los encola; otro envía HEARTBEAT cada 2 s. Las
    escrituras en el socket se serializan con un lock."""

    def __init__(self, host, port, engine, once=False, verbose=False):
        self.host = host
        self.port = port
        self.engine = engine
        self.once = once
        self.verbose = verbose
        self.sock = None
        self.write_lock = threading.Lock()
        self.pending = 0
        self.pending_lock = threading.Lock()

    # ---- envío ----
    def send(self, typ, rid, payload=b"", sock=None):
        data = HEADER.pack(0x4F, 0x43, PROTO_VERSION, typ, rid, len(payload)) + payload
        with self.write_lock:
            (sock or self.sock).sendall(data)

    def send_error(self, rid, message):
        self.send(ERROR, rid, pack_str(message))

    def log(self, message):
        """Línea por mensaje procesado (solo con --verbose, como el nodo Java que es silencioso)."""
        if self.verbose:
            print(message, flush=True)

    # ---- bucle de reconexión ----
    def run(self):
        while True:
            try:
                self.session()
            except KeyboardInterrupt:
                raise
            except ConnectionRefusedError:
                print("No se pudo conectar a %s:%d (servidor apagado?)" % (self.host, self.port), flush=True)
            except (OSError, ProtocolError) as e:
                print("Conexion con %s:%d terminada: %s" % (self.host, self.port, e), flush=True)
            if self.once:
                return
            print("Reintentando en %.0f s..." % RETRY_SECONDS, flush=True)
            time.sleep(RETRY_SECONDS)

    def session(self):
        print("Conectando a %s:%d ..." % (self.host, self.port), flush=True)
        sock = socket.create_connection((self.host, self.port), timeout=10)
        sock.settimeout(None)
        sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_KEEPALIVE, 1)
        self.sock = sock
        self.pending = 0
        versions = {}   # slot -> (versión, clases K); se borra en cada conexión nueva
        stop = threading.Event()
        try:
            hostname = socket.gethostname()
            self.send(REGISTER, 1, pack_str("python") + struct.pack(">ii", os.cpu_count() or 1,
                                                                   self.engine.threads()) + pack_str(hostname))
            typ, rid, payload = read_frame(sock)
            if typ == ERROR:
                raise ProtocolError("registro rechazado: " + unpack_str(payload)[0])
            if typ != REGISTER_OK or len(payload) < 4:
                raise ProtocolError("se esperaba REGISTER_OK y llego el tipo 0x%02X" % typ)
            node_id = struct.unpack_from(">i", payload)[0]
            print("Registrado como nodo %d (python, nucleos=%d, procesos=%d, host=%s)"
                  % (node_id, os.cpu_count() or 1, self.engine.threads(), hostname), flush=True)

            inbox = queue.Queue()
            reader = threading.Thread(target=self._reader, args=(sock, inbox), daemon=True)
            beat = threading.Thread(target=self._heartbeat, args=(sock, stop), daemon=True)
            reader.start()
            beat.start()

            while True:
                try:
                    item = inbox.get(timeout=0.5)
                except queue.Empty:
                    continue
                if isinstance(item, Exception):
                    raise item
                typ, rid, payload = item
                if typ == BYE:
                    print("El servidor envio BYE", flush=True)
                    return
                try:
                    self.handle(typ, rid, payload, versions)
                finally:
                    if typ in (TRAIN_TASK, INFER_TASK):
                        with self.pending_lock:
                            self.pending -= 1
        except KeyboardInterrupt:
            print("\nCtrl+C: enviando BYE al servidor", flush=True)
            try:
                self.send(BYE, 0)
            except OSError:
                pass
            raise
        finally:
            stop.set()
            try:
                sock.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass
            sock.close()

    def _reader(self, sock, inbox):
        try:
            while True:
                frame = read_frame(sock)
                if frame[0] in (TRAIN_TASK, INFER_TASK):
                    with self.pending_lock:
                        self.pending += 1
                inbox.put(frame)
        except Exception as e:  # conexión cerrada o error de protocolo
            inbox.put(e if isinstance(e, (OSError, ProtocolError)) else ProtocolError(str(e)))

    def _heartbeat(self, sock, stop):
        while not stop.wait(HEARTBEAT_SECONDS):
            try:
                with self.pending_lock:
                    pending = self.pending
                self.send(HEARTBEAT, 0, struct.pack(">i", pending), sock)
            except OSError:
                try:
                    sock.shutdown(socket.SHUT_RDWR)   # despierta al hilo lector
                except OSError:
                    pass
                return

    # ---- mensajes ----
    def handle(self, typ, rid, p, versions):
        try:
            if typ == SET_WEIGHTS:
                slot, version, count = struct.unpack_from(">Bii", p, 0)
                if len(p) != 9 + 4 * count:
                    raise ValueError("SET_WEIGHTS con longitud incorrecta")
                classes = Params.classes_from_size(count)   # valida el tamaño
                weights = array("f")
                weights.frombytes(p[9:])
                if LITTLE:
                    weights.byteswap()
                self.engine.set_weights(slot, weights)
                versions[slot] = (version, classes)
                self.log("SET_WEIGHTS slot=%d version=%d (%d pesos)" % (slot, version, count))
            elif typ == TRAIN_TASK:
                slot, version, n = struct.unpack_from(">Bii", p, 0)
                if versions.get(slot, (None,))[0] != version:
                    self.send_error(rid, "version")
                    print("TRAIN_TASK id=%d: version %d no coincide con la del slot %d (%s) -> ERROR"
                          % (rid, version, slot, versions.get(slot, (None,))[0]), flush=True)
                    return
                if n < 0 or len(p) != 9 + n + n * IN_SIZE:
                    raise ValueError("TRAIN_TASK con longitud incorrecta")
                labels = p[9:9 + n]
                if n and max(labels) >= versions[slot][1]:
                    raise ValueError("etiqueta fuera de rango (K=%d)" % versions[slot][1])
                pixels = p[9 + n:]
                t0 = time.perf_counter()
                st, grad = self.engine.train(slot, labels, pixels)
                ms = int(round((time.perf_counter() - t0) * 1000))
                out = (struct.pack(">iidiii", version, n, st.loss_sum, st.correct, ms, len(grad))
                       + f32_to_be(grad))
                self.send(GRADIENTS, rid, out)
                self.log("TRAIN_TASK id=%d slot=%d version=%d n=%d: %d ms, perdida media %.4f, aciertos %d"
                         % (rid, slot, version, n, ms, st.loss_sum / max(n, 1), st.correct))
            elif typ == INFER_TASK:
                slot, version, n = struct.unpack_from(">Bii", p, 0)
                if versions.get(slot, (None,))[0] != version:
                    self.send_error(rid, "version")
                    print("INFER_TASK id=%d: version %d no coincide con la del slot %d (%s) -> ERROR"
                          % (rid, version, slot, versions.get(slot, (None,))[0]), flush=True)
                    return
                if n < 0 or len(p) != 9 + n * IN_SIZE:
                    raise ValueError("INFER_TASK con longitud incorrecta")
                t0 = time.perf_counter()
                probs = self.engine.infer(slot, p[9:])
                ms = int(round((time.perf_counter() - t0) * 1000))
                k = versions[slot][1]
                out = (struct.pack(">ii", n, k) + f32_to_be([v for row in probs for v in row])
                       + struct.pack(">i", ms))
                self.send(INFER_RESULT, rid, out)
                self.log("INFER_TASK id=%d slot=%d version=%d n=%d: %d ms" % (rid, slot, version, n, ms))
            elif typ == ERROR:
                print("ERROR del servidor (id=%d): %s" % (rid, unpack_str(p)[0]), flush=True)
            elif typ in (HEARTBEAT, REGISTER_OK):
                pass
            else:
                self.send_error(rid, "tipo de mensaje desconocido 0x%02X" % typ)
        except OSError:
            raise   # problema del socket: termina la sesión
        except Exception as e:  # mensaje inválido o error de cálculo: se responde ERROR y se sigue
            print("Error procesando mensaje 0x%02X id=%d: %s: %s" % (typ, rid, type(e).__name__, e), flush=True)
            self.send_error(rid, str(e) or type(e).__name__)


def _raise_interrupt(signum, frame):
    raise KeyboardInterrupt


def run_node(args):
    host = args.get("server", "127.0.0.1")
    port = args.get_int("port", 6000)
    threads = max(1, args.get_int("threads", os.cpu_count() or 1))   # como JavaNode: todos los núcleos
    print("Nodo Python (Open AI Cubes): servidor %s:%d, procesos de calculo=%d" % (host, port, threads),
          flush=True)
    engine = create_engine(threads)    # antes de crear hilos (fork seguro)
    # Ctrl+C (SIGINT) y kill (SIGTERM) terminan el nodo enviando BYE. Se instala el
    # manejador explícitamente porque un proceso lanzado en segundo plano desde un
    # script hereda SIGINT ignorado y Python no lo activaría.
    signal.signal(signal.SIGINT, signal.default_int_handler)
    signal.signal(signal.SIGTERM, _raise_interrupt)
    try:
        Node(host, port, engine, once=args.has("once"), verbose=args.has("verbose")).run()
    except KeyboardInterrupt:
        print("Nodo detenido", flush=True)
    finally:
        engine.close()
    return 0


def main(argv):
    args = Args(argv)
    if args.has("verify"):
        return verify(args)
    if args.has("bench"):
        return bench(args)
    return run_node(args)


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
