"""Nodo de cómputo en Python para Open AI Cubes. Misma CNN y mismo protocolo que el nodo Java.

En Python el GIL impide que los hilos calculen en paralelo, así que el trabajo se reparte entre
procesos (multiprocessing): cada proceso calcula una parte de las imágenes del lote.
Solo usa la librería estándar.  Uso: python nodo.py [ip_servidor] [procesos]
"""
import multiprocessing as mp
import socket
import struct
import sys
from array import array
from itertools import repeat
from math import exp, log
from operator import add, mul

C1, C2, FC1, FLAT, IN = 16, 32, 128, 32 * 64, 3072
W1 = 0
B1 = W1 + C1 * 27
W2 = B1 + C1
B2 = W2 + C2 * C1 * 9
W3 = B2 + C2
B3 = W3 + FC1 * FLAT
W4 = B3 + FC1


def tabla(c, s):
    """Para cada píxel, los índices de sus c*3*3 vecinos en la entrada (-1 fuera del borde)."""
    t = []
    for y in range(s):
        for x in range(s):
            fila = []
            for ch in range(c):
                for ky in (-1, 0, 1):
                    for kx in (-1, 0, 1):
                        yy, xx = y + ky, x + kx
                        fila.append(ch * s * s + yy * s + xx if 0 <= yy < s and 0 <= xx < s else -1)
            t.append(fila)
    return t


T1, T2 = tabla(3, 32), tabla(C1, 16)


def columnas(img, t):
    return [[img[j] if j >= 0 else 0.0 for j in fila] for fila in t]


def preparar(w):
    """Pesos agrupados por filtro o neurona, para usar sum(map(mul, ...)) en cada producto."""
    k = (len(w) - W4) // (FC1 + 1)
    return {
        "w": w, "k": k,
        "f1": [w[W1 + f * 27:W1 + (f + 1) * 27] for f in range(C1)],
        "f2": [w[W2 + f * 144:W2 + (f + 1) * 144] for f in range(C2)],
        "r3": [w[W3 + j * FLAT:W3 + (j + 1) * FLAT] for j in range(FC1)],
        "r4": [w[W4 + j * FC1:W4 + (j + 1) * FC1] for j in range(k)],
    }


def conv(cols, filtros, w, ob):
    out = []
    for f, wf in enumerate(filtros):
        b = w[ob + f]
        out.extend(max(0.0, b + sum(map(mul, wf, col))) for col in cols)
    return out


def pool(a, c, s):
    h = s // 2
    out, idx = [], []
    for ch in range(c):
        for y in range(h):
            for x in range(h):
                base = ch * s * s + 2 * y * s + 2 * x
                best = max((base, base + 1, base + s, base + s + 1), key=lambda i: a[i])
                out.append(a[best])
                idx.append(best)
    return out, idx


def adelante(P, img):
    w, k = P["w"], P["k"]
    c1 = columnas(img, T1)
    a1 = conv(c1, P["f1"], w, B1)
    p1, i1 = pool(a1, C1, 32)
    c2 = columnas(p1, T2)
    a2 = conv(c2, P["f2"], w, B2)
    p2, i2 = pool(a2, C2, 16)
    h = [max(0.0, w[B3 + j] + sum(map(mul, r, p2))) for j, r in enumerate(P["r3"])]
    b4 = W4 + FC1 * k
    z = [w[b4 + j] + sum(map(mul, r, h)) for j, r in enumerate(P["r4"])]
    m = max(z)
    e = [exp(v - m) for v in z]
    probs = [v / sum(e) for v in e]
    return probs, (c1, a1, i1, c2, a2, p2, i2, h)


