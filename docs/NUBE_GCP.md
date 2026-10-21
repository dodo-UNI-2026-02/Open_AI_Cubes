# Servidor en la nube con Google Cloud (Compute Engine)

El enunciado pide como **opcional** "desarrollar el servidor en internet o en la nube". Esta es la
opción elegida por el equipo: **Google Cloud**. Azure for Students no reconoció la cuenta `uni.pe`
y la cuenta de AWS quedó bloqueada; en cualquier otra nube los pasos son equivalentes (crear una
VM Ubuntu, abrir TCP 5000 y 6000 y correr `scripts/nube_preparar_vm.sh`).

**Solo el servidor central va a la nube**, en una VM de Compute Engine. Los **nodos son nuestras
laptops físicas**, una por integrante y cada una con su lenguaje. Las laptops inician la conexión
hacia el servidor, así que pueden estar en casas distintas y no hay que abrir puertos en sus
routers ni en el firewall de Windows.

```
                   Internet
   ┌──────────────────────────────────────────┐
   │  VM en Google Cloud (Ubuntu, IP estática)│
   │  CentralServer  :5000 clientes           │
   │                 :6000 nodos              │
   └─────▲──────────────▲──────────────▲──────┘
   ┌─────┴──────┐ ┌─────┴──────┐ ┌─────┴──────┐  ┌──────────────┐
   │ Laptop 1   │ │ Laptop 2   │ │ Laptop 3   │  │ Cualquier PC │
   │ nodo Java  │ │ nodo Go    │ │ nodo Python│  │ chat / carga │
   └────────────┘ └────────────┘ └────────────┘  └──────────────┘
```

Lo más cómodo de Google Cloud: se entra al servidor con el **botón "SSH" de la consola**, que abre
la terminal en el navegador. No hay archivos de claves que manejar.

## Comandos rápidos (día a día)

Servidor del equipo: VM `oac-servidor` en `southamerica-west1-a` (Santiago), IP fija **34.176.215.27**.
Santiago tiene menos latencia desde Lima, pero **no entra en Always Free**: mientras está encendida
cobra la VM, y la IP fija cobra siempre. Las secciones 1 y 7 describen la alternativa gratuita en
`us-east1`; para nuestra VM, en los comandos `gcloud` va `--zone=southamerica-west1-a`. La VM del
equipo es `e2-micro` con Ubuntu 24.04 LTS (la guía de abajo usa 22.04; cualquiera de las dos sirve).

| Qué | Dónde | Comando |
|---|---|---|
| Encender | Consola → Instancias de VM | ⋮ → *Iniciar/Reanudar* |
| Entrar | Consola → Instancias de VM | Botón **SSH** (si entra con otro usuario: `sudo -iu dodo`) |
| Arrancar el servidor | SSH | `cd ~/Open_AI_Cubes && tmux new -s oac` y luego `JAVA_MEM=-Xmx600m scripts/servidor.sh` |
| **Salir dejando el servidor trabajando** | SSH, dentro de tmux | **`Ctrl+B`**, soltar, y luego **`D`** (*detach*). Aparece `[detached (from session oac)]`; ya se puede cerrar la ventana o apagar la PC |
| **Volver a la consola del servidor** | SSH | **`tmux attach -t oac`** (ahí se escribe `estado`, `verbose` o `salir`) |
| ¿Ya está corriendo? | SSH | `tmux ls` (si aparece `oac`, no arrancarlo otra vez: daría `Address already in use`) |
| Actualizar el código | SSH, con el servidor detenido | `git pull && bash scripts/compilar.sh` |
| Probar el puerto | Laptop (PowerShell) | `Test-NetConnection 34.176.215.27 -Port 6000` |
| Nodo | Laptop | `scripts\nodo_java.bat 34.176.215.27 4` (o `nodo_go`, `nodo_python`) |
| Chat | Laptop | `scripts\cliente.bat 34.176.215.27` |
| Estado | Laptop | `java -cp out cubes.client.AdminClient --host 34.176.215.27 estado` |
| Apagar | Consola → Instancias de VM | ⋮ → **Detener** (al terminar cada sesión) |
| **Día de la presentación** | Red de VPC → Firewall → `permitir-open-ai-cubes` → Editar | Agregar `0.0.0.0/0` a los rangos de origen **antes** de exponer y **quitarlo al terminar** (ver README, "Día de la presentación") |

