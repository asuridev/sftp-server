package com.asuridev.stcp.controller;

import com.asuridev.stcp.dto.FileUpload;
import com.asuridev.stcp.dto.UploadResponse;
import com.asuridev.stcp.exception.BadRequestException;
import com.asuridev.stcp.service.StcpUploadService;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1")
public class FileUploadController {

    private final StcpUploadService uploadService;

    public FileUploadController(StcpUploadService uploadService) {
        this.uploadService = uploadService;
    }

    @PostMapping(value = "/files", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<UploadResponse> uploadFile(@RequestPart("file") MultipartFile file) {
        UploadResponse response = uploadService.upload(toFileUpload(file));
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    private static FileUpload toFileUpload(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BadRequestException("No se recibió ningún archivo");
        }
        try {
            return new FileUpload(file.getBytes(), file.getOriginalFilename(), file.getContentType(), file.getSize());
        } catch (IOException exception) {
            throw new BadRequestException("No se pudo leer el archivo enviado");
        }
    }
}
