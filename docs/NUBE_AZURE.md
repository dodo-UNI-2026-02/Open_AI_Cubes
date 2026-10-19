# Servidor en la nube con Azure for Students

El enunciado pide como **opcional** "desarrollar el servidor en internet o en la nube". Aquí está
cómo hacerlo con **Azure for Students**: 100 USD de crédito y sin tarjeta de crédito. Se activa con
el correo institucional de la UNI en https://azure.microsoft.com/free/students.

## Idea

```
                 Internet
  ┌──────────────────────────────────────────┐
  │  VM Azure (Ubuntu, IP pública)           │
  │  CentralServer  :5000 clientes           │
  │                 :6000 nodos              │
  └──────▲───────────────▲───────────────▲───┘
         │ TCP saliente  │               │
  ┌──────┴─────┐  ┌──────┴─────┐  ┌──────┴──────────┐
  │ Lab / casa │  │ Lab / casa │  │ Cualquier PC    │
  │ nodo Java  │  │ nodo Go    │  │ ChatClient      │
  │ nodo Python│  │            │  │ LoadGenerator   │
  └────────────┘  └────────────┘  └─────────────────┘
```

- **Solo el servidor va a la nube.** Los nodos y los clientes *inician* la conexión hacia la VM,
  así que en el laboratorio o en casa no hay que abrir ni redirigir puertos: basta con salida a
  internet por los puertos 5000 y 6000.
- El protocolo es el mismo (OC/1 sobre TCP, sin librerías externas). No se usa VPN ni middleware
  de terceros, que el enunciado prohíbe.
- Si no hay nodos conectados, el servidor responde las consultas él solo, así que el chat
  funciona aunque se caigan los nodos.

## Qué VM usar

| Tamaño | vCPU / RAM | Costo aprox. | Uso |
|---|---|---|---|
| **B1s** | 1 / 1 GB | Gratis 750 h/mes el primer año (si no, ~0,01 USD/h) | Chat, prueba de carga moderada. Usar `JAVA_MEM=-Xmx600m` |
| B2s | 2 / 4 GB | ~0,04 USD/h | Prueba de carga fuerte o entrenamiento con el servidor en la nube |

Región: la más cercana a Lima que permita la suscripción. Brazil South da menos latencia;
East US o East US 2 suelen estar siempre permitidas. Si una región da error de "policy", se prueba
otra.

## Paso a paso (portal web, sin instalar nada)

1. **Crear la VM:** Portal → *Máquinas virtuales* → *Crear*.
   - Imagen **Ubuntu Server 22.04 LTS**, tamaño **B1s**.
   - Autenticación con **clave SSH**: el portal genera la clave y la descarga como `.pem`.
     Guardarla fuera del repo.
   - Puertos de entrada: dejar solo **SSH (22)**.
2. **Abrir 5000 y 6000:** VM → *Redes* → *Agregar regla de puerto de entrada*.
   - Destino: puertos `5000,6000`, protocolo TCP, prioridad 1010.
   - **Origen: "Direcciones IP"** con la IP pública del laboratorio y la de casa. La IP pública
     se ve en https://api.ipify.org desde cada lugar. Así nadie más puede usar el servidor, que
     no tiene cifrado.
   - Abrir a "Any" solo durante la prueba y cerrarlo después.
3. **Entrar por SSH** desde PowerShell:
   `ssh -i ruta\clave.pem azureuser@<IP_PUBLICA>`
4. **Preparar la VM** (instala el JDK, agrega swap, clona el repo, baja `train.bin` del Release
   v1.0 y compila):
   ```bash
   curl -fsSL https://raw.githubusercontent.com/dodo-UNI-2026-02/Open_AI_Cubes/main/scripts/nube_preparar_vm.sh | bash
   ```
   Este script clona lo que esté en GitHub. Si hay cambios locales sin subir, primero hay que
   hacer push, o copiarlos con `scp -i clave.pem -r java scripts azureuser@<IP>:~/Open_AI_Cubes/`
   y volver a compilar.
5. **Arrancar el servidor** dentro de `tmux`, para que siga vivo al cerrar SSH:
   ```bash
   cd ~/Open_AI_Cubes && tmux new -s oac
   JAVA_MEM=-Xmx600m scripts/servidor.sh      # en la consola: estado | verbose | salir
   ```
   Para salir sin apagarlo: `Ctrl+B` y luego `D`. Para volver: `tmux attach -t oac`.
