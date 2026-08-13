# STCP Gemini (Riversoft) — Documentación técnica

> Documento consolidado a partir de la documentación técnica de la PoC *"STCP Gemini – App File Transfer Security"* (BNP Paribas Cardif).
> Complemento: ver `PROTOCOLOS-TRANSFERENCIA.md` para el detalle normativo de los protocolos FTP, SFTP y OFTP referidos aquí.

## 1. Introducción

**STCP Gemini** es una plataforma MFT (Managed File Transfer) del proveedor brasileño **Riversoft** (riversoft.com.br). BNP Paribas Cardif ejecutó una PoC para evaluar la migración de reglas de transferencia de archivos desde su sistema legado **STCP File Transfer / PIMS** hacia STCP Gemini.

Características declaradas por el fabricante:

- TLS 1.2 y 1.3 para intercambio seguro por Internet.
- Soporte total de certificados digitales.
- Autenticidad, Confidencialidad, Integridad y Trazabilidad, cumpliendo LGPD, GDPR, PCI y otros.
- Cifrado asimétrico y simétrico: autenticidad (RSA/DSA), confidencialidad (AES/AES128/AES256), integridad (SHA1/SHA256/SHA384).
- Políticas de seguridad en control de autenticación.
- Integración con aplicaciones externas (PGP, GPG, otras).

> **Nota sobre algoritmos**: SHA1 figura en la lista del fabricante pero está criptográficamente roto y desaconsejado para integridad desde 2017. Verificar que la configuración productiva use SHA256 o superior.

## 2. Arquitectura

### 2.1 Topología general

Componentes principales:

| Componente | Función |
|---|---|
| **STCP Gemini** (Linux Server, Docker) | Motor de transferencia gestionada |
| **STCP DirectLink** (Linux Server, Docker) | Variante para portal/API de socios vía web (HTTPS) |
| **STCP Gemini API / Portal** | Administración y automatización vía API/web |
| **STCP DirectLink API / Portal** | Acceso de clientes/socios vía HTTPS |
| **SQL Server** | Configuración y logs |
| **Conectores** | Integración hacia nubes (Oracle Cloud, AWS, Azure) |
| **Datos / Aplicación** | Integración con apps internas |

Existen dos vías de ingreso para terceros, dirigidas a audiencias distintas:

- **Agente de transferencia STCP sobre OFTP** — para socios EDI con acuerdo formal establecido. Requiere software instalado y configurado en ambos extremos.
- **SFTP/HTTPS contra el portal DirectLink** — para el resto de clientes y socios.

Todo el tráfico pasa por firewall antes de llegar al servidor Linux.

### 2.2 Zonas de riesgo (HR / LR)

La instalación productiva separa dos entornos:

- **PRES-AZ-L0P / PRES-AZ-L1P (High Risk, HR)**: expuesta a entidades externas. Incluye `FS External`, **Gemini Portal + API**, **STCP Gemini Core (Presentation)** con instancias por país (Colombia, Chile, Perú, Brasil, México), su propio SQL de metadata/config/logs, un conector COS y un `Proxy Socks CIB` hacia entidades externas.
- **INTRA-AZ-L0R / INTRA-AZ-L1R (Low Risk, LR)**: red interna. Incluye `FS Internal` + WAF, **Gemini Portal + API**, **STCP Direct Link**, **STCP Gemini Core (Intranet)** con las mismas instancias por país, y su propio SQL de metadata/config/logs.
- Ambos entornos corren sobre **RHEL Linux Server** y se comunican entre sí vía **SESAME SAF**.
- Protocolos de entrada: OFTP/SFTP (HR, hacia `gmn-b2b-transfer.cardifnet.com` / `gmn-edi.cardifnet.com`) y OFTP/SFTP/HTTPS (LR, hacia `FS Internal`).
- Los administradores acceden vía HTTPS desde la red de usuarios (`Users Net`).

### 2.3 Flujos posibles (diagrama de riesgo)

```
HIGH RISK                                    LOW RISK
──────────                                   ─────────
External Partners
  │ OFTP/SFTP (🔒)
  ▼
STCP GEMINI HR ───── SFTP ─────► STCP GEMINI LR ──SFTP──► CFT AXWAY ──PESIT(🔒)──► Head Office FR
  │ SFTP        │ SSH                          │ SSH        │ SFTP        Screening/Domino
  ▼             ▼                              ▼            ▼
STCP COUNTRY HR  APP HR ──SSH──► APP LR    STCP COUNTRY LR   DATABASE REPORT
```

