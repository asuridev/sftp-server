# Levanta la infra local forzando la conexión ROOTLESS de Podman.
#
# Sin CONTAINER_CONNECTION, podman-compose usa la conexión por defecto (rootful, que
# Podman reimpone en cada arranque de la máquina). En rootful los puertos se publican
# con DNAT sin socket en escucha y `localhost:2024` / `localhost:8081` no responden
# desde Windows. Ver infra/README.md.

$ErrorActionPreference = "Stop"
$env:CONTAINER_CONNECTION = "podman-machine-default"

$composeFile = Join-Path $PSScriptRoot "docker-compose.yaml"

# Contenedores duplicados en rootful roban los puertos y el arranque falla con
# "address already in use", así que se limpian antes de levantar.
$env:CONTAINER_CONNECTION = "podman-machine-default-root"
podman rm -f stcp-sftp-emulator stcp-sftp-browser 2>$null | Out-Null

$env:CONTAINER_CONNECTION = "podman-machine-default"
podman-compose -f $composeFile up -d

Write-Output ""
Write-Output "SFTP  -> localhost:2024 (usuario: stcp / clave: stcp123)"
Write-Output "Visor -> http://localhost:8081"
