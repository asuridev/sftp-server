# Baja la infra local (conexión ROOTLESS, la misma que usa up.ps1).

$ErrorActionPreference = "Stop"
$env:CONTAINER_CONNECTION = "podman-machine-default"

$composeFile = Join-Path $PSScriptRoot "docker-compose.yaml"
podman-compose -f $composeFile down