> **tmux en dos líneas:** `Ctrl+B` y luego `D` = salir sin apagar el servidor (sigue trabajando
> en la nube). `tmux attach -t oac` = volver a ver su consola. **No** usar `Ctrl+C` ni `salir`
> si se quiere que siga funcionando: eso sí lo apaga.

Probado el 20 de octubre de 2026: servidor en Santiago, nodo Java en una laptop en Lima y el chat
identificando una foto de gato con 100 % de confianza. La primera consulta tardó ~3 s porque el
nodo recibe primero los pesos del modelo (~1 MB) y Java calienta; las siguientes son mucho más
rápidas.

## 0. Cuenta y prueba gratuita (una sola para todo el equipo)

1. Entrar a https://console.cloud.google.com con una **cuenta de Gmail personal**. Las cuentas
   `@uni.pe` suelen tener Google Cloud desactivado por la universidad.
2. Hay dos situaciones según la cuenta:
   - **Cuenta nueva:** se acepta la **prueba gratuita** (300 USD por 90 días). Pide tarjeta, pero
     durante la prueba no cobra.
   - **Cuenta completa (de pago) o créditos vencidos:** es nuestro caso. Igual se puede usar la
     capa **Always Free**, que no vence: 1 VM `e2-micro` al mes en `us-east1`, `us-central1` o
     `us-west1`, 30 GB de disco estándar y 1 GB de salida al mes. Lo que pase de eso **se cobra a
     la tarjeta**, así que hay que respetar región, tipo y disco, y crear la alerta de abajo.
3. Usar el proyecto que ya exista (el selector de arriba muestra su ID) o crear uno nuevo
   (*Nuevo proyecto* → `open-ai-cubes`).
4. Menú ☰ → **Compute Engine → VM instances**. La primera vez pide *Habilitar* la API de
   Compute Engine: aceptar y esperar 1 a 2 minutos.

> **Alerta de gasto (obligatorio con cuenta de pago):** ☰ → *Facturación → Presupuestos y
> alertas → Crear presupuesto*, de S/ 10 (o 3 USD) con aviso al 50 % y al 100 %.

Si la VM se crea en una región de Always Free (sección 1), la VM y el disco no cuestan; la nuestra
está en Santiago y sí cobra mientras está encendida. Lo único que puede cobrar es la **IP pública**
(Google cobra las IPv4 externas, unos 0,12 USD al día encendida) y el tráfico si pasa de 1 GB al
mes. Por eso se **detiene** la VM cuando no se usa, y el entrenamiento se hace en red local.

## 1. Crear el servidor

Compute Engine → VM instances → **Crear instancia**:

| Campo | Valor |
|---|---|
| Nombre | `oac-servidor` |
| Región / zona | **`us-east1` (Carolina del Sur)**, zona `us-east1-b`: la región gratuita más cercana a Lima (~80–100 ms). Con créditos vigentes se puede usar `southamerica-west1` (Santiago), que tiene menos latencia |
| Configuración de máquina | Serie **E2**, tipo **`e2-micro`** (2 vCPU compartidas, 1 GB). Es el único tipo gratis; `e2-small` ya cobra |
| Disco de arranque → *Cambiar* | **Ubuntu**, versión **Ubuntu 22.04 LTS (x86/64)**, tipo **Disco persistente estándar** (no "balanceado", que cobra) de 10 GB |
| Redes → Etiquetas de red | `oac-servidor` (la regla de firewall del paso 2 se aplica por esta etiqueta) |
| Redes → Interfaz de red → IP externa | *Reservar dirección IP externa estática* → nombre `oac-ip` → *Reservar* |

*Crear*. En unos 30 segundos aparece con un ✔ verde y su **IP externa**: esa es `<IP>`, y no
cambia aunque se detenga la VM.

