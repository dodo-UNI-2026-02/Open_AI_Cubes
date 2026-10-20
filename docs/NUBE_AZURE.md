# Servidor en la nube con Azure for Students

El enunciado pide como **opcional** "desarrollar el servidor en internet o en la nube". Aquí está
cómo hacerlo con **Azure for Students**: 100 USD de crédito y sin tarjeta de crédito. Se activa con
el correo institucional de la UNI en https://azure.microsoft.com/free/students. El equipo eligió
finalmente [Google Cloud](NUBE_GCP.md) (Azure no reconoció la cuenta `uni.pe`); esta guía queda
como alternativa.

## Idea

Lo único que va a la nube es el **servidor central**. En Azure, una computadora en la nube se
llama *máquina virtual* (VM): es la única VM del sistema. Los **nodos de cómputo son nuestras
laptops físicas**, una por integrante, y cada una corre el nodo de su lenguaje.

```
                         Internet
     ┌───────────────────────────────────────────┐
     │  Servidor en Azure (Ubuntu, IP pública)   │
     │  CentralServer  :5000 clientes            │
     │                 :6000 nodos               │
     └─────▲──────────────▲──────────────▲───────┘
           │ TCP saliente │              │
   ┌───────┴──────┐ ┌─────┴────────┐ ┌───┴──────────┐   ┌──────────────┐
   │ Laptop 1     │ │ Laptop 2     │ │ Laptop 3     │   │ Cualquier PC │
   │ nodo Java    │ │ nodo Go      │ │ nodo Python  │   │ chat / carga │
   │ (casa o UNI) │ │ (casa o UNI) │ │ (casa o UNI) │   │              │
   └──────────────┘ └──────────────┘ └──────────────┘   └──────────────┘
```

- **Las laptops no necesitan estar en la misma red.** Cada integrante puede conectar su nodo
  desde su casa, porque los nodos y los clientes *inician* la conexión hacia el servidor. No hay
  que abrir puertos en el router ni en el firewall de Windows: basta con poder salir a internet
  por los puertos 5000 y 6000.
- Esa es la ventaja frente a poner el servidor en una de las laptops. Ahí todas tienen que estar
  en la misma red local, y el WiFi de la universidad suele aislar a los equipos entre sí.
- El protocolo es el mismo (OC/1 sobre TCP, sin librerías externas). No se usa VPN ni middleware
  de terceros, que el enunciado prohíbe.
- Si no hay nodos conectados, el servidor responde las consultas él solo, así que el chat
  funciona aunque se desconecte alguna laptop.

## Qué tamaño de servidor usar

| Tamaño | vCPU / RAM | Costo aprox. | Uso |
|---|---|---|---|
| **B1s** | 1 / 1 GB | Gratis 750 h/mes el primer año (si no, ~0,01 USD/h) | Chat, prueba de carga moderada. Usar `JAVA_MEM=-Xmx600m` |
| B2s | 2 / 4 GB | ~0,04 USD/h | Prueba de carga fuerte o entrenamiento con el servidor en la nube |

El servidor casi no calcula, porque eso lo hacen las laptops: alcanza con B1s. Región: la más
cercana a Lima que permita la suscripción. Brazil South da menos latencia; East US o East US 2
suelen estar siempre permitidas. Si una región da error de "policy", se prueba otra.

## Paso a paso (portal web, sin instalar nada)

1. **Crear el servidor:** Portal → *Máquinas virtuales* → *Crear*.
   - Imagen **Ubuntu Server 22.04 LTS**, tamaño **B1s**.
   - Autenticación con **clave SSH**: el portal genera la clave y la descarga como `.pem`.
     Guardarla fuera del repo.
   - Puertos de entrada: dejar solo **SSH (22)**.
