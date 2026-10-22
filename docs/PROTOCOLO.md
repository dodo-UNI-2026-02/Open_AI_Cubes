# Protocolo OC/1 (Open AI Cubes)

Protocolo binario propio sobre TCP, implementado solo con los sockets estándar de cada lenguaje
(Java `java.net.Socket`, Python `socket`, Go `net`). No se usa HTTP, WebSocket, Socket.IO ni middleware.

## Puertos

| Puerto | Quién se conecta | Uso |
|---|---|---|
| 5000 | clientes (chat Swing, generador de carga, consola admin) | consultas de clientes |
| 6000 | nodos de cómputo | trabajo de la CNN |

Siempre es el cliente o el nodo quien abre la conexión hacia el servidor central. El servidor no
necesita conocer de antemano las IP de los nodos.

## Trama

Todos los enteros y floats son **big-endian**. Cabecera de 12 bytes:

```
+--------+--------+---------+------+------------+-----------+-------------------+
| 'O'    | 'C'    | versión | tipo | id petición | longitud  | payload           |
| 1 byte | 1 byte | 1 byte  | 1 B  | int32      | int32     | <longitud> bytes  |
+--------+--------+---------+------+------------+-----------+-------------------+
```

- versión = 1. Longitud máxima del payload: 64 MiB.
- El id de petición lo elige quien pregunta; la respuesta repite el mismo id.
- Mensajes sin respuesta usan id 0.

Tipos de dato dentro del payload:

| Tipo | Codificación |
|---|---|
| u8 | 1 byte sin signo |
| i32 | 4 bytes con signo |
| f32 | IEEE-754 de 4 bytes |
| f64 | IEEE-754 de 8 bytes |
| str | u16 longitud en bytes + UTF-8 |
| f32[] | i32 cantidad + cantidad × f32 |
| pixeles | n × 3072 bytes en formato CIFAR (planos R, G, B) |

## Mensajes nodo ↔ servidor (puerto 6000)

| Código | Nombre | Dirección | Payload |
|---|---|---|---|
| 0x01 | REGISTER | nodo → srv | str lenguaje, i32 núcleos, i32 hilos, str hostname |
| 0x02 | REGISTER_OK | srv → nodo | i32 nodeId |
| 0x03 | HEARTBEAT | nodo → srv | i32 tareas pendientes (Go y Python envían su cola; Java envía 0). Cada 2 s, id 0, sin respuesta. El servidor solo lo usa como señal de vida |
| 0x04 | BYE | ambos | vacío; luego se cierra la conexión |
| 0x10 | SET_WEIGHTS | srv → nodo | u8 slot, i32 versión, f32[] pesos (sin respuesta) |
| 0x11 | TRAIN_TASK | srv → nodo | u8 slot, i32 versión, i32 n, n bytes etiquetas, pixeles |
| 0x12 | GRADIENTS | nodo → srv | i32 versión, i32 n, f64 suma pérdidas, i32 aciertos, i32 ms de cómputo, f32[] suma de gradientes |
| 0x20 | INFER_TASK | srv → nodo | u8 slot, i32 versión, i32 n, pixeles |
| 0x21 | INFER_RESULT | nodo → srv | i32 n, i32 K, n×K f32 probabilidades, i32 ms de cómputo |
| 0x7F | ERROR | ambos | str mensaje (responde con el id de la petición que falló) |

- Slot 0 = pesos "de servicio" (los que usa el chat). Slot 1 = pesos de entrenamiento.
- El nodo procesa los mensajes en el orden en que llegan (un SET_WEIGHTS siempre llega antes
  que la tarea que lo usa, porque TCP mantiene el orden).
- Si la versión de una tarea no coincide con la versión guardada en ese slot, el nodo responde
  ERROR `"version"`.
- El servidor considera muerto a un nodo si no recibe nada de él en 10 s o si se cierra el
  socket; sus tareas pendientes se reasignan a otro nodo.

## Mensajes cliente ↔ servidor (puerto 5000)

| Código | Nombre | Dirección | Payload |
|---|---|---|---|
| 0x40 | LOGIN | cli → srv | str usuario |
| 0x41 | LOGIN_OK | srv → cli | i32 idClientChat, i32 K, K × str nombre de clase |
| 0x42 | IDENTIFY | cli → srv | u8 formato (0 = pixeles crudos 3072 bytes, 1 = archivo JPG/PNG), i32 longitud, bytes |
| 0x43 | RESULT | srv → cli | i32 clase, str etiqueta, f32 confianza, i32 nodeId, str lenguaje del nodo, i32 ms en servidor |
| 0x44 | HISTORY_REQ | cli → srv | vacío |
| 0x45 | HISTORY | srv → cli | i32 cantidad, cantidad × str |
| 0x46 | IDENTIFY_BATCH | cli → srv | i32 n (1 a 5000), n × 3072 bytes de pixeles crudos (el cliente reduce cada imagen a 32×32) |
| 0x47 | RESULT_ITEM | srv → cli | i32 índice, u8 ok; si ok: i32 clase, str etiqueta, f32 confianza, i32 nodeId, str lenguaje, i32 ms; si no: str error |
| 0x48 | BATCH_DONE | srv → cli | i32 n, i32 respondidas sin error, i32 ms totales en servidor |
| 0x50 | STATUS_REQ | cli → srv | vacío |
| 0x51 | STATUS | srv → cli | str texto con nodos, cola y contadores |
| 0x52 | TRAIN_REQ | cli → srv | i32 imágenes, i32 épocas, i32 lote, f32 lr, str filtro de lenguajes ("all" o "java,go"), i32 máx. nodos (0 = todos), u8 publicar (1 = usar el resultado para el chat), str etiqueta |
| 0x53 | TRAIN_PROGRESS | srv → cli | str línea de progreso |
| 0x54 | TRAIN_DONE | srv → cli | f64 segundos, f64 pérdida media, f64 acierto, i32 nodos usados, str detalle por nodo |
| 0x55 | CROSSCHECK_REQ | cli → srv | vacío |
| 0x56 | CROSSCHECK | srv → cli | str reporte |
| 0x04 | BYE | cli → srv | vacío; el cliente se despide y se cierra la conexión |
| 0x7F | ERROR | srv → cli | str mensaje |

Antes de LOGIN solo se aceptan LOGIN, STATUS_REQ, TRAIN_REQ y CROSSCHECK_REQ.