> Si en el resumen de la derecha aparece un costo mensual estimado, es el precio de lista: el
> descuento de Always Free se aplica después, en la factura. Revisar con la alerta de gasto.

## 2. Abrir los puertos 5000 y 6000 (firewall)

☰ → **Red de VPC → Firewall** → **Crear regla de firewall**:

| Campo | Valor |
|---|---|
| Nombre | `permitir-open-ai-cubes` |
| Dirección | Entrada |
| Acción | Permitir |
| Destinos | *Etiquetas de destino especificadas* → `oac-servidor` |
| Filtro de origen | Rangos IPv4: la IP pública de **cada integrante** con `/32` (por ejemplo `190.12.34.56/32`) |
| Protocolos y puertos | *Protocolos y puertos especificados* → TCP: `5000,6000` |

- Cada integrante ve su IP en https://api.ipify.org desde su casa. El día de la exposición se
  agrega `0.0.0.0/0` y se quita al terminar (ver README, "Día de la presentación").
- Las IP de casa pueden cambiar; si un nodo de repente no conecta, revisar esta regla primero.
- Para una prueba corta se puede usar `0.0.0.0/0` (cualquiera) y quitarlo después. El servidor no
  tiene cifrado, así que no conviene dejarlo abierto.
- El SSH (puerto 22) ya viene abierto por la regla `default-allow-ssh` de la red *default*.

## 3. Entrar y preparar el servidor

En la lista de VM, en la fila de `oac-servidor`, pulsar **SSH**. Se abre una terminal en el
navegador (la primera vez tarda unos segundos en crear la clave). Ahí:

```bash
curl -fsSL https://raw.githubusercontent.com/dodo-UNI-2026-02/Open_AI_Cubes/main/scripts/nube_preparar_vm.sh | bash
```

El script instala el JDK 17, `unzip`, `git` y `tmux`; agrega 1 GB de swap; clona el repo en
`~/Open_AI_Cubes`; baja `train.bin` del Release v1.0 y compila. Al final imprime los comandos con
la IP ya puesta.

## 4. Arrancar el servidor

```bash
cd ~/Open_AI_Cubes
tmux new -s oac
JAVA_MEM=-Xmx600m scripts/servidor.sh       # consola: estado | verbose | salir
```

- `tmux` mantiene el servidor vivo aunque se cierre la ventana SSH del navegador. Para salir sin
  apagarlo: `Ctrl+B` y luego `D`. Para volver: botón SSH otra vez y `tmux attach -t oac`.
- Si se cambió el código y se subió a GitHub: `git pull && bash scripts/compilar.sh` y volver a
  arrancar.

## 5. Conectar las laptops (Windows)

Desde la carpeta del proyecto, o del kit (`scripts/empaquetar.sh kit`):

```powershell
Test-NetConnection <IP> -Port 6000          # debe decir TcpTestSucceeded : True
scripts\nodo_java.bat   <IP> 4               # laptop 1
scripts\nodo_go.bat     <IP> 4               # laptop 2
scripts\nodo_python.bat <IP> 4               # laptop 3
scripts\cliente.bat     <IP>                 # el chat, desde cualquiera
java -cp out cubes.client.AdminClient --host <IP> estado
```

`estado` debe listar los 3 nodos, cada uno con el nombre de su laptop. Si `Test-NetConnection`
falla, casi siempre falta la IP de esa persona en la regla de firewall del paso 2.

## 6. Apagar y no gastar

- **Al terminar cada sesión:** VM instances → `oac-servidor` → ⋮ → **Detener**. Detenida no cobra
  CPU ni IP efímera; la IP **estática** reservada sí cobra algo detenida (unos 0,24 USD al día). Si se quiere gasto cero, usar IP efímera (cambia al reiniciar) o liberar la estática al terminar el proyecto.
- **Para volver:** ⋮ → **Iniciar/Reanudar**, botón SSH y repetir el paso 4. La IP es la misma.
- **Al cerrar el ciclo:** borrar el proyecto completo (☰ → *IAM y administración → Administrar
  recursos* → marcar `open-ai-cubes` → *Borrar*). Así no queda nada cobrando.

