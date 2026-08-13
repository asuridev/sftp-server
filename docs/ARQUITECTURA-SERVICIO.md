# stcp-uploader — Arquitectura del servicio

> Servicio REST que actúa como **gateway de protocolo**: recibe archivos por HTTP y los deposita vía SFTP en un servidor STCP.
> Documentos relacionados: `STCP-GEMINI.md` (plataforma destino) · `PROTOCOLOS-TRANSFERENCIA.md` (fundamento de los protocolos) · `../PLAN.md` (plan de construcción) · `../infra/README.md` (entorno local).

## 1. Qué resuelve

La plataforma STCP Gemini recibe archivos de sus clientes por **SFTP**. Eso obliga a que cada aplicación que quiera depositar un archivo hable SSH, gestione claves y conozca las credenciales del servidor MFT.

Este servicio invierte esa dependencia: expone **una API HTTP** y concentra en un solo lugar la conversación SFTP.

```
┌──────────────┐                    ┌─────────────────┐                  ┌──────────────┐
│ Cliente HTTP │  POST multipart    │  stcp-uploader  │   SFTP / SSH     │ Servidor STCP│
│ (curl, app)  │ ─────────────────► │  (Spring Boot)  │ ───────────────► │              │
└──────────────┘  /api/v1/files     └─────────────────┘   puerto 2024    │  /ENTRADA    │
                                                                          └──────────────┘
```

Beneficios concretos:

- Las apps consumidoras no necesitan cliente SFTP ni credenciales SSH.
- Las credenciales del servidor STCP viven en un único servicio.
- El punto de entrada queda centralizado: es donde se puede validar, auditar y limitar.

### Relación con WinSCP

En la PoC de STCP Gemini el archivo se subía a mano con **WinSCP**. Este servicio hace la misma operación de forma programática, pero es deliberadamente más acotado:

| | WinSCP | stcp-uploader |
|---|---|---|
| Operaciones | `ls`, `cd`, `get`, `put`, `rm`, editar, sincronizar | **solo `put`** |
| Dirección | Bidireccional | Unidireccional (subida) |
| Destino | Cualquier carpeta navegable | Fijo, configurado (`/ENTRADA`) |
| Operador | Persona, interfaz gráfica | Otro sistema, vía API |
| Sesión | Interactiva y persistente | Pool reutilizable (`CachingSessionFactory`) |

En términos de la arquitectura de `STCP-GEMINI.md`, este servicio ocupa el rol del cliente `USER01`: deposita en `ENTRADA/` para que el motor STCPREN dispare sus reglas.

## 2. Stack

| Elemento | Versión / detalle |
|---|---|
| Java | 21 (toolchain) |
| Spring Boot | 3.5.3 |
| Build | Gradle (wrapper incluido) |
| Cliente SFTP | `spring-integration-sftp` |
| Capa SSH | **Apache MINA SSHD** (transitiva) |
| Artefacto | `com.asuridev:stcp` v0.1.0 |

> **Nota sobre MINA SSHD**: Spring Integration 6.5 migró el módulo SFTP de **JSch** a **Apache MINA SSHD**. El cambio importa: JSch clásico no soporta los algoritmos modernos de intercambio de llaves (curve25519 y similares) y rompía el handshake contra el servidor emulado. No requiere dependencias extra.

## 3. Componentes

```
com.asuridev.stcp
├── StcpUploaderApplication      punto de entrada
├── controller
│   └── FileUploadController     POST /api/v1/files (multipart)
├── service
│   └── StcpUploadService        validación + envío SFTP
├── config
│   ├── SftpConfig               beans de sesión y template
│   └── SftpProperties           binding de stcp.sftp.*
├── dto
│   ├── FileUpload               contenido, nombre, tipo, tamaño
│   └── UploadResponse           remotePath, size, uploadedAt
└── exception
    ├── BadRequestException      entrada inválida     → 400
    ├── UploadFailedException    fallo aguas abajo    → 502
    ├── ErrorResponse            cuerpo de error
    └── ApiExceptionHandler      @RestControllerAdvice
```