6. **Conectar desde fuera:**
   - Comprobar el puerto desde Windows: `Test-NetConnection <IP> -Port 6000`.
     Desde Linux: `nc -vz <IP> 6000`.
   - Nodos (en el clúster del lab o en las laptops):
     `scripts/nodo_java.sh <IP> 2`, `scripts/nodo_go.sh <IP> 2`, `scripts/nodo_python.sh <IP> 2`.
   - Chat: `scripts/cliente.sh <IP>` (en Windows, `scripts\cliente.bat <IP>`).
   - Estado: `java -cp out cubes.client.AdminClient --host <IP> estado`.
7. **Apagar al terminar:** VM → *Detener*. En Azure "detenida (desasignada)" no cobra cómputo.
   Al cerrar el ciclo, borrar el **grupo de recursos** completo para que no quede el disco ni la
   IP cobrando.

## Lo mismo por línea de comandos (Azure Cloud Shell o `az` instalado)

```bash
az group create -n rg-openaicubes -l eastus
az vm create -g rg-openaicubes -n oac-servidor --image Ubuntu2204 --size Standard_B1s \
  --admin-username azureuser --generate-ssh-keys --public-ip-sku Standard
# 5000 y 6000 solo desde las IP del lab y de casa (separadas por espacio)
az network nsg rule create -g rg-openaicubes --nsg-name oac-servidorNSG -n OpenAICubes \
  --priority 1010 --protocol Tcp --destination-port-ranges 5000 6000 \
  --source-address-prefixes <IP_LAB> <IP_CASA>
az vm show -d -g rg-openaicubes -n oac-servidor --query publicIps -o tsv
# al terminar
az vm deallocate -g rg-openaicubes -n oac-servidor
az group delete -n rg-openaicubes        # al cerrar el ciclo
```

## Qué medir en la nube (para el informe)

Por internet aparece la **latencia de red**, que en la LAN casi no existe. Es justo el tema de
desempeño de la semana 6.

1. **Prueba de carga LAN vs nube.** Con los mismos nodos y la misma rampa, primero contra el
   servidor del lab y luego contra la VM. Se cambia solo la etiqueta:
   ```bash
   scripts/carga.sh <IP_LAN>  "LAN 3 nodos"  10,50,100,200,400
   scripts/carga.sh <IP_NUBE> "Nube 3 nodos" 10,50,100,200,400
   scripts/graficas.sh
   ```
   `carga_latencia.png` muestra las dos curvas: la diferencia en p50 es el viaje de ida y vuelta
   a Azure. `carga_throughput.png` muestra dónde se satura cada uno; con B1s el límite es el
   único vCPU del servidor.
2. **Entrenamiento por internet:** cada lote manda unos 1 MB de pesos a cada nodo. Con lote 64,
   la comunicación domina; usar `BATCH=256` y comparar en `costo_comunicacion.png`. Se recomienda
   **entrenar en la LAN** y dejar en la nube el chat y la prueba de carga.

## Riesgos y plan B

- **La red de la UNI puede bloquear puertos de salida no estándar (5000/6000).** Hay que probar
  `Test-NetConnection <IP> -Port 6000` desde el laboratorio antes de la exposición. Si está
  bloqueado, redirigir puertos estándar en la VM sin tocar el código:
  ```bash
  sudo iptables -t nat -A PREROUTING -p tcp --dport 443 -j REDIRECT --to-port 5000
  sudo iptables -t nat -A PREROUTING -p tcp --dport 80  -j REDIRECT --to-port 6000
  ```
  Abrir 80 y 443 en el NSG y conectar con `--port 443` (clientes) y `--port 80` (nodos).
- **Hostnames repetidos:** si las VM del laboratorio son clones con el mismo hostname y salen
  por la misma IP pública, el servidor no las distingue como máquinas distintas. Antes de
  conectarlas, poner a cada una un nombre propio:
  `sudo hostnamectl set-hostname nodo1`.
- **Seguridad:** el protocolo no cifra ni autentica de verdad. El login del chat es de
  demostración. Limitar el NSG a IPs conocidas y no dejar la VM encendida sin uso.
- **Crédito:** revisar en el portal *Cost Management*. B1s apagada entre pruebas gasta casi nada.
