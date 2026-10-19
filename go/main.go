// Nodo de cómputo Go de Open AI Cubes. Solo usa la biblioteca estándar de Go.
//
// Modos:
//
//	nodo-go --server 127.0.0.1 --port 6000 --threads 4            (nodo en red, por defecto)
//	nodo-go --verify ../data/ref.bin --init ../data/init_weights.bin --train ../data/train.bin
//	nodo-go --bench --train ../data/train.bin --images 1024 --epochs 1 --batch 64 --threads 4
//
// Ver README.md, docs/CNN_SPEC.md y docs/PROTOCOLO.md.
package main

import (
	"flag"
	"fmt"
	"os"
	"runtime"
	"time"
)

func fatal(err error) {
	fmt.Fprintln(os.Stderr, "error:", err)
	os.Exit(1)
}

func main() {
	verify := flag.String("verify", "", "archivo de referencia de Java (data/ref.bin): verifica forward+backward y termina")
	bench := flag.Bool("bench", false, "entrenamiento local sin red (equivale a LocalTrain de Java)")

	trainPath := flag.String("train", "../data/train.bin", "dataset de entrenamiento (formato CIFAR)")
	testPath := flag.String("test", "", "dataset de prueba para --bench (vacio = no se evalua, acierto_test = NaN)")
	initPath := flag.String("init", "../data/init_weights.bin", "pesos iniciales OCW1")
	threads := flag.Int("threads", 0, "goroutines por capa (H); 0 = 1 en --bench/--verify y nucleos en modo nodo")
	images := flag.Int("images", 0, "imagenes de entrenamiento para --bench (0 = todas)")
	epochs := flag.Int("epochs", 1, "epocas para --bench")
	batch := flag.Int("batch", 64, "tamano de lote para --bench")
	lr := flag.Float64("lr", 0.01, "tasa de aprendizaje para --bench")
	momentum := flag.Float64("momentum", 0.9, "momentum para --bench")
	testImages := flag.Int("test-images", 0, "imagenes de prueba a evaluar (0 = todas)")
	csvPath := flag.String("csv", "", "agrega una fila al CSV de resultados (--bench)")
	label := flag.String("label", "", "escenario en el CSV (por defecto secuencial si H=1, si no paralelo)")
	savePath := flag.String("save", "", "guarda los pesos entrenados en este archivo (--bench)")

	server := flag.String("server", "127.0.0.1", "IP o nombre del servidor central (modo nodo)")
	port := flag.Int("port", 6000, "puerto de nodos del servidor")
	retry := flag.Int("retry", 3, "segundos de espera antes de reconectar")
	quiet := flag.Bool("quiet", false, "no imprimir una linea por cada mensaje (modo nodo)")
	flag.Parse()
	if flag.NArg() > 0 {
		fmt.Fprintf(os.Stderr, "argumentos no reconocidos: %v\n", flag.Args())
		flag.Usage()
		os.Exit(2)
	}

	h := *threads
	switch {
	case *verify != "":
		if h <= 0 {
			h = 1
		}
		if !runVerify(*verify, *initPath, *trainPath, h) {
			os.Exit(1)
		}
	case *bench:
		if h <= 0 {
			h = 1
		}
		runBench(benchConfig{
			trainPath: *trainPath, testPath: *testPath, initPath: *initPath,
			threads: h, images: *images, epochs: *epochs, batch: *batch, testImages: *testImages,
			lr: float32(*lr), momentum: float32(*momentum),
			csvPath: *csvPath, label: *label, savePath: *savePath,
		})
	default:
		if h <= 0 {
			h = runtime.NumCPU()
		}
		runNode(nodeConfig{server: *server, port: *port, threads: h,
			retry: time.Duration(*retry) * time.Second, quiet: *quiet})
	}
}