La instancia **HR** habla con socios externos y con la instancia **LR** (vía SSH/SFTP), que a su vez conecta con **CFT Axway** hacia la casa matriz en Francia (protocolo **PESIT**) y alimenta una base de datos de reportes.

### 2.4 Uso de OFTP en la arquitectura

OFTP cumple un rol acotado y bien definido: **es el protocolo de ingreso de socios externos B2B a la zona de alto riesgo**. Tres señales lo confirman:

1. **Solo aparece en el borde externo.** En el diagrama de §2.3, OFTP figura exclusivamente en el tramo `External Partners → STCP GEMINI HR`. Todos los tramos internos (HR→LR, hacia APP, hacia STCP COUNTRY) usan SFTP o SSH.
2. **Los endpoints lo declaran.** Los hostnames de §2.2 son `gmn-b2b-transfer` y `gmn-edi` — *B2B transfer* y *EDI*, exactamente el caso de uso para el que Odette diseñó OFTP.
3. **Requiere agente dedicado**, no un cliente genérico — coherente con un partner formal con identidades acordadas de antemano.

La razón técnica de fondo: OFTP2 aporta **no repudio de entrega** mediante acuses firmados (EERP) y **cifrado a nivel de archivo** (CMS), garantías que SFTP no ofrece a nivel de protocolo. Ver `PROTOCOLOS-TRANSFERENCIA.md`.

> **Alcance de la PoC**: OFTP está documentado como capacidad de la plataforma pero **no fue ejercitado durante la PoC**. Todas las pruebas (§3–§6) usaron transferencia TCP directa entre usuarios internos del Portal. No existe en esta documentación ningún dato de configuración OFTP: ni puerto, ni SSID/SFID, ni certificados de partner. Validar esa conectividad queda como trabajo pendiente.

## 3. Modelo de usuarios y conexiones

Para la PoC se crearon dos usuarios en el Portal:

| Usuario | Rol |
|---|---|
| **USER01** | Cliente, envío de archivos hacia Gemini |
| **VOLTAGE** | Recibe los archivos y ejecuta las reglas (FileProcessor) |

Gestión vía el menú lateral del Portal: **Instâncias → Redes → Usuários → Agendamentos** (config STCP), y **Grupos → Operadores → Códigos de erros/eventos → API** (config Admin).

Una conexión se inicia manualmente desde **Manutenção > Usuário > Conexão**, seleccionando el usuario (ej. `USER01`) y pulsando "Iniciar conexão". El panel muestra en tiempo real contador de **Sucesso/Erro** y el detalle ("A instância STCPCARDIFLAM recebeu o comando com sucesso em fecha/hora").

## 4. Motor de reglas STCPREN

Al igual que STCP File Transfer clásico, Gemini usa la herramienta nativa **STCPREN** para definir el pipeline de reglas aplicadas a un usuario. La diferencia es el formato: STCP FT usa `.INI`, Gemini usa **`.json`**.

> **Limitación de la versión evaluada**: el modo gráfico de STCPREN no estaba disponible en la consola Gemini. Riversoft comprometió su disponibilidad para la actualización de diciembre de 2024. Hasta entonces, los archivos de reglas debían crearse **manualmente** por consola. **Pendiente de verificar** si esa entrega se concretó.

Ubicación de los archivos de reglas: `/opt/gemini/stcpren/`, ejemplo de listado real:

```
stcpren-rx-gpg.json
stcpren-rx-voltage.json
stcpren-rx-zip.json
stcpren-tx-zip.json
```

La convención de nombres es legible: `stcpren-<rx|tx>-<propósito>.json`, donde `rx` aplica en recepción y `tx` en transmisión.

### 4.1 Configuración desde el Portal

La regla se configura en **Usuários → [usuario] → Tipos de arquivo de usuário**. Como el usuario `VOLTAGE` es quien ejecuta el FileProcessor, la regla se aplica sobre su configuración.

Cada regla tiene cuatro bloques: **Geral** (nombre/descripción/tags), **Informações** (patrón: Padrão o **Expressão regular/Regex**), **Execução de comando externo de validação**, **Período de transferência**.

En "Recepção" se define:

- **Habilitada** (on/off)
- **Executar comando externo**: el binario `stcpren` con sus parámetros
- **Converte nome de arquivo**, **Tabela de conversão de dados**, **Inserir CRLF**, etc.