### 3.1 Flujo de una subida

1. **`FileUploadController`** recibe el `multipart/form-data` en la parte `file`. Convierte el `MultipartFile` a un record `FileUpload` (`getBytes()`, `getOriginalFilename()`, `getContentType()`, `getSize()`). Si viene vacío o ilegible → `BadRequestException`.
2. **`StcpUploadService`** valida contenido y nombre no vacíos, arma un `Message<ByteArrayInputStream>` con el header `FileHeaders.FILENAME`, y llama a `SftpRemoteFileTemplate.send(message, FileExistsMode.REPLACE)`.
3. **`SftpRemoteFileTemplate`** toma una sesión del pool, escribe en el directorio remoto configurado y la devuelve.
4. Respuesta **`201 Created`** con `UploadResponse(remotePath, size, uploadedAt)`.

La operación es **síncrona**: el 201 no vuelve hasta que la escritura remota terminó. Es intencional para esta primera versión — el cliente sabe con certeza si el archivo llegó.

### 3.2 Configuración SFTP

`SftpConfig` expone dos beans:

- **`sftpSessionFactory`** — `DefaultSftpSessionFactory` con host/puerto/usuario/contraseña, envuelto en un **`CachingSessionFactory`**. Sin el pool, cada request abriría un handshake SSH nuevo, que es la parte cara de la operación.
- **`sftpRemoteFileTemplate`** — apunta al directorio remoto vía `LiteralExpression`, con `autoCreateDirectory = true`.

### 3.3 Manejo de errores

`ApiExceptionHandler` mapea cada situación a su código HTTP:

| Situación | Código | Criterio |
|---|---|---|
| Archivo supera el límite del servlet | **413** | Corte técnico previo a toda validación |
| Falta la parte `file` en el multipart | **400** | Petición mal formada |
| Archivo vacío o sin nombre | **400** | Validación de negocio |
| Método HTTP no soportado | **405** | — |
| Fallo de conexión o escritura SFTP | **502** | El problema es del sistema aguas abajo, no de esta API |
| Cualquier otra excepción | **500** | Genérico, con log del stacktrace |

La elección de **502 y no 500** para el fallo SFTP es correcta y deliberada: distingue "yo fallé" de "el servidor STCP no respondió", que es lo que el operador necesita saber para diagnosticar.

## 4. Configuración

Gradiente de externalización en dos archivos:

**`application.yaml`** — exige variables de entorno sin valor por defecto (salvo puerto y opcionales):

```yaml
stcp:
  sftp:
    host: ${STCP_SFTP_HOST}
    port: ${STCP_SFTP_PORT:22}
    user: ${STCP_SFTP_USER}
    password: ${STCP_SFTP_PASSWORD}
    remote-directory: ${STCP_SFTP_REMOTE_DIR:/ENTRADA}
    allow-unknown-keys: ${STCP_SFTP_ALLOW_UNKNOWN_KEYS:false}
```

**`application-local.yaml`** — literales para desarrollo contra el emulador:

```yaml
stcp:
  sftp:
    host: localhost
    port: 2024
    user: stcp
    password: stcp123
    remote-directory: /ENTRADA
    allow-unknown-keys: true
```

El diseño es bueno: **el mismo `.jar` sirve para local y producción**, y la ausencia de defaults en `application.yaml` hace que un despliegue mal configurado falle al arrancar en vez de apuntar silenciosamente al lugar equivocado.

Otros parámetros: `server.port` (8080), `spring.servlet.multipart.max-file-size` y `max-request-size` (50 MB).

> **`allow-unknown-keys` debe ser `false` fuera de local.** En `true`, el cliente acepta cualquier host key sin verificar, lo que abre la puerta a un ataque de intermediario. Está en `true` en local solo porque el contenedor emulado regenera su host key cada vez que se recrea. Contra el STCP real hay que instalar la host key legítima aparte.

## 5. Entorno local

`infra/docker-compose.yaml` levanta dos contenedores (ver `../infra/README.md` para el detalle operativo de Podman en este equipo):

