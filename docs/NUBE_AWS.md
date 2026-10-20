# Servidor en la nube con AWS (EC2)

El enunciado pide como **opcional** "desarrollar el servidor en internet o en la nube". Esta guía
lo hace en **AWS**, con una instancia EC2 de la capa gratuita. El equipo eligió finalmente
[Google Cloud](NUBE_GCP.md); esta guía queda como alternativa.

La idea es la misma que en [NUBE_AZURE.md](NUBE_AZURE.md): **solo el servidor central va a la
nube**, en una instancia EC2 (la "computadora" de AWS). Los **nodos son nuestras laptops
físicas**, una por integrante y cada una con su lenguaje. Las laptops inician la conexión hacia el
servidor, así que pueden estar en casas distintas y no hay que abrir puertos en sus routers.

```
                   Internet
   ┌──────────────────────────────────────────┐
   │  EC2 en AWS (Ubuntu, IP elástica)        │
   │  CentralServer  :5000 clientes           │
   │                 :6000 nodos              │
   └─────▲──────────────▲──────────────▲──────┘
   ┌─────┴──────┐ ┌─────┴──────┐ ┌─────┴──────┐  ┌──────────────┐
   │ Laptop 1   │ │ Laptop 2   │ │ Laptop 3   │  │ Cualquier PC │
   │ nodo Java  │ │ nodo Go    │ │ nodo Python│  │ chat / carga │
   └────────────┘ └────────────┘ └────────────┘  └──────────────┘
```

## 0. Crear la cuenta (una sola para todo el equipo)

1. Entrar a https://aws.amazon.com/free y elegir *Crear una cuenta gratuita*. Con correo personal
   o de la UNI.
2. AWS pide **tarjeta de débito o crédito** para verificar la identidad. Hace un cargo temporal de
   ~1 USD que luego devuelve. Si se usa solo lo gratuito, no cobra nada más.
3. Verificación por SMS o llamada y plan de soporte **Básico (gratis)**.
4. En la consola, arriba a la derecha, elegir la región **América del Sur (São Paulo)
   `sa-east-1`**, que es la más cercana a Lima. Todo lo que sigue se crea en esa región.

> **Plan gratuito con créditos:** las cuentas nuevas reciben 100 USD en créditos (hasta 200 si se
> completan las tareas de bienvenida) por 6 meses. Mientras la cuenta esté en el *plan gratuito*
> no se cobra a la tarjeta: al acabarse los créditos o el plazo, los recursos se detienen. Una
> t3.micro encendida 24 h, con su IP pública, gasta alrededor de 0,5 USD al día en São Paulo, así que alcanza de sobra si se
> **detiene** cuando no se usa.
>
> **Igual conviene crear una alerta de gasto:** *Billing and Cost Management → Budgets → Create
> budget → plantilla "Zero spend budget"* (o un presupuesto mensual de 1 USD) con tu correo. Si
> algo empieza a cobrar, llega un aviso.

## 1. Crear la instancia EC2

Consola → **EC2** → **Launch instance**:

