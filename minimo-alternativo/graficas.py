"""Gráficas de desempeño de Open AI Cubes a partir de resultados.csv y carga.csv.

No es parte del sistema distribuido (es un agregado para el informe), así que usa matplotlib:
    pip install matplotlib
    python graficas.py        (en la carpeta donde están los CSV)
Genera velocidad.png, aceleracion.png, carga.png y desempeno.png (las tres juntas).
"""
import csv
import os

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt

COLOR = {"secuencial": "#9aa0a6", "paralelo": "#4c8dff", "distribuido": "#f0965a", "paralelo_distribuido": "#8fd16a"}


def leer(nombre):
    if not os.path.exists(nombre):
        return []
    with open(nombre, encoding="utf-8") as f:
        return list(csv.DictReader(f))


def velocidad(ax, res):
    etiquetas = [f"{r['escenario'].replace('_', ' ')}\n{r['lenguajes']} · {r['nodos']} nodo(s) x {r['hilos']} hilo(s)" for r in res]
    valores = [float(r["img_por_s"]) for r in res]
    barras = ax.barh(etiquetas, valores, color=[COLOR.get(r["escenario"], "#c77dff") for r in res])
    ax.bar_label(barras, fmt="%.0f img/s", padding=4)
    ax.set_title("Velocidad de entrenamiento por escenario")
    ax.set_xlabel("imágenes por segundo")
    ax.invert_yaxis()
    ax.set_xlim(0, max(valores) * 1.25)


def aceleracion(ax, res):
    base = next(float(r["img_por_s"]) for r in res if r["escenario"] == "secuencial")
    etiquetas = [f"{r['escenario'].replace('_', ' ')}\n{r['lenguajes']}" for r in res]
    valores = [float(r["img_por_s"]) / base for r in res]
    barras = ax.bar(etiquetas, valores, color=[COLOR.get(r["escenario"], "#c77dff") for r in res])
    ax.bar_label(barras, fmt="%.1fx", padding=3)
    ax.axhline(1, color="#666", lw=1, ls="--")
    ax.set_title("Aceleración respecto al secuencial")
    ax.set_ylabel("veces más rápido")
    ax.set_ylim(0, max(valores) * 1.25)


def carga(ax, filas):
    x = [int(r["clientes"]) for r in filas]
    ax.plot(x, [float(r["resp_por_s"]) for r in filas], "o-", color="#4c8dff", lw=2, label="respuestas por segundo")
    ax.set_xlabel("clientes simultáneos")
    ax.set_ylabel("respuestas por segundo", color="#4c8dff")
    ax2 = ax.twinx()
    ax2.plot(x, [float(r["lat_media_ms"]) for r in filas], "s--", color="#f0965a", lw=2, label="latencia media (ms)")
    ax2.plot(x, [float(r["p95_ms"]) for r in filas], "^:", color="#e0454f", lw=2, label="latencia p95 (ms)")
    ax2.set_ylabel("milisegundos", color="#f0965a")
    errores = sum(int(r["clientes_con_error"]) for r in filas)
    ax.set_title(f"Prueba de carga ({sum(int(r['respuestas']) for r in filas):,} consultas, {errores} clientes con error)")
    lineas = ax.get_legend_handles_labels()[0] + ax2.get_legend_handles_labels()[0]
    ax.legend(lineas, [l.get_label() for l in lineas], loc="upper left")


def main():
    res, cg = leer("resultados.csv"), sorted(leer("carga.csv"), key=lambda r: int(r["clientes"]))
    paneles = []
    if res:
        paneles.append(("velocidad.png", velocidad, res))
        if any(r["escenario"] == "secuencial" for r in res):
            paneles.append(("aceleracion.png", aceleracion, res))
    if cg:
        paneles.append(("carga.png", carga, cg))
    if not paneles:
        print("No hay resultados.csv ni carga.csv en esta carpeta.")
        return
    for nombre, dibujar, datos in paneles:
        fig, ax = plt.subplots(figsize=(9, 4.8))
        dibujar(ax, datos)
        fig.tight_layout()
        fig.savefig(nombre, dpi=130)
        plt.close(fig)
    fig, axes = plt.subplots(1, len(paneles), figsize=(7.5 * len(paneles), 5))
    for ax, (_, dibujar, datos) in zip(axes if len(paneles) > 1 else [axes], paneles):
        dibujar(ax, datos)
    fig.suptitle("Open AI Cubes · desempeño", fontsize=15, fontweight="bold")
    fig.tight_layout()
    fig.savefig("desempeno.png", dpi=110)
    print("Listo:", ", ".join(n for n, _, _ in paneles), "y desempeno.png")


if __name__ == "__main__":
    main()