2. **Abrir 5000 y 6000:** servidor → *Redes* → *Agregar regla de puerto de entrada*.
   - Destino: puertos `5000,6000`, protocolo TCP, prioridad 1010.
   - **Origen: "Direcciones IP"** con la IP pública de cada integrante (cada uno la ve en
     https://api.ipify.org desde su casa) y la de la red de la UNI el día de la exposición. Así
     nadie más puede usar el servidor, que no tiene cifrado.
   - Las IP de casa pueden cambiar de un día a otro. Si un nodo de repente no conecta, revisar
     esto primero. Para una prueba corta se puede abrir a "Any" y cerrarlo después.
3. **Entrar por SSH** desde PowerShell:
   `ssh -i ruta\clave.pem azureuser@<IP_PUBLICA>`
4. **Preparar el servidor** (instala el JDK, agrega swap, clona el repo, baja `train.bin` del
   Release v1.0 y compila):
   ```bash
   curl -fsSL https://raw.githubusercontent.com/dodo-UNI-2026-02/Open_AI_Cubes/main/scripts/nube_preparar_vm.sh | bash
   ```
5. **Arrancar el servidor** dentro de `tmux`, para que siga vivo al cerrar SSH:
   ```bash
   cd ~/Open_AI_Cubes && tmux new -s oac
   JAVA_MEM=-Xmx600m scripts/servidor.sh      # en la consola: estado | verbose | salir
   ```
   Para salir sin apagarlo: `Ctrl+B` y luego `D`. Para volver: `tmux attach -t oac`.
6. **Conectar las laptops** (Windows, desde la carpeta del proyecto o del kit):
   - Comprobar el puerto: `Test-NetConnection <IP> -Port 6000` (debe decir
     `TcpTestSucceeded : True`).
   - Cada laptop, su nodo:
     `scripts\nodo_java.bat <IP> 4`, `scripts\nodo_go.bat <IP> 4` o `scripts\nodo_python.bat <IP> 4`.
     En Linux o macOS, los mismos scripts terminan en `.sh`.
   - Chat: `scripts\cliente.bat <IP>`.
   - Estado: `java -cp out cubes.client.AdminClient --host <IP> estado`.
     Deben aparecer los 3 nodos, cada uno con el nombre de su laptop.
7. **Apagar al terminar:** servidor → *Detener*. En Azure "detenida (desasignada)" no cobra
   cómputo. Al cerrar el ciclo, borrar el **grupo de recursos** completo para que no quede el
   disco ni la IP cobrando.

## Lo mismo por línea de comandos (Azure Cloud Shell o `az` instalado)

```bash
az group create -n rg-openaicubes -l eastus
az vm create -g rg-openaicubes -n oac-servidor --image Ubuntu2204 --size Standard_B1s \
  --admin-username azureuser --generate-ssh-keys --public-ip-sku Standard
# 5000 y 6000 solo desde las IP de los integrantes (separadas por espacio)
az network nsg rule create -g rg-openaicubes --nsg-name oac-servidorNSG -n OpenAICubes \
  --priority 1010 --protocol Tcp --destination-port-ranges 5000 6000 \
  --source-address-prefixes <IP_INTEGRANTE_1> <IP_INTEGRANTE_2> <IP_INTEGRANTE_3>
az vm show -d -g rg-openaicubes -n oac-servidor --query publicIps -o tsv
# al terminar
az vm deallocate -g rg-openaicubes -n oac-servidor
az group delete -n rg-openaicubes        # al cerrar el ciclo
```

## Qué medir en la nube (para el informe)

Por internet aparece la **latencia de red**, que en la red local casi no existe. Es justo el tema
de desempeño de la semana 6.

1. **Prueba de carga: red local vs nube.** Con las mismas 3 laptops como nodos y la misma rampa:
   - **Red local:** el servidor corre en una de las laptops y todas están en el mismo WiFi o
     hotspot.
   - **Nube:** el servidor corre en Azure.

   Se cambia solo la etiqueta:
   ```bash
   scripts/carga.sh <IP_LAPTOP_SERVIDOR> "Red local 3 laptops" 10,50,100,200,400
   scripts/carga.sh <IP_AZURE>           "Nube 3 laptops"      10,50,100,200,400
   scripts/graficas.sh
   ```
   `carga_latencia.png` muestra las dos curvas: la diferencia en p50 es el viaje de ida y vuelta
   a Azure. `carga_throughput.png` muestra dónde se satura cada uno; con B1s el límite es el
   único vCPU del servidor.
2. **Entrenamiento por internet:** cada lote manda unos 1 MB de pesos a cada laptop, y el WiFi de
   casa sube mucho más lento de lo que baja. Con lote 64 domina la comunicación: usar
   `BATCH=256` y comparar en `costo_comunicacion.png`. Se recomienda **entrenar en red local**
   (laptops juntas) y dejar en la nube el chat y la prueba de carga.

## Riesgos y plan B

- **La red de la UNI puede bloquear puertos de salida no estándar (5000/6000).** Hay que probar
  `Test-NetConnection <IP> -Port 6000` desde la UNI antes de la exposición. Si está bloqueado,
  hay dos opciones:
  - Compartir internet desde el celular (hotspot) a las laptops.
  - Redirigir puertos estándar en el servidor sin tocar el código:
    ```bash
    sudo iptables -t nat -A PREROUTING -p tcp --dport 443 -j REDIRECT --to-port 5000
    sudo iptables -t nat -A PREROUTING -p tcp --dport 80  -j REDIRECT --to-port 6000
    ```
    Abrir 80 y 443 en el NSG y conectar con `--port 443` (clientes) y `--port 80` (nodos).
- **Dos nodos en la misma laptop** (por ejemplo, Java y Go para probar) cuentan como una sola
  máquina en la columna `hosts`. Para medir el escenario distribuido, cada nodo debe ir en una
  laptop distinta.
- **Laptops distintas entre sí:** cada una tiene otros núcleos y otra velocidad. El servidor
  reparte el trabajo en proporción a la velocidad que mide, pero cada lote espera a la más lenta.
  En el informe, anotar el procesador de cada laptop (`estado` muestra núcleos e hilos).
- **Seguridad:** el protocolo no cifra ni autentica de verdad. El login del chat es de
  demostración. Limitar el NSG a IPs conocidas y no dejar el servidor encendido sin uso.
- **Crédito:** revisar en el portal *Cost Management*. B1s apagado entre pruebas gasta casi nada.