### 4.2 Estructura de un `FileMatching` (JSON)

Cada regla contiene, entre otros campos:

```jsonc
"FileMatching": [
  {
    "Index": 0,
    "Name": "FILE_UNZIP",
    "Description": "FILE_UNZIP",
    "FileRegEx": "(?i).*\\.zip$",
    "DirRegEx": "", "SrcRegEx": "", "ContentRegEx": "",
    "General": {
      "SourceUser": "<UNZIP>",
      "DestinationUser": "<UNZIP>",
      "ExecProg": "",
      "ExecProgBefore": "unzip $FILEOLDNAME -d $PATHNEWNAME",
      "ExecProgError": "/bin/bash -c 'mv $FILEOLDNAME $FILEOLDNAME.err ...'"
    },
    "Copy": {
      "CopyToDir": "/opt/gemini/data/STCPCARDIFLAM/VOLTAGE/TEMP",
      "CopyFileOption": 0, "CopyTempExtension": "",
      "Replaces": null, "ReplacesByRegEx": null
    }
  }
]
```

Campos clave:

- **`Index`**: orden de evaluación. Las reglas encadenadas dependen de él (ver §5.3).
- **`FileRegEx`**: expresión regular que hace match del nombre de archivo entrante para disparar la regla.
- **`ExecProgBefore`**: comando de shell ejecutado **antes** de mover/copiar el archivo (aquí se invocan `unzip`, `gpg`, o el script de Voltage).
- **`ExecProg`**: comando ejecutado **después** de la copia.
- **`ExecProgError`**: comando de compensación ante fallo (típicamente renombrar a `.err`).
- **`Copy.CopyToDir`**: carpeta destino tras procesar.
- **`ReplacesByRegEx`**: array de reglas de renombrado por regex, usado para insertar sufijos (ej. `_MSK`) al nombre final.

### 4.3 Variables de sustitución

Las variables disponibles en los comandos externos, inferidas de los ejemplos:

| Variable | Significado |
|---|---|
| `$FILEOLDNAME` | Ruta/nombre del archivo de origen |
| `$FILENEWNAME` | Ruta/nombre del archivo destino |
| `$PATHOLDNAME` | Directorio del archivo de origen |
| `$PATHNEWNAME` | Directorio destino |
| `$OLD2NAME` | Nombre previo en una cadena de reglas encadenadas |
| `$VAR0` | Valor pasado por el parámetro `-var0` de `stcpren` |
| `$LFNAME` | *Last file name* — argumento posicional que recibe `stcpren` |

