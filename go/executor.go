package main

// Ejecutor de micro chunks (equivale a cubes.cnn.ChunkExecutor en Java).
//
// Un micro chunk es un rango [from, to) de trabajo independiente (filtros de una
// convolución, neuronas de una capa densa, canales de un pooling). ForRange reparte
// el rango completo entre H goroutines y espera a que todas terminen: esa espera
// (sync.WaitGroup) es la barrera entre capas, porque la capa siguiente necesita el
// resultado completo de la anterior.
//
// Con H = 1 todo corre en la goroutine que llama (modo secuencial).

import (
	"fmt"
	"sync"
)

// RangeTask procesa el micro chunk [from, to).
type RangeTask func(from, to int)

type ChunkExecutor struct {
	threads int
}

func NewChunkExecutor(threads int) *ChunkExecutor {
	if threads < 1 {
		threads = 1
	}
	return &ChunkExecutor{threads: threads}
}

// ForRange divide [0, n) en hasta H micro chunks contiguos, lanza una goroutine por
// chunk y espera a todas (barrera). Si un chunk entra en pánico, el pánico se repite
// en la goroutine que llamó, igual que Java relanza la excepción de f.get().
func (ex *ChunkExecutor) ForRange(n int, task RangeTask) {
	if n <= 0 {
		return
	}
	if ex.threads == 1 || n == 1 {
		task(0, n)
		return
	}
	parts := ex.threads
	if n < parts {
		parts = n
	}
	var wg sync.WaitGroup
	var mu sync.Mutex
	var failure interface{}
	wg.Add(parts)
	for p := 0; p < parts; p++ {
		from := n * p / parts
		to := n * (p + 1) / parts
		go func(from, to int) {
			defer wg.Done()
			defer func() {
				if r := recover(); r != nil {
					mu.Lock()
					if failure == nil {
						failure = r
					}
					mu.Unlock()
				}
			}()
			task(from, to)
		}(from, to)
	}
	wg.Wait() // barrera: la capa siguiente necesita todos los chunks terminados
	if failure != nil {
		panic(fmt.Sprintf("micro chunk fallo: %v", failure))
	}
}