| Servicio | Imagen | Puerto | Función |
|---|---|---|---|
| `stcp-sftp-emulator` | `atmoz/sftp:alpine` | 2024 | Servidor SFTP con la estructura de carpetas de STCP |
| `stcp-sftp-browser` | `filebrowser` | 8081 | Visor web de solo lectura sobre el mismo volumen |

El emulador replica lo observado en la PoC: puerto **2024**, usuario `stcp`, y el árbol `ENTRADA / SAIDA / SAIDA/BACKUP / SAIDA/PENDENTE / TEMP / CONTROLE / FORMATO`.

Reproduce **solo el transporte**. No emula reglas STCPREN, Voltage ni PGP — eso vive en el servidor real y queda fuera del alcance de este servicio.

### Verificación end-to-end

```bash
# 1. Levantar infraestructura
./infra/up.ps1

# 2. Arrancar el servicio
./gradlew bootRun

# 3. Subir un archivo
curl -F "file=@prueba.txt" http://localhost:8080/api/v1/files

# 4. Confirmar la llegada
#    - Visor web: http://localhost:8081  → carpeta ENTRADA
#    - O por SFTP: sftp -P 2024 stcp@localhost
```

Respuesta esperada:

```json
{ "remotePath": "/ENTRADA/prueba.txt", "size": 1234, "uploadedAt": "..." }
```

## 6. Migración a producción

El cambio a STCP Gemini real es **solo de configuración**, sin tocar código:

| Variable | Local | Producción |
|---|---|---|
| `STCP_SFTP_HOST` | `localhost` | host real del STCP |
| `STCP_SFTP_PORT` | `2024` | puerto real |
| `STCP_SFTP_USER` / `PASSWORD` | `stcp` / `stcp123` | credenciales del usuario STCP |
| `STCP_SFTP_ALLOW_UNKNOWN_KEYS` | `true` | **`false`** + host key instalada |
| `PROFILE` | `local` | perfil productivo |

**Antes de migrar hay que confirmar** la inconsistencia registrada en `STCP-GEMINI.md` §8.1: la configuración de la PoC dice *"SFTP con SSL/TLS habilitado"*, lo cual es contradictorio. Si el servidor real habla **FTPS** y no SFTP, este servicio no sirve tal cual — habría que cambiar el cliente por uno FTPS, que es otra librería y otro modelo de conexión.

## 7. Fuera de alcance (versión actual)

Decisiones conscientes, documentadas en `PLAN.md`:

- **Reglas STCPREN, Voltage, PGP** — responsabilidad del servidor STCP.
- **Autenticación por llave SSH** — se usa usuario/contraseña; es un cambio de configuración menor.
- **Reintentos y cola asíncrona** — la subida es directa y síncrona.

## 8. Puntos abiertos

| # | Punto | Detalle |
|---|---|---|
| 1 | **Validación del nombre de archivo** | `getOriginalFilename()` llega del cliente sin validar y determina la ruta remota. Riesgo de *path traversal* (`../../SAIDA/x.txt`) y de alimentar la inyección de comandos de STCPREN (`STCP-GEMINI.md` §4.3, donde `$FILEOLDNAME` se expande sin comillas en un shell). Este servicio es el lugar natural y barato para atajarlo: validar contra la convención de `§8.4` o contra `^[A-Za-z0-9._-]+$` |
| 2 | **Sin autenticación en el endpoint** | Cualquiera con acceso de red puede depositar archivos en STCP |
| 3 | **`FileExistsMode.REPLACE`** | Un reenvío sobrescribe el archivo previo sin aviso. Como el nombre codifica fecha y hora, conviene decidir si eso es lo deseado o si corresponde `FAIL` |
| 4 | **Archivo completo en memoria** | `getBytes()` + `ByteArrayInputStream` cargan todo el contenido. Con 50 MB y concurrencia alta puede presionar el heap; evaluar streaming |
| 5 | **Sin tests** | `build.gradle` ya define los source sets `test` e `integrationTest`, pero no hay clases todavía |
| 6 | **Confirmar SFTP vs FTPS** del servidor real | Ver §6 |
