package ru.usb.s3failover.controller;

import ru.usb.s3failover.service.S3HostStatus;
import ru.usb.s3failover.service.S3Service;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.List;

@RestController
@RequestMapping("/api/s3")
@Tag(name = "S3 Storage", description = "API для работы с S3/MinIO хранилищем")
public class S3Controller {

    private final S3Service    s3Service;
    private final S3HostStatus hostStatus;

    public S3Controller(S3Service s3Service, S3HostStatus hostStatus) {
        this.s3Service  = s3Service;
        this.hostStatus = hostStatus;
    }

    @Operation(summary = "Health check сервиса")
    @GetMapping("/health")
    public ResponseEntity<java.util.Map<String, Object>> health() {
        boolean primaryOk = hostStatus.getPrimary().isAvailable();
        boolean backupOk  = hostStatus.getBackup().isAvailable();
        boolean up        = primaryOk || backupOk;
        java.util.Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("status",  up ? "UP" : "DOWN");
        body.put("primary", primaryOk ? "UP" : "DOWN");
        body.put("backup",  backupOk  ? "UP" : "DOWN");
        return ResponseEntity.status(up ? 200 : 503).body(body);
    }

    @Operation(summary = "Статус доступности S3 хостов")
    @GetMapping("/status")
    public ResponseEntity<java.util.Map<String, Object>> status() {
        S3HostStatus.HostStatus p = hostStatus.getPrimary();
        S3HostStatus.HostStatus b = hostStatus.getBackup();
        return ResponseEntity.ok(java.util.Map.of(
                "primary", statusMap(p),
                "backup",  statusMap(b)));
    }

    private java.util.Map<String, Object> statusMap(S3HostStatus.HostStatus s) {
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("available",       s.isAvailable());
        m.put("hostAvailable",   s.hostAvailable());
        m.put("bucketAvailable", s.bucketAvailable());
        m.put("checkedAt",       s.checkedAt() != null ? s.checkedAt().toString() : null);
        m.put("error",           s.error());
        return m;
    }

    @Operation(summary = "Загрузить файл в бакет")
    @PostMapping("/{bucket}/upload")
    public ResponseEntity<String> upload(
            @Parameter(description = "Имя бакета") @PathVariable String bucket,
            @Parameter(description = "Ключ (имя файла) в хранилище") @RequestParam String key,
            @Parameter(description = "Файл для загрузки") @RequestParam MultipartFile file) throws IOException {
        s3Service.upload(bucket, key, file);
        return ResponseEntity.ok("Uploaded: " + key);
    }

    @Operation(summary = "Скачать файл из бакета")
    @GetMapping("/{bucket}/download")
    public ResponseEntity<InputStreamResource> download(
            @Parameter(description = "Имя бакета") @PathVariable String bucket,
            @Parameter(description = "Ключ (имя файла) в хранилище") @RequestParam String key) {
        ResponseInputStream<GetObjectResponse> obj = s3Service.download(bucket, key);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + key + "\"")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(new InputStreamResource(obj));
    }

    @Operation(summary = "Получить список объектов в бакете")
    @GetMapping("/{bucket}/list")
    public ResponseEntity<List<String>> list(
            @Parameter(description = "Имя бакета") @PathVariable String bucket) {
        return ResponseEntity.ok(s3Service.list(bucket));
    }

    @Operation(summary = "Удалить файл из бакета")
    @DeleteMapping("/{bucket}/delete")
    public ResponseEntity<String> delete(
            @Parameter(description = "Имя бакета") @PathVariable String bucket,
            @Parameter(description = "Ключ (имя файла) в хранилище") @RequestParam String key) {
        s3Service.delete(bucket, key);
        return ResponseEntity.ok("Deleted: " + key);
    }

    @Operation(summary = "Получить Presigned URL для скачивания файла")
    @GetMapping("/{bucket}/presign/download")
    public ResponseEntity<String> presignedDownload(
            @Parameter(description = "Имя бакета") @PathVariable String bucket,
            @Parameter(description = "Ключ (имя файла) в хранилище") @RequestParam String key,
            @Parameter(description = "Время жизни ссылки в минутах") @RequestParam(defaultValue = "60") long expiresIn) {
        return ResponseEntity.ok(s3Service.presignedGetUrl(bucket, key, expiresIn));
    }

    @Operation(summary = "Получить Presigned URL для загрузки файла")
    @GetMapping("/{bucket}/presign/upload")
    public ResponseEntity<String> presignedUpload(
            @Parameter(description = "Имя бакета") @PathVariable String bucket,
            @Parameter(description = "Ключ (имя файла) в хранилище") @RequestParam String key,
            @Parameter(description = "Время жизни ссылки в минутах") @RequestParam(defaultValue = "60") long expiresIn) {
        return ResponseEntity.ok(s3Service.presignedPutUrl(bucket, key, expiresIn));
    }

    /**
     * Генерирует Presigned URL для произвольного S3-совместимого хранилища.
     * Все параметры подключения передаются явно — метод не использует конфигурацию приложения.
     * Предназначен для ручной проверки и отладки.
     *
     * @param host      полный URL хоста хранилища, например https://s3.example.com
     * @param bucket    имя бакета
     * @param key       ключ (путь) объекта в бакете
     * @param accessKey AWS Access Key
     * @param secretKey AWS Secret Key
     * @param region    регион, например us-east-1
     * @param expiresIn время жизни ссылки в минутах
     * @param method    тип операции: GET (скачивание) или PUT (загрузка)
     * @return сгенерированный Presigned URL
     */
    @Operation(summary = "Сгенерировать Presigned URL для произвольного хранилища (для проверки)")
    @GetMapping("/presign/custom")
    public ResponseEntity<String> presignCustom(
            @Parameter(description = "URL хоста хранилища, например https://s3.example.com") @RequestParam String host,
            @Parameter(description = "Имя бакета") @RequestParam String bucket,
            @Parameter(description = "Ключ (путь) объекта в бакете") @RequestParam String key,
            @Parameter(description = "AWS Access Key") @RequestParam String accessKey,
            @Parameter(description = "AWS Secret Key") @RequestParam String secretKey,
            @Parameter(description = "Регион, например us-east-1") @RequestParam(defaultValue = "us-east-1") String region,
            @Parameter(description = "Время жизни ссылки в минутах") @RequestParam(defaultValue = "60") long expiresIn,
            @Parameter(description = "Тип операции: GET или PUT") @RequestParam(defaultValue = "GET") String method) {

        S3Presigner presigner = S3Presigner.builder()
                .endpointOverride(URI.create(host))
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKey, secretKey)))
                .build();

        try {
            String url;
            if ("PUT".equalsIgnoreCase(method)) {
                url = presigner.presignPutObject(PutObjectPresignRequest.builder()
                        .signatureDuration(Duration.ofMinutes(expiresIn))
                        .putObjectRequest(PutObjectRequest.builder().bucket(bucket).key(key).build())
                        .build()).url().toString();
            } else {
                url = presigner.presignGetObject(GetObjectPresignRequest.builder()
                        .signatureDuration(Duration.ofMinutes(expiresIn))
                        .getObjectRequest(GetObjectRequest.builder().bucket(bucket).key(key).build())
                        .build()).url().toString();
            }
            return ResponseEntity.ok(url);
        } finally {
            presigner.close();
        }
    }
}
