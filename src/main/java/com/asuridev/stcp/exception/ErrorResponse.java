package com.asuridev.stcp.exception;

import java.time.Instant;

/**
 * Contrato de error uniforme para toda la API.
 */
public record ErrorResponse(Instant timestamp, int status, String error, String message) {

    public ErrorResponse(int status, String error, String message) {
        this(Instant.now(), status, error, message);
    }
}
