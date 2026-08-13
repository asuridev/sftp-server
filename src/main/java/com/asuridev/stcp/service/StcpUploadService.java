package com.asuridev.stcp.service;

import com.asuridev.stcp.dto.FileUpload;
import com.asuridev.stcp.dto.UploadResponse;
import com.asuridev.stcp.exception.BadRequestException;
import com.asuridev.stcp.exception.UploadFailedException;
import java.io.ByteArrayInputStream;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.integration.file.FileHeaders;
import org.springframework.integration.file.support.FileExistsMode;
import org.springframework.integration.sftp.session.SftpRemoteFileTemplate;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Service;

@Service
public class StcpUploadService {

    private static final Logger log = LoggerFactory.getLogger(StcpUploadService.class);

    private final SftpRemoteFileTemplate sftpRemoteFileTemplate;

    public StcpUploadService(SftpRemoteFileTemplate sftpRemoteFileTemplate) {
        this.sftpRemoteFileTemplate = sftpRemoteFileTemplate;
    }

    public UploadResponse upload(FileUpload file) {
        if (file.content() == null || file.content().length == 0) {
            throw new BadRequestException("El archivo enviado está vacío");
        }
        if (file.filename() == null || file.filename().isBlank()) {
            throw new BadRequestException("El archivo enviado no tiene nombre");
        }

        Message<ByteArrayInputStream> message = MessageBuilder
                .withPayload(new ByteArrayInputStream(file.content()))
                .setHeader(FileHeaders.FILENAME, file.filename())
                .build();

        String remotePath;
        try {
            remotePath = sftpRemoteFileTemplate.send(message, FileExistsMode.REPLACE);
        } catch (Exception exception) {
            throw new UploadFailedException(
                    "No se pudo subir '" + file.filename() + "' al servidor STCP", exception);
        }

        log.info("Archivo '{}' subido a STCP en '{}' ({} bytes)", file.filename(), remotePath, file.size());
        return new UploadResponse(remotePath, file.size(), Instant.now());
    }
}
