package com.asuridev.stcp.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Datos de conexión al servidor SFTP de STCP. En local apuntan al contenedor
 * emulado de infra/docker-compose.yaml; en otros ambientes, al STCP Gemini real
 * (mismo código, solo cambian estos valores vía variables de entorno).
 */
@ConfigurationProperties("stcp.sftp")
public record SftpProperties(
        String host,
        int port,
        String user,
        String password,
        String remoteDirectory,
        boolean allowUnknownKeys) {
}
