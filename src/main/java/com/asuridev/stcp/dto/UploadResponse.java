package com.asuridev.stcp.dto;

import java.time.Instant;

public record UploadResponse(String remotePath, long size, Instant uploadedAt) {
}