def atras(P, img, etiqueta, g):
    """Suma en g los gradientes de una imagen y devuelve (pérdida, acierto)."""
    w, k = P["w"], P["k"]
    probs, (c1, a1, i1, c2, a2, p2, i2, h) = adelante(P, img)
    dz = [p - (1.0 if c == etiqueta else 0.0) for c, p in enumerate(probs)]
    b4 = W4 + FC1 * k
    # MLP de salida y capa oculta
    dh = [0.0] * FC1
    for j, d in enumerate(dz):
        g[b4 + j] += d
        base = W4 + j * FC1
        g[base:base + FC1] = map(add, g[base:base + FC1], map(mul, h, repeat(d)))
        dh = list(map(add, dh, map(mul, P["r4"][j], repeat(d))))
    dh = [d if v > 0 else 0.0 for d, v in zip(dh, h)]
    dp2 = [0.0] * FLAT
    for j, d in enumerate(dh):
        if d:
            g[B3 + j] += d
            base = W3 + j * FLAT
            g[base:base + FLAT] = map(add, g[base:base + FLAT], map(mul, p2, repeat(d)))
            dp2 = list(map(add, dp2, map(mul, P["r3"][j], repeat(d))))
    # Bloque convolucional 2
    dz2 = [0.0] * (C2 * 256)
    for q, i in enumerate(i2):
        if a2[i] > 0:
            dz2[i] = dp2[q]
    ct2 = list(zip(*c2))
    filas = [dz2[f * 256:(f + 1) * 256] for f in range(C2)]
    for f, dzf in enumerate(filas):
        g[B2 + f] += sum(dzf)
        base = W2 + f * 144
        g[base:base + 144] = map(add, g[base:base + 144], [sum(map(mul, dzf, col)) for col in ct2])
    wt2 = list(zip(*P["f2"]))
    dp1 = [0.0] * (C1 * 256)
    for i, (dzi, fila) in enumerate(zip(zip(*filas), T2)):
        if any(dzi):
            for kk, j in enumerate(fila):
                if j >= 0:
                    dp1[j] += sum(map(mul, dzi, wt2[kk]))
    # Bloque convolucional 1
    dz1 = [0.0] * (C1 * 1024)
    for q, i in enumerate(i1):
        if a1[i] > 0:
            dz1[i] = dp1[q]
    ct1 = list(zip(*c1))
    for f in range(C1):
        dzf = dz1[f * 1024:(f + 1) * 1024]
        g[B1 + f] += sum(dzf)
        base = W1 + f * 27
        g[base:base + 27] = map(add, g[base:base + 27], [sum(map(mul, dzf, col)) for col in ct1])
    return -log(max(probs[etiqueta], 1e-12)), probs.index(max(probs)) == etiqueta


# ---------- Trabajo de cada proceso (cachea los pesos mientras no cambie la versión) ----------

_cache = [None, None]


def trabajo(args):
    version, pesos, etiquetas, imgs = args
    if _cache[0] != version:
        _cache[:] = [version, preparar(array("f", pesos).tolist())]
    P = _cache[1]
    if etiquetas is None:
        return [adelante(P, img)[0] for img in imgs]
    g, perdida, aciertos = [0.0] * len(P["w"]), 0.0, 0
    for img, e in zip(imgs, etiquetas):
        l, ok = atras(P, img, e, g)
        perdida += l
        aciertos += ok
    return g, perdida, aciertos


# ---------- Protocolo con el servidor (big-endian, igual que DataInput/DataOutput de Java) ----------

def recibir(sock, n):
    buf = bytearray()
    while len(buf) < n:
        parte = sock.recv(n - len(buf))
        if not parte:
            raise ConnectionError("el servidor cerró la conexión")
        buf += parte
    return bytes(buf)


def floats_be(data):
    a = array("f")
    a.frombytes(data)
    if sys.byteorder == "little":
        a.byteswap()
    return a


def a_bytes_be(valores):
    a = array("f", valores)
    if sys.byteorder == "little":
        a.byteswap()
    return a.tobytes()


def main():
    host = sys.argv[1] if len(sys.argv) > 1 else "127.0.0.1"
    procesos = int(sys.argv[2]) if len(sys.argv) > 2 else mp.cpu_count()
    sock = socket.create_connection((host, 6000))
    sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
    sock.sendall(struct.pack(">H", 6) + b"python" + struct.pack(">i", procesos))
    print(f"Nodo Python conectado a {host} con {procesos} procesos")
    grupo = mp.Pool(procesos) if procesos > 1 else None
    pesos, version = None, 0
    try:
        while True:
            tipo, n = struct.unpack(">ci", recibir(sock, 5))
            if tipo == b"W":
                pesos, version = floats_be(recibir(sock, 4 * n)).tobytes(), version + 1
                continue
            etiquetas = list(recibir(sock, n)) if tipo == b"T" else None
            imgs = [[b / 127.5 - 1.0 for b in recibir(sock, IN)] for _ in range(n)]
            # Una parte de las imágenes por proceso
            partes = [(version, pesos, etiquetas and etiquetas[i::procesos], imgs[i::procesos]) for i in range(min(procesos, n))]
            resultados = grupo.map(trabajo, partes) if grupo else [trabajo(p) for p in partes]
            if tipo == b"I":
                probs = [None] * n
                for i, r in enumerate(resultados):
                    probs[i::procesos] = r
                sock.sendall(struct.pack(">i", len(probs[0])) + b"".join(a_bytes_be(p) for p in probs))
            else:
                g = resultados[0][0]
                for r in resultados[1:]:
                    g = list(map(add, g, r[0]))
                perdida = sum(r[1] for r in resultados)
                aciertos = sum(r[2] for r in resultados)
                sock.sendall(a_bytes_be(g) + struct.pack(">di", perdida, aciertos))
            print(f"{tipo.decode()}: {n} imágenes")
    except OSError:
        print("El servidor cerró la conexión")


if __name__ == "__main__":
    main()
