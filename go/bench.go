package main

// Modos locales (sin red):
//   --verify: compara forward+backward con el archivo de referencia generado por Java.
//   --bench:  entrenamiento local, equivale a cubes.bench.LocalTrain (escenarios
//             "secuencial" con --threads 1 y "paralelo" con --threads H).

import (
	"fmt"
	"math"
	"os"
	"path/filepath"
	"time"
)

// ---------------------------------------------------------------------------
// Verificación contra data/ref.bin
// ---------------------------------------------------------------------------

type reference struct {
	n       int
	lossSum float64
	correct int
	grad    []float32
	classes int
	probs   [][]float32
}

func loadReference(path string) (*reference, error) {
	raw, err := os.ReadFile(path)
	if err != nil {
		return nil, err
	}
	r := &payloadReader{buf: raw}
	if uint32(r.i32()) != 0x4F435231 { // "OCR1"
		return nil, fmt.Errorf("no es un archivo de referencia OCR1: %s", path)
	}
	ref := &reference{}
	ref.n = int(r.i32())
	ref.lossSum = r.f64()
	ref.correct = int(r.i32())
	ref.grad = r.f32s()
	ref.classes = int(r.i32())
	if r.err == nil && (ref.n < 0 || ref.classes <= 0) {
		return nil, fmt.Errorf("referencia invalida: n=%d K=%d", ref.n, ref.classes)
	}
	for i := 0; i < ref.n && r.err == nil; i++ {
		ref.probs = append(ref.probs, r.f32n(ref.classes))
	}
	if r.err != nil {
		return nil, fmt.Errorf("referencia truncada: %v", r.err)
	}
	return ref, nil
}

// closeEnough es el criterio de igualdad de la especificación: |a - b| <= 1e-3 + 1e-3*|b|.
func closeEnough(a, b float64) bool {
	return math.Abs(a-b) <= 1e-3+1e-3*math.Abs(b)
}

// errStats acumula el error absoluto máximo y cuántos elementos fallan la tolerancia.
type errStats struct {
	maxAbs float64
	worst  int
	bad    int
	total  int
}

func (e *errStats) add(i int, a, b float64) {
	if d := math.Abs(a - b); d > e.maxAbs {
		e.maxAbs = d
		e.worst = i
	}
	if !closeEnough(a, b) {
		e.bad++
	}
	e.total++
}

func runVerify(refPath, initPath, trainPath string, threads int) bool {
	ref, err := loadReference(refPath)
	if err != nil {
		fatal(err)
	}
	params, err := LoadParams(initPath)
	if err != nil {
		fatal(err)
	}
	ds, err := LoadDataset(trainPath)
	if err != nil {
		fatal(err)
	}
	if ref.n > ds.Size {
		fatal(fmt.Errorf("la referencia usa %d imagenes y %s tiene %d", ref.n, trainPath, ds.Size))
	}
	if len(ref.grad) != len(params.Data) || ref.classes != params.Classes {
		fatal(fmt.Errorf("tamanos distintos: referencia %d pesos / K=%d, init %d pesos / K=%d",
			len(ref.grad), ref.classes, len(params.Data), params.Classes))
	}
	fmt.Printf("Verificacion Go: n=%d hilos=%d pesos=%d clases=%d\n", ref.n, threads, len(params.Data), params.Classes)

	x := make([][]float32, ref.n)
	y := make([]int, ref.n)
	for i := range x {
		x[i] = ds.Image(i)
		y[i] = ds.Label(i)
	}
	ex := NewChunkExecutor(threads)
	cnn := NewCnn(ex)
	grad := make([]float32, len(params.Data))
	t0 := time.Now()
	st := cnn.ForwardBackward(params, x, y, grad)
	probs := cnn.Predict(params, x)
	ms := time.Since(t0).Seconds() * 1000

	ok := true
	lossOK := closeEnough(st.LossSum, ref.lossSum)
	fmt.Printf("  perdida: go=%.6f java=%.6f error=%.3g %s\n", st.LossSum, ref.lossSum,
		math.Abs(st.LossSum-ref.lossSum), okText(lossOK))
	ok = ok && lossOK
	corrOK := st.Correct == ref.correct
	fmt.Printf("  aciertos: go=%d java=%d %s\n", st.Correct, ref.correct, okText(corrOK))
	ok = ok && corrOK

	var ge errStats
	for i := range grad {
		ge.add(i, float64(grad[i]), float64(ref.grad[i]))
	}
	fmt.Printf("  gradientes: %d valores, error max %.3g (indice %d: go=%.6g java=%.6g), fuera de tolerancia %d %s\n",
		ge.total, ge.maxAbs, ge.worst, grad[ge.worst], ref.grad[ge.worst], ge.bad, okText(ge.bad == 0))
	ok = ok && ge.bad == 0

	var pe errStats
	for i := 0; i < ref.n; i++ {
		for k := 0; k < ref.classes; k++ {
			pe.add(i*ref.classes+k, float64(probs[i][k]), float64(ref.probs[i][k]))
		}
	}
	fmt.Printf("  probabilidades: %d valores, error max %.3g, fuera de tolerancia %d %s\n",
		pe.total, pe.maxAbs, pe.bad, okText(pe.bad == 0))
	ok = ok && pe.bad == 0

	fmt.Printf("  tiempo forward+backward+predict: %.1f ms\n", ms)
	if ok {
		fmt.Printf("OK (error max gradientes %.3g, probabilidades %.3g, perdida %.3g)\n",
			ge.maxAbs, pe.maxAbs, math.Abs(st.LossSum-ref.lossSum))
	} else {
		fmt.Println("FAIL")
	}
	return ok
}

