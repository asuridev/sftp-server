# Infraestructura local (Podman)

Emula el servidor STCP Gemini para desarrollo local:

| Servicio | Contenedor | Puerto | Para qué |
|---|---|---|---|
| SFTP | `stcp-sftp-emulator` (`atmoz/sftp`) | `2024` | Recibe los archivos que sube el servicio Spring Boot |
| Visor web | `stcp-sftp-browser` (`filebrowser`) | `8081` | Ver los archivos subidos desde el navegador |

Estructura de carpetas (`ENTRADA/SAIDA/TEMP/CONTROLE/FORMATO`) y puerto `2024` replican
lo observado en la PoC real de STCP Gemini (ver `docs/STCP-GEMINI.md`, sección 8).

## Levantar

Usa los scripts: fijan solos la conexión correcta y limpian contenedores duplicados.

```powershell
.\infra\up.ps1     # levantar
.\infra\down.ps1   # bajar
```

Si prefieres el comando a mano, la variable de entorno **no es opcional** (ver nota abajo):

```powershell
$env:CONTAINER_CONNECTION = "podman-machine-default"
podman-compose -f infra/docker-compose.yaml up -d
```

```bash
export CONTAINER_CONNECTION=podman-machine-default
podman-compose -f infra/docker-compose.yaml up -d
```

Luego:
- Visor web: <http://localhost:8081> (sin login)
- SFTP: `localhost:2024`, usuario `stcp`, contraseña `stcp123`

## Nota importante: usar SIEMPRE la conexión rootless

`CONTAINER_CONNECTION=podman-machine-default` (rootless) **no es opcional** en este equipo.

La máquina de Podman está creada en modo **rootful**, y Podman la deja como conexión por
defecto en cada arranque. El problema es cómo publica los puertos cada modo:

- **rootful** → reglas **DNAT de nftables**, sin socket en escucha dentro de la VM.
  El reenvío de `localhost` de WSL solo relaya puertos que tienen un **socket real**,
  así que los puertos publicados nunca llegan a Windows: `localhost:2024` y
  `localhost:8081` dan "connection refused" (solo funcionan por la IP de la VM).
- **rootless** → `rootlessport` abre un **socket real** en la VM, WSL lo detecta y
  `localhost` funciona normalmente.

Diagnóstico rápido si `localhost` deja de responder:

```bash
# ¿hay socket en escucha dentro de la VM?
podman machine ssh podman-machine-default -- "ss -tlnp | grep -E '2024|8081'"
```

- Si aparece `rootlessport` → todo bien.
- Si no aparece nada → los contenedores se levantaron en rootful. Bájalos y vuelve a
  subirlos con `CONTAINER_CONNECTION=podman-machine-default` (o usa `up.ps1`).

### Error "address already in use" al levantar

```
Error: unable to start container ...: cannot bind tcp port :2024: address already in use
```

Significa que hay contenedores **duplicados en la otra conexión**: rootful y rootless
tienen almacenes de contenedores separados, así que un `podman-compose up` sin
`CONTAINER_CONNECTION` crea un segundo juego de contenedores en rootful que choca con
los puertos que ya tienen los de rootless. `up.ps1` limpia esto automáticamente; a mano:

```powershell
$env:CONTAINER_CONNECTION = "podman-machine-default-root"
podman rm -f stcp-sftp-emulator stcp-sftp-browser
```

Para ver qué hay en cada lado:

```powershell
$env:CONTAINER_CONNECTION = "podman-machine-default"; podman ps -a       # rootless
$env:CONTAINER_CONNECTION = "podman-machine-default-root"; podman ps -a  # rootful
```

### Alternativa permanente (opcional)

Para no tener que exportar la variable cada vez, se puede pasar la máquina a rootless:

```bash
podman machine stop podman-machine-default
podman machine set --rootful=false podman-machine-default
podman machine start podman-machine-default
```

Si `set` falla con "Acceso denegado", cierra Podman Desktop (mantiene bloqueado el
archivo de configuración de la máquina) y reintenta.

## Otros problemas conocidos en este equipo

- **`podman compose` (subcomando nativo) no funciona**: delega en un `docker-compose.exe`
  que no logra conectar al socket de Podman. Usar siempre **`podman-compose`** (el script
  de Python).
- **`win-sshproxy.exe` falla al arrancar** (exit code 1) → Podman Desktop no lista ningún
  contenedor. Causa: este equipo tiene endurecido el registro de eventos de Windows
  (`HKLM\SYSTEM\CurrentControlSet\Services\EventLog\Application` → `CustomSD` =
  `O:BAG:SYD:(A;;0x2;;;SY)(A;;0x2;;;BA)(A;;0x1;;;ER)`), que solo permite **escritura a
  SYSTEM y Administradores**. `win-sshproxy` escribe su log en la fuente ".NET Runtime"
  de ese registro y no tiene opción para desactivarlo, así que sin elevación muere con
  "Error setting up logging: Acceso denegado" y nunca crea el named pipe
  `\\.\pipe\podman-machine-default` que usa Podman Desktop.

  **Solución**: arrancar la máquina **como Administrador** (hay que repetirlo cada vez
  que se reinicie la máquina o Windows):

  ```powershell
  # en una PowerShell ABIERTA COMO ADMINISTRADOR
  podman machine stop podman-machine-default
  podman machine start podman-machine-default
  ```

  Para comprobar que quedó bien: debe existir el pipe y el proceso.

  ```powershell
  [System.IO.Directory]::GetFiles("\\.\pipe\") | Where-Object { $_ -like "*podman*" }
  Get-Process win-sshproxy
  ```

  Ojo: reiniciar la máquina **detiene los contenedores**; hay que volver a levantarlos
  con `CONTAINER_CONNECTION=podman-machine-default` (ver arriba).
- **Tras actualizar Podman**, el ejecutable pasó de `C:\Program Files\RedHat\Podman` a
  `C:\Users\<usuario>\AppData\Local\Programs\Podman`. Las terminales abiertas de antes
  siguen con el PATH viejo y fallan con "podman: command not found" (o `podman-compose`
  lanza `FileNotFoundError`): basta abrir una terminal nueva.

## Bajar

```bash
podman-compose -f infra/docker-compose.yaml down
```