| Campo | Valor |
|---|---|
| Nombre | `oac-servidor` |
| Imagen (AMI) | **Ubuntu Server 22.04 LTS** (o 24.04), arquitectura **64-bit (x86)**, marcada *Free tier eligible* |
| Tipo de instancia | La que diga **Free tier eligible**: `t3.micro` o `t2.micro` (2 vCPU / 1 GB o 1 vCPU / 1 GB) |
| Key pair | *Create new key pair* → nombre `oac-clave`, tipo RSA, formato **.pem** → se descarga. Guardarlo **fuera del repo** (por ejemplo en `Documentos\aws\`) |
| Network settings → *Edit* | Auto-assign public IP: **Enable**. Security group nuevo: `oac-sg` |
| Almacenamiento | 8 GB gp3 (lo que viene; la capa gratuita cubre hasta 30 GB) |

En **Network settings → Inbound security group rules**, dejar estas reglas:

| Tipo | Protocolo | Puerto | Origen | Para qué |
|---|---|---|---|---|
| SSH | TCP | 22 | **My IP** | Entrar a administrar |
| Custom TCP | TCP | **5000** | IP de cada integrante (`x.x.x.x/32`) | Chat, carga y consola admin |
| Custom TCP | TCP | **6000** | IP de cada integrante (`x.x.x.x/32`) | Nodos |

- Cada integrante ve su IP pública en https://checkip.amazonaws.com, desde su casa.
- Se agrega una regla por IP; el día de la exposición se suma la IP de la red de la UNI.
- Para una prueba corta se puede usar `0.0.0.0/0` (cualquiera) y quitarlo después. El servidor
  no tiene cifrado, así que no conviene dejarlo abierto.

*Launch instance*. A los ~30 s queda en estado **Running**.

## 2. Darle una IP fija (Elastic IP)

Sin esto, la IP pública **cambia cada vez que se detiene y arranca** la instancia, y habría que
avisarles a todos la nueva IP.

EC2 → **Elastic IPs** → *Allocate Elastic IP address* → *Allocate*. Luego *Actions → Associate
Elastic IP address* → elegir la instancia `oac-servidor` → *Associate*. Esa es la IP del
servidor para siempre: la llamamos `<IP>`.

> AWS cobra las IP públicas (~0,005 USD/h). La capa gratuita y los créditos de cuenta nueva lo
> cubren mientras la instancia esté encendida. Al cerrar el ciclo hay que **liberar** la Elastic
> IP (*Release*), porque una IP sin usar sí cobra.

## 3. Entrar por SSH desde Windows

En PowerShell, primero se le quitan permisos al `.pem`. SSH se niega a usar una clave que otros
usuarios puedan leer:

```powershell
$k = "$env:USERPROFILE\Documents\aws\oac-clave.pem"
icacls $k /inheritance:r
icacls $k /grant:r "$($env:USERNAME):(R)"
ssh -i $k ubuntu@<IP>
```

El usuario de las imágenes Ubuntu en AWS es **`ubuntu`**. La primera vez pregunta si confías en el
host: escribir `yes`.

## 4. Preparar el servidor (una sola vez)

Ya dentro de la instancia:

```bash
curl -fsSL https://raw.githubusercontent.com/dodo-UNI-2026-02/Open_AI_Cubes/main/scripts/nube_preparar_vm.sh | bash
```

El script hace todo esto: instala el JDK 17, `unzip`, `git` y `tmux`; agrega 1 GB de swap
(la instancia tiene solo 1 GB de RAM); clona el repo en `~/Open_AI_Cubes`; baja `train.bin` del
Release v1.0 y compila. Al final imprime los comandos con la IP ya puesta.

## 5. Arrancar el servidor

```bash
cd ~/Open_AI_Cubes
tmux new -s oac
JAVA_MEM=-Xmx600m scripts/servidor.sh       # consola: estado | verbose | salir
```

- `tmux` mantiene el servidor vivo aunque se cierre la ventana de SSH. Para salir sin apagarlo:
  `Ctrl+B` y luego `D`. Para volver a la consola: `ssh ...` y `tmux attach -t oac`.
- Si se cambió algo del código y se subió a GitHub:
  `git pull && bash scripts/compilar.sh` y volver a arrancar.

## 6. Conectar las laptops (Windows)

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
falla, casi siempre falta la IP de esa persona en el security group (paso 1) o la IP de su casa
cambió: *EC2 → Security Groups → oac-sg → Edit inbound rules*.

## 7. Apagar y no gastar

- **Al terminar cada sesión de pruebas:** EC2 → Instances → `oac-servidor` → *Instance state →
  Stop*. Detenida no cobra cómputo, solo el disco (cubierto por la capa gratuita). La Elastic IP
  se mantiene.
- **Para volver:** *Start*, entrar por SSH y repetir el paso 5.
- **Al cerrar el ciclo:** *Terminate* la instancia, **Release** la Elastic IP y borrar el
  security group y el key pair.
- Revisar de vez en cuando *Billing → Free tier* para ver cuánto de la capa gratuita se lleva
  usado.

## 8. Qué medir para el informe

Es lo mismo que en la guía de Azure (sección "Qué medir"):

1. **Prueba de carga, red local vs nube.** Con las mismas 3 laptops, primero con el servidor en
   una laptop y luego con el servidor en AWS:
   ```bash
   scripts/carga.sh <IP_LAPTOP_SERVIDOR> "Red local 3 laptops" 10,50,100,200,400
   scripts/carga.sh <IP>                 "AWS 3 laptops"       10,50,100,200,400
   scripts/graficas.sh
   ```
   La diferencia de latencia p50 en `carga_latencia.png` es el viaje de ida y vuelta Lima ↔ São
   Paulo. En `carga_throughput.png` se ve dónde se satura el servidor chico de la nube.
2. **Entrenamiento:** por internet conviene `BATCH=256`, porque cada lote manda ~1 MB de pesos a
   cada laptop. Se compara en `costo_comunicacion.png`. Lo recomendado es entrenar en red local y
   dejar la nube para el chat y la carga.

Para la exposición conviene tener una captura de la consola de EC2 con la instancia *Running* y
otra del security group: sirven de evidencia de que el servidor está en la nube.

## 9. Problemas comunes

| Síntoma | Solución |
|---|---|
| `WARNING: UNPROTECTED PRIVATE KEY FILE` / `Permission denied (publickey)` | Correr los `icacls` del paso 3. Usuario `ubuntu`, no `ec2-user` ni `root` |
| SSH no responde | La regla de SSH es "My IP" y tu IP cambió: editar la regla de entrada 22 |
| El nodo no conecta a 6000 | Falta la IP de esa laptop en `oac-sg`, o la red bloquea el puerto (probar con el hotspot del celular) |
| La red de la UNI bloquea 5000/6000 | Hotspot del celular, o en la instancia `sudo iptables -t nat -A PREROUTING -p tcp --dport 443 -j REDIRECT --to-port 5000` y `... --dport 80 ... --to-port 6000`, abrir 80/443 en `oac-sg` y conectar con `--port 443` (clientes) y `--port 80` (nodos) |
| El servidor se cae con `OutOfMemoryError` o queda lento | Usar `JAVA_MEM=-Xmx600m` y confirmar el swap con `free -h` |
| Cambió la IP del servidor | Falta la Elastic IP (paso 2) |