func okText(ok bool) string {
	if ok {
		return "ok"
	}
	return "FALLA"
}

// ---------------------------------------------------------------------------
// Entrenamiento local (LocalTrain)
// ---------------------------------------------------------------------------

type benchConfig struct {
	trainPath, testPath, initPath  string
	threads, images, epochs, batch int
	testImages                     int
	lr, momentum                   float32
	csvPath, label, savePath       string
}

func runBench(cfg benchConfig) {
	train, err := LoadDataset(cfg.trainPath)
	if err != nil {
		fatal(err)
	}
	var test *Dataset
	if cfg.testPath != "" {
		if test, err = LoadDataset(cfg.testPath); err != nil {
			fatal(err)
		}
	}
	images := cfg.images
	if images <= 0 || images > train.Size {
		images = train.Size
	}
	testImages := 0
	if test != nil {
		testImages = cfg.testImages
		if testImages <= 0 || testImages > test.Size {
			testImages = test.Size
		}
	}
	if cfg.batch <= 0 {
		cfg.batch = 64
	}

	params, err := LoadParams(cfg.initPath)
	if err != nil {
		fatal(fmt.Errorf("no se pudieron leer los pesos iniciales (generarlos con Java LocalTrain): %v", err))
	}
	if k := train.NumClasses(); k != params.Classes {
		fatal(fmt.Errorf("%s tiene K=%d y el dataset %d clases", cfg.initPath, params.Classes, k))
	}
	sgd := NewSgd(cfg.lr, cfg.momentum)

	fmt.Printf("LocalTrain: hilos=%d imagenes=%d epocas=%d lote=%d clases=%d\n",
		cfg.threads, images, cfg.epochs, cfg.batch, params.Classes)
	ex := NewChunkExecutor(cfg.threads)
	cnn := NewCnn(ex)

	// Buffers reutilizados entre lotes: imágenes normalizadas, etiquetas y gradiente.
	xbuf := newMatrix(cfg.batch, InSize)
	ybuf := make([]int, cfg.batch)
	grad := make([]float32, len(params.Data))

	t0 := time.Now()
	var last Stats
	for e := 1; e <= cfg.epochs; e++ {
		te := time.Now()
		var st Stats
		for start := 0; start < images; start += cfg.batch {
			n := minInt(cfg.batch, images-start)
			x := xbuf[:n]
			y := ybuf[:n]
			for i := 0; i < n; i++ {
				Normalize(train.Pixels(start+i), x[i])
				y[i] = train.Label(start + i)
			}
			for i := range grad {
				grad[i] = 0
			}
			st.Add(cnn.ForwardBackward(params, x, y, grad))
			sgd.Step(params, grad, n)
		}
		sec := time.Since(te).Seconds()
		fmt.Printf("  epoca %d: %.2f s, %.1f img/s, perdida %.4f, acierto entrenamiento %.2f%%\n",
			e, sec, float64(images)/sec, st.LossSum/float64(st.Count), 100*float64(st.Correct)/float64(st.Count))
		last = st
	}
	trainSec := time.Since(t0).Seconds()

	testAcc := math.NaN()
	if testImages > 0 {
		testAcc = evaluate(cnn, params, test, testImages, 256)
		fmt.Printf("  acierto en prueba (%d imagenes): %.2f%%\n", testImages, 100*testAcc)
	}

	if cfg.savePath != "" {
		if err := params.Save(cfg.savePath); err != nil {
			fatal(err)
		}
		fmt.Println("  pesos guardados en " + cfg.savePath)
	}
	if cfg.csvPath != "" {
		label := cfg.label
		if label == "" {
			if cfg.threads == 1 {
				label = "secuencial"
			} else {
				label = "paralelo"
			}
		}
		if err := appendCsv(cfg.csvPath, label, cfg.threads, images, cfg.epochs, cfg.batch, trainSec, last, testAcc); err != nil {
			fatal(err)
		}
	}
	fmt.Printf("TOTAL %.3f s\n", trainSec)
}

// evaluate devuelve la fracción de aciertos sobre las primeras count imágenes de ds.
func evaluate(cnn *Cnn, params *Params, ds *Dataset, count, batch int) float64 {
	correct := 0
	for start := 0; start < count; start += batch {
		n := minInt(batch, count-start)
		x := make([][]float32, n)
		for i := range x {
			x[i] = ds.Image(start + i)
		}
		probs := cnn.Predict(params, x)
		for i := range probs {
			if Argmax(probs[i]) == ds.Label(start+i) {
				correct++
			}
		}
	}
	return float64(correct) / float64(count)
}

func appendCsv(path, label string, threads, images, epochs, batch int, sec float64, st Stats, testAcc float64) error {
	_, statErr := os.Stat(path)
	header := os.IsNotExist(statErr)
	if dir := filepath.Dir(path); dir != "" {
		if err := os.MkdirAll(dir, 0o755); err != nil {
			return err
		}
	}
	f, err := os.OpenFile(path, os.O_APPEND|os.O_CREATE|os.O_WRONLY, 0o644)
	if err != nil {
		return err
	}
	if header {
		fmt.Fprintln(f, "escenario,lenguaje,maquinas,hilos,imagenes,epocas,lote,segundos,img_por_s,perdida,acierto_train,acierto_test")
	}
	fmt.Fprintf(f, "%s,go,1,%d,%d,%d,%d,%.3f,%.2f,%.4f,%.4f,%.4f\n", label, threads, images, epochs, batch,
		sec, float64(images)*float64(epochs)/sec, st.LossSum/float64(st.Count),
		float64(st.Correct)/float64(st.Count), testAcc)
	return f.Close()
}
