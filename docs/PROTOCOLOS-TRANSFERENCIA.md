# Protocolos de transferencia de archivos: FTP, SFTP y OFTP

> Documento de referencia. Aclara la naturaleza normativa y las diferencias técnicas entre los tres protocolos que aparecen en la arquitectura de STCP Gemini (ver `STCP-GEMINI.md`).

## 1. Estatus normativo

Los tres protocolos tienen naturaleza de estándar **muy distinta**, y esa es la diferencia más importante entre ellos.

| | **FTP** | **SFTP** | **OFTP** |
|---|---|---|---|
| Documento | **RFC 959** (oct 1985) | `draft-ietf-secsh-filexfer` v00–v13 (2001–2006) | **RFC 5024** (OFTP 2.0, 2007) |
| Estatus IETF | **Internet Standard** — STD 9 | **Ninguno. Todos los drafts expiraron** | **Informational** |
| Organismo | IETF | IETF (intento fallido) | **Odette International** (industria automotriz europea); el RFC solo publica la spec |
| Obsoleta a | — | — | RFC 2204 (OFTP 1.3, 1997) |

Puntos finos que suelen malinterpretarse:

- **SFTP no es un estándar.** La estandarización murió por falta de consenso sobre qué versión adoptar: OpenSSH se quedó en **v3** y no piensa moverse, mientras varios productos comerciales implementaron **v6**. Lo que sí es estándar formal es la capa sobre la que corre: **SSH-2** (RFC 4251/4252/4253/4254, Standards Track, 2006). SFTP es un *subsystem* de SSH.
- **OFTP es "Informational"** porque el dueño de la especificación no es el IETF sino Odette. El RFC existe para que la industria tenga una referencia pública citable, no para que el IETF la gobierne.

## 2. Diferencias técnicas

| | **FTP** | **SFTP** | **OFTP2** |
|---|---|---|---|
| Puertos | 21 control + 20 datos (activo) o puerto alto (pasivo) | **22** (una sola conexión) | **3305** plano / **6619** TLS |
| Conexiones | **Dos canales separados** — el problema clásico con firewalls | Una sola, multiplexada sobre SSH | Una sola |
| Seguridad nativa | **Ninguna.** Usuario, clave y datos en claro | SSH: cifrado, integridad y autenticación (clave pública o password) | TLS en sesión **+ CMS a nivel de archivo** |
| Autenticación | Usuario/clave en texto plano | Claves SSH, password, GSSAPI | **SSID/SFID** con X.509 y desafío mutuo |
| Acuse de recibo | No | No | **EERP / NERP** firmados — no repudio de entrega |
| Modelo de datos | Rutas del filesystem | Rutas del filesystem (+ `stat`, permisos, atributos) | **Virtual File**: nombre lógico, timestamp, formato de registro — abstraído del filesystem |
| Interactivo | Sí | Sí | **No** — push/pull automatizado entre partners |
| Transporte | TCP/IP | TCP/IP | TCP/IP, **X.25, ISDN** |
| Tamaño máximo | Sin límite de protocolo | Sin límite de protocolo | Hasta **9 PB** |
| Compresión | No | No (la aporta SSH, opcional) | Sí, vía CMS |

## 3. La trampa de nombres: SFTP ≠ FTPS ≠ FTP

- **FTP** — RFC 959, sin cifrado.
- **FTPS** — FTP + TLS. **RFC 4217** (explícito, comando `AUTH TLS` sobre el puerto 21) o implícito en el puerto 990. Extiende RFC 2228. Sigue siendo FTP con sus dos canales, con los mismos problemas de firewall.
- **SFTP** — no tiene relación con FTP. Es un protocolo distinto, sobre SSH, un solo canal, puerto 22.

**Consecuencia práctica:** la expresión *"protocolo SFTP con SSL/TLS habilitado"* es contradictoria. **SFTP nunca usa TLS.** Si una configuración dice eso, o el campo está mal leído o lo que realmente hay es FTPS. Conviene confirmarlo antes de dimensionar una PoC, porque cambia el cliente, el puerto y el tipo de certificado requerido.

## 4. Cómo se apilan

```
FTP        FTPS              SFTP              OFTP2
 │          │                 │                  │
 │        [TLS]           [SSH-2]          [TLS opcional]   ← seguridad de canal
 │          │                 │                  │
 └──────────┴─────── TCP/IP ──┴──────────────────┴── (+ X.25 / ISDN solo OFTP)
```

OFTP2 es el único que además cifra y firma **el archivo mismo** (CMS, RFC 5652).

Diferencia práctica: con SFTP o FTPS, cuando el archivo aterriza en disco queda en claro y el canal ya no lo protege. Con OFTP2 el payload sigue firmado y cifrado extremo a extremo aunque pase por un VAN o por saltos intermedios.

## 5. Implicaciones para la arquitectura STCP Gemini

Referencias a secciones de `STCP-GEMINI.md`:

- Que STCP ofrezca **OFTP en el borde HR** (§2.1–2.2) responde a un requisito real: un intercambio EDI B2B con no repudio. El **EERP firmado** es la prueba de entrega; SFTP no tiene equivalente a nivel de protocolo.
- Que el tramo interno **HR→LR sea SFTP/SSH** (§2.3) es coherente: dentro del perímetro no hace falta no repudio, alcanza con el canal seguro.
- El pipeline **PGP/GPG** (§5.3) reimplementa manualmente lo que OFTP2 hace de forma nativa con CMS: cifrado a nivel de archivo. Si el partner soporta OFTP2, ese pipeline podría ser redundante.
- **FTP plano no aparece en la arquitectura**, y correctamente: sería inaceptable en la zona de alto riesgo.

## 6. Regla práctica de selección

| Necesidad | Protocolo |
|---|---|
| Mover un archivo por un canal seguro hasta un servidor, integración genérica | **SFTP** |
| Prueba no repudiable de que el partner recibió el archivo, y protección del contenido más allá del canal | **OFTP2** |
| Compatibilidad con un sistema legado que solo habla FTP, dentro de red confiable | **FTPS** (nunca FTP plano hacia el exterior) |

## 7. Referencias

- [RFC 959 — File Transfer Protocol](https://datatracker.ietf.org/doc/html/rfc959)
- [RFC 4217 — Securing FTP with TLS](https://datatracker.ietf.org/doc/html/rfc4217)
- [RFC 2228 — FTP Security Extensions](https://datatracker.ietf.org/doc/html/rfc2228)
- [draft-ietf-secsh-filexfer-13 — SSH File Transfer Protocol](https://datatracker.ietf.org/doc/html/draft-ietf-secsh-filexfer-13)
- [RFC 4251 — SSH Protocol Architecture](https://datatracker.ietf.org/doc/html/rfc4251)
- [RFC 5024 — ODETTE File Transfer Protocol 2.0](https://www.rfc-editor.org/rfc/rfc5024.html)
- [RFC 2204 — ODETTE File Transfer Protocol (obsoleta)](https://datatracker.ietf.org/doc/html/rfc2204)
- [RFC 5652 — Cryptographic Message Syntax (CMS)](https://datatracker.ietf.org/doc/html/rfc5652)
- [SFTP Standards — sftp.net](https://www.sftp.net/specification)