> **Riesgo de seguridad a evaluar**: estas variables se expanden dentro de comandos de shell sin comillas. Un nombre de archivo malicioso que contenga metacaracteres (`;`, `` ` ``, `$(...)`) podría derivar en inyección de comandos. Dado que los archivos llegan de socios externos, conviene confirmar con Riversoft cómo se sanitizan, o restringir los `FileRegEx` a conjuntos de caracteres seguros.

## 5. Pipelines documentados (casos de uso probados)

La PoC probó 3 pipelines encadenables sobre el usuario `VOLTAGE`, activables al **recibir** o **transmitir** archivos según el alcance necesario.

Los pipelines son **agnósticos al protocolo de entrada**: disparan por regex sobre el nombre del archivo una vez que este ya llegó. Cómo llegó (OFTP, SFTP, HTTPS) es irrelevante para STCPREN.

### 5.1 Pipeline ZIP — descompresión

- Regla `FILE_UNZIP`, dispara con regex `(?i).*\.zip$`.
- Ejecuta: `unzip $FILEOLDNAME -d $PATHNEWNAME`.
- Copia el resultado a `/opt/gemini/data/STCPCARDIFLAM/VOLTAGE/TEMP`.
- Configuración del comando externo en el Portal:

```
/usr/local/bin/stcpren -rules /opt/gemini/stcpren/stcpren-rx-zip.json $LFNAME
```

Requiere el binario `stcpren` en `/usr/local/bin/stcpren`, el parámetro `-rules` con la ruta al JSON, y `$LFNAME` (last file name).

**Ejemplo real**: `USER01` envía `archive.txt.zip` → aparece en `SAIDA/` como `arquivo.txt.zip` → el log muestra eventos `MSG7020` ("removido arquivo com sucesso") y `MSG7035` ("executar o comando antes da copia com sucesso", `cmd=unzip $FILEOLDNAME -d $PATHNEWNAME`) → el archivo descomprimido aparece en `VOLTAGE/TEMP/arquivo.txt`.

### 5.2 Pipeline Voltage (tokenización/enmascaramiento)

Objetivo: renombrar el archivo después de procesarlo en Voltage. Cada nombre de archivo corresponde a un **archivo de template** de Voltage (config XML), y cada regla creada usa en su regex el nombre de archivo que informa su template.

Ejemplo de la PoC:

| Dato | Valor |
|---|---|
| Nombre archivo (regex) | `069CO08PR820*` |
| Archivo template Voltage | `protect-Plain-HeaderF-DelSemicolon-Col3-CC_FPE_4x4-CO.xml` |
| Regla STCPREN | `stcpren-rx-voltage.json` |

El nombre del template es autodescriptivo: `protect` (operación), `Plain` (entrada en claro), `HeaderF` (sin header), `DelSemicolon` (delimitador `;`), `Col3` (columna 3), `CC_FPE_4x4` (tarjeta de crédito, cifrado que preserva formato, muestra 4 primeros y 4 últimos dígitos), `CO` (Colombia).

Comando completo configurado en Recepção:

```
/usr/local/bin/stcpren -rules /opt/gemini/stcpren/stcpren-rx-voltage.json \
  -var0 protect-Plain-HeaderF-DelSemicolon-Col3-CC_FPE_4x4-CO.xml \
  $LFNAME
```

Parámetros: 1) binario `stcpren`, 2) `-rules` + ruta al JSON, 3) `-var0` para pasar el **nombre del archivo de template**, 4) `$LFNAME`.

**Ejecución real de Voltage** ocurre dentro de STCPREN, vía el script `/usr/local/stcp/scripts/exec.sh`:

```bash
#!/bin/bash
export JAVA_HOME=/opt/gemini/java/jdk-11
export PATH=$JAVA_HOME/bin:$PATH

OUTPUT=$(/opt/gemini/VOLTAGE/voltage-sdfp/sdfileprocessor --in $1 --out $2 --config-file /opt/gemini/VOLTAGE/test/config/$3)
echo "$OUTPUT" >> /opt/gemini/transfer/log.txt

if [ $? -eq 0 ]; then
  rm $1
fi
exit 0
```

> **Tres defectos en este script**, a corregir antes de producción:
> 1. `$?` no evalúa el resultado de `sdfileprocessor` sino el del `echo` anterior, que casi siempre devuelve 0. **El archivo de origen se borra aunque Voltage haya fallado.**
> 2. `exit 0` incondicional: STCPREN nunca se entera de un fallo, por lo que `ExecProgError` jamás se dispara.
> 3. La ruta de templates apunta a `/opt/gemini/VOLTAGE/**test**/config/` — ruta de pruebas, no productiva.

Regla asociada (índice 1, `FILE_VOLTAGE`, sin PGP) invoca:

```
"ExecProgBefore": "/usr/local/stcp/scripts/exec.sh $FILEOLDNAME $FILENEWNAME $VAR0"
```

Copia a `VOLTAGE/TEMP` tras renombrar.

### 5.3 Pipeline PGP/GPG — archivos cifrados

Regla `FILE_GPG_VOLTAGE`, dispara con regex `(?i).*\.PGP$`. Requiere **GPG** instalado en el servidor con las llaves correspondientes, para descifrar/cifrar.

```
"ExecProgBefore": "gpg --decrypt --batch --yes -o $FILENEWNAME $FILEOLDNAME"
```

El archivo descifrado se copia a `/opt/gemini/data/STCPCARDIFLAM/VOLTAGE/ENTRADA` (carpeta de entrada de Voltage, **no** TEMP).

Segunda regla encadenada `FILE_GPG_VOLTAGE_EXEC` ejecuta Voltage sobre el archivo ya descifrado:

```
"ExecProg": "/usr/local/stcp/scripts/exec.sh $PATHOLDNAME/$OLD2NAME $FILENEWNAME $VAR0"
```

y aplica el mismo renombrado final (`ReplacesByRegEx`) antes de copiar a `VOLTAGE/TEMP`.

Nótese que aquí se usa `ExecProg` (después de la copia) mientras que en §5.2 se usa `ExecProgBefore` (antes) — la diferencia responde al encadenamiento entre ambas reglas.

> **Observación de diseño**: este pipeline reimplementa manualmente lo que OFTP2 provee de forma nativa mediante CMS (cifrado y firma a nivel de archivo). Si el socio soporta OFTP2, el paso PGP podría ser redundante.

### 5.4 Renombrado final (`_MSK`)

Común a los pipelines de Voltage: al terminar el procesamiento, una regla `ReplacesByRegEx` inserta el sufijo `_MSK` (masked) al nombre de archivo:

```jsonc
"ReplacesByRegEx": [
  {
    "RegExReplaceDesc": "Insert MSK extension to filename",
    "RegExReplaceFrom": "\\.(\\w+)$",
    "RegExReplaceTo": "_MSK.$1",
    "RegExReplaceOcorr": 0
  }
]
```

Ejemplo: `069CO08PR8202023091200.txt` → `069CO08PR8202023091200_MSK.txt`.

El sufijo actúa como marca visible de que el contenido ya pasó por enmascaramiento — útil para auditoría y para evitar reprocesamiento.

## 6. Ejemplo end-to-end (caso cifrado)

`USER01` envía dos archivos: uno **cifrado** (`069CO08PR8202023091200.txt.pgp`) y uno **sin cifrar** (`069CO08PR8202023091201.txt`), para forzar la ejecución de Voltage por ambas rutas.

Log de transmisión:

```
17/10/2024 09:27:25  USER01    [MSG0019] OUT - Início de transmissão '069CO08PR8202023091200.txt.pgp' ftype=global:Padrão [TCP-OUT] ...
17/10/2024 09:27:25  VOLTAGE   [MSG0035] IN  - Início de recepção '069CO08PR8202023091200.txt.pgp' ftype=protect-Plain-HeaderF-DelSemicolon-Col3-CC_FPE_4x4-CO [TCP-IN] ...
```

El campo `ftype` es el que vincula el archivo con su template de Voltage: el emisor usa `global:Padrão` y el receptor resuelve el template específico.

Resultado final en `VOLTAGE/TEMP`: ambos archivos renombrados con sufijo `_MSK`:

```
069CO08PR8202023091200_MSK.txt
069CO08PR8202023091201_MSK.txt
```

## 7. Importación de reglas desde STCP FT / PIMS

Además de la PoC de pipelines, se solicitó importar reglas ya usadas en **PIMS** (reportadas por el equipo de Colombia), que consisten mayormente en **renombrado simple** de archivos:

| Nombre origen | Renombre |
|---|---|
| `070CO07PR01024121200.txt` | `AV070CO07PR010-24121200.txt` |
| `069CO08PR9002024121203.txt` | `BA069CO08PR900-2024121203.txt` |
| `082CO10MR10524121200.txt` | `BB082CO10MR105-24121200.txt` |
| `082CO10PR10524121211.txt` | `BB082CO10PR105-24121211.txt` |
| `084CO12PR01824121200.txt` | `BP084CO12PR018-24121200.txt` |
| `510CO77PR9002024121200.txt` | `CO510CO77PR900-2024121200.txt` |
| `208CO99PR1992024121200.txt` | `EX208CO99PR199-2024121200.txt` |
| `083CO11CE0112024121200.txt` | `OC083CO11CE011-2024121200.txt` |

El patrón de transformación es consistente: se antepone un prefijo alfabético de 2 letras (identificador de entidad: `AV`, `BA`, `BB`, `BP`, `CO`, `EX`, `OC`) y se inserta un guion antes del bloque de fecha. Todo esto es expresable con `ReplacesByRegEx`.

**Respuesta del proveedor** (Fred Ribeiro, `fred.ribeiro@riversoft.com.br`, 09/ene/2025, a Jonatas Bezerra / Hernán Pineda de Cardif):

> *"La gran mayoría de las reglas encaminadas se refieren a renombramientos simples, copia y backup. Es posible exportar esas reglas y utilizarlas en STCP Gemini."*

## 8. Conectividad y estructura de carpetas

### 8.1 Acceso SFTP

Ejemplo de conexión vía **WinSCP** al servidor:

- Host de ejemplo: `stcp-hr-transfer-latam-assurance.io.echonet`
- Puerto: **2024**
- Protocolo: **SFTP**

> **Inconsistencia a resolver**: la configuración registrada indica *"SFTP con SSL/TLS habilitado"*, lo cual es contradictorio. **SFTP se asegura con SSH, nunca con TLS.** Si realmente hay TLS de por medio, el protocolo es **FTPS**, no SFTP. Esto debe confirmarse antes de dimensionar cualquier despliegue: cambia el cliente, el puerto y el tipo de certificado requerido. Ver `PROTOCOLOS-TRANSFERENCIA.md` §3.

### 8.2 Acceso OFTP

La documentación de la PoC **no incluye configuración OFTP**. Para habilitarlo haría falta, como mínimo:

- Agente de transferencia STCP instalado en ambos extremos (no sirve un cliente SFTP genérico).
- Identificadores **SSID/SFID** acordados con cada socio.
- Certificados X.509 intercambiados con cada socio.
- Puertos: **3305** (plano) o **6619** (TLS), según RFC 5024.

### 8.3 Estructura estándar de carpetas por usuario

```
CONTROLE/
ENTRADA/
FORMATO/
SAIDA/
  BACKUP/
  PENDENTE/
TEMP/
```

| Carpeta | Uso |
|---|---|
| `ENTRADA/` | Archivos recibidos; entrada de Voltage en el pipeline PGP |
| `SAIDA/` | Archivos a transmitir |
| `SAIDA/PENDENTE/` | En cola de envío |
| `SAIDA/BACKUP/` | Copia tras envío exitoso |
| `TEMP/` | Área de trabajo de STCPREN; destino final de los pipelines |
| `CONTROLE/` | Control interno de la instancia |
| `FORMATO/` | Definiciones de formato |

### 8.4 Convención de nombres de archivo

Patrón observado: `0XXCOXXPRXXXyyyymmddhhmm.txt`

Ejemplo: `069CO08PR8202023091200.txt`

El prefijo numérico/alfabético identifica tipo de entidad, país y producto; el sufijo es fecha y hora. **Esta convención es crítica**: todas las reglas STCPREN dependen de expresiones regulares sobre el nombre, y el mapeo archivo↔template de Voltage se resuelve por ahí.

## 9. Requisitos para replicar o extender la PoC

### 9.1 Accesos

- **Portal STCP Gemini** (multi-tenant: requiere seleccionar organización/tenant, ej. `CARDIFLAM`, en el login) para crear usuarios y "tipos de arquivo de usuário".
- **Shell del servidor Gemini** (RHEL) para crear manualmente los archivos de reglas `.json` en `/opt/gemini/stcpren/`, mientras el editor gráfico no esté disponible.

### 9.2 Componentes de software

| Componente | Ruta / detalle | Necesario para |
|---|---|---|
| `stcpren` | `/usr/local/bin/stcpren` | Todos los pipelines |
| Voltage/SecureData | `sdfileprocessor` + Java 11 | Tokenización/enmascaramiento |
| Templates XML de Voltage | uno por tipo de dato a proteger | Tokenización/enmascaramiento |
| GPG | con las llaves correspondientes | Descifrado de archivos `.pgp` |
| `unzip` | binario del sistema | Pipeline ZIP |

### 9.3 Cliente de prueba

- Para **SFTP**: cualquier cliente estándar (WinSCP, FileZilla, `sftp` de OpenSSH).
- Para **OFTP**: agente STCP de Riversoft o un cliente Odette dedicado, con SSID/SFID y certificados acordados. **WinSCP no soporta OFTP** — solo habla SFTP, SCP, FTP, FTPS, WebDAV y S3.

### 9.4 Definiciones previas

- Convención de nombres de archivo (de ella dependen todas las regex).
- Mapeo archivo ↔ template de Voltage.
- Estrategia de manejo de errores (ver los defectos de `exec.sh` en §5.2).

## 10. Puntos abiertos

Consolidado de lo que quedó sin resolver o requiere verificación:

| # | Punto | Sección |
|---|---|---|
| 1 | Confirmar si el editor gráfico de reglas STCPREN se entregó (comprometido para dic/2024) | §4 |
| 2 | Aclarar si el acceso es SFTP o FTPS — la config registrada es contradictoria | §8.1 |
| 3 | Corregir los tres defectos de `exec.sh`: chequeo de `$?`, `exit 0` incondicional, ruta `test/` | §5.2 |
| 4 | Evaluar riesgo de inyección de comandos vía nombres de archivo de origen externo | §4.3 |
| 5 | Validar la conectividad OFTP end-to-end: nunca se probó en la PoC | §2.4 |
| 6 | Confirmar que la configuración productiva no use SHA1 para integridad | §1 |
| 7 | Definir si el pipeline PGP sigue siendo necesario cuando el socio soporta OFTP2 | §5.3 |
