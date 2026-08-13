package com.asuridev.stcp.dto;

/**
 * Contenido de una subida multipart, desacoplado de Servlet API justo en el controller.
 */
public record FileUpload(byte[] content, String filename, String contentType, long size) {
}