## 7. Lo mismo por comandos (Cloud Shell)

El ícono `>_` de arriba a la derecha abre **Cloud Shell**, que ya trae `gcloud`:

```bash
gcloud config set project <ID_PROYECTO>                  # ver con: gcloud projects list
gcloud compute addresses create oac-ip --region=us-east1
gcloud compute instances create oac-servidor --zone=us-east1-b \
  --machine-type=e2-micro --image-family=ubuntu-2204-lts --image-project=ubuntu-os-cloud \
  --boot-disk-size=10GB --boot-disk-type=pd-standard --tags=oac-servidor --address=oac-ip
gcloud compute firewall-rules create permitir-open-ai-cubes --direction=INGRESS \
  --allow=tcp:5000,tcp:6000 --target-tags=oac-servidor \
  --source-ranges=<IP_1>/32,<IP_2>/32,<IP_3>/32
# Nuestra VM está en Santiago: en los comandos que siguen, usar --zone=southamerica-west1-a
gcloud compute instances describe oac-servidor --zone=us-east1-b \
  --format="get(networkInterfaces[0].accessConfigs[0].natIP)"
gcloud compute ssh oac-servidor --zone=us-east1-b      # entrar por SSH
gcloud compute instances stop oac-servidor --zone=us-east1-b
```

## 8. Qué medir para el informe

1. **Prueba de carga, red local vs nube.** Con las mismas 3 laptops, primero con el servidor en
   una laptop y luego con el servidor en Google Cloud:
   ```bash
   scripts/carga.sh <IP_LAPTOP_SERVIDOR> "Red local 3 laptops" 10,50,100,200,400
   scripts/carga.sh <IP>                 "Google Cloud 3 laptops" 10,50,100,200,400
   scripts/graficas.sh
   ```
   La diferencia de latencia p50 en `carga_latencia.png` es el viaje de ida y vuelta Lima ↔
   Santiago (Carolina del Sur si se usa la opción gratuita de `us-east1`). En `carga_throughput.png` se ve dónde se satura el servidor chico de la nube.
2. **Entrenamiento:** por internet conviene `BATCH=256`, porque cada lote manda ~1 MB de pesos a
   cada laptop. Se compara en `costo_comunicacion.png`. Lo recomendado es entrenar en red local y
   dejar la nube para el chat y la carga.

Para la exposición conviene tener capturas de la VM corriendo y de la regla de firewall, como
evidencia de que el servidor está en la nube.

## 9. Problemas comunes

| Síntoma | Solución |
|---|---|
| "Compute Engine API no habilitada" | Paso 0.4: habilitarla y esperar 1 a 2 minutos |
| No deja crear la VM por cuota o región | Probar otra zona de `us-east1` (`-c`, `-d`) o `us-central1` |
| El nodo no conecta a 6000 | Falta la IP de esa laptop en la regla del paso 2, la etiqueta `oac-servidor` no está en la VM, o la red bloquea el puerto (probar con el hotspot del celular) |
| La red de la UNI bloquea 5000/6000 | Hotspot del celular, o en la VM `sudo iptables -t nat -A PREROUTING -p tcp --dport 443 -j REDIRECT --to-port 5000` y `... --dport 80 ... --to-port 6000`, abrir `tcp:80,443` en la regla y conectar con `--port 443` (clientes) y `--port 80` (nodos). Los scripts `nodo_*` fijan el puerto 6000, así que el nodo se lanza directo (por ejemplo `java -cp out cubes.node.JavaNode --server <IP> --port 80`) |
| El botón SSH no conecta | Esperar a que la VM termine de arrancar; revisar que exista la regla `default-allow-ssh` |
| `OutOfMemoryError` o servidor lento | Usar `JAVA_MEM=-Xmx600m`, confirmar el swap con `free -h`, o, si hace falta, `e2-small` (cobra unos 0,02 USD/h; con la VM detenida: *Editar → Tipo de máquina*) |
