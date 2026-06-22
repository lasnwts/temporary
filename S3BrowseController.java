package ru.usb.s3failover.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import ru.usb.s3failover.config.LG;
import ru.usb.s3failover.config.S3BackupProperties;
import ru.usb.s3failover.config.S3Properties;
import jakarta.servlet.http.HttpServletRequest;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.net.*;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

// imports опущены для краткости (оставь как у тебя)

@RestController
@RequestMapping("")
@Tag(name = "S3 Browse", description = "Просмотр бакетов и файлов S3 с валидацией AWS4 подписи")
public class S3BrowseController {

    private static final Logger log = LoggerFactory.getLogger(S3BrowseController.class);
    private static final Set<Integer> FAILOVER_STATUSES = Set.of(500, 502, 503);
    private static final Set<String> BYPASS_PREFIXES = Set.of("/actuator", "/swagger-ui", "/api-docs", "/api/");

    private final S3Properties props;
    private final S3BackupProperties backupProps;
    private final HttpClient httpClient;

    @Value("${s3.browse.hostId}")
    private String hostId;

    public S3BrowseController(S3Properties props, S3BackupProperties backupProps,
                              @Value("${s3.browse.timeout-ms:3000}") long timeoutMs,
                              @Value("${ssl.verify:true}") boolean sslVerify)
            throws NoSuchAlgorithmException, KeyManagementException {

        this.props = props;
        this.backupProps = backupProps;
        this.httpClient = createHttpClient(timeoutMs, sslVerify);
    }

    // -------------------------------------------------------------------------
    // Failover core
    // -------------------------------------------------------------------------

    private ResponseEntity<?> sendWithFailover(HttpServletRequest request, String path,
                                               String method, byte[] body) {

        String query   = request.getQueryString();
        String amzDate = request.getHeader("x-amz-date");
        String region  = extractCredentialPart(request.getHeader("authorization"), 2);

        // PRIMARY
        try {
            HttpRequest s3Request = buildS3Request(method, body, path, query, amzDate, region,
                    props.getUrl(), props.getAccessKey(), props.getSecretKey(),
                    request.getContentType());

            HttpResponse<byte[]> response = httpClient.send(s3Request, HttpResponse.BodyHandlers.ofByteArray());

            if (!FAILOVER_STATUSES.contains(response.statusCode())) {
                return buildResponse(response, path);
            }

        } catch (Exception e) {
            log.warn("Primary failed: {}", e.getMessage());
        }

        // BACKUP
        try {
            HttpRequest backupRequest = buildS3Request(method, body, path, query, amzDate, region,
                    backupProps.getUrl(), backupProps.getAccessKey(), backupProps.getSecretKey(),
                    request.getContentType());

            HttpResponse<byte[]> response = httpClient.send(backupRequest, HttpResponse.BodyHandlers.ofByteArray());

            return buildResponse(response, path);

        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // 🔥 ОБНОВЛЁННЫЙ buildResponse (главное изменение)
    // -------------------------------------------------------------------------

    private ResponseEntity<?> buildResponse(HttpResponse<byte[]> response, String path) {
        ResponseEntity.BodyBuilder rb = ResponseEntity.status(response.statusCode());

        // Content-Type
        String ct = response.headers().firstValue("content-type")
                .orElse(MediaType.APPLICATION_OCTET_STREAM_VALUE);
        rb.contentType(MediaType.parseMediaType(ct));

        // --- Content-Disposition fallback ---
        String fileName = extractFileName(path);

        String disposition = response.headers().firstValue("content-disposition")
                .orElseGet(() -> {
                    String encoded = URLEncoder.encode(fileName, StandardCharsets.UTF_8)
                            .replace("+", "%20");

                    String fallback = "attachment; filename*=UTF-8''" + encoded;

                    log.warn("Content-Disposition missing → fallback used: {}", fallback);
                    return fallback;
                });

        rb.header("Content-Disposition", disposition);

        // остальные заголовки
        response.headers().firstValue("etag").ifPresent(v -> rb.header("ETag", v));
        response.headers().firstValue("location").ifPresent(v -> rb.header("Location", v));
        response.headers().firstValue("content-length").ifPresent(v -> rb.header("Content-Length", v));

        return rb.body(response.body());
    }

    // -------------------------------------------------------------------------
    // 🔧 helper для имени файла
    // -------------------------------------------------------------------------

    private String extractFileName(String path) {
        try {
            if (path == null || path.isEmpty()) return "file";

            String decoded = URLDecoder.decode(path, StandardCharsets.UTF_8);

            if (decoded.contains("/")) {
                String name = decoded.substring(decoded.lastIndexOf('/') + 1);
                return name.isEmpty() ? "file" : name;
            }

            return decoded;
        } catch (Exception e) {
            log.warn("Filename extraction error: {}", e.getMessage());
            return "file";
        }
    }

    // -------------------------------------------------------------------------
    // остальной код (БЕЗ ИЗМЕНЕНИЙ)
    // -------------------------------------------------------------------------

    private HttpClient createHttpClient(long timeoutMs, boolean sslVerify)
            throws NoSuchAlgorithmException, KeyManagementException {

        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(timeoutMs));

        if (!sslVerify) {
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, new TrustManager[]{
                    new X509TrustManager() {
                        public X509Certificate[] getAcceptedIssuers() { return null; }
                        public void checkClientTrusted(X509Certificate[] c, String a) {}
                        public void checkServerTrusted(X509Certificate[] c, String a) {}
                    }
            }, new java.security.SecureRandom());
            builder.sslContext(sslContext);
        }

        return builder.build();
    }

    /**
     * Формирует подписанный HttpRequest для любого метода и любого хоста S3.
     */
    /**
     * Формирует подписанный HttpRequest для отправки на S3.
     * Вычисляет AWS4 подпись: canonical request → string to sign → HMAC-SHA256.
     * Для стандартных портов (80, 443) порт в host header опускается.
     * Для GET/DELETE тело пустое — используется хэш пустой строки.
     */
    private HttpRequest buildS3Request(String method, byte[] body, String path, String query,
                                       String amzDate, String region,
                                       String baseUrl, String accessKey, String secretKey,
                                       String contentType) throws Exception {
        log.debug("{} Building S3 request: method={}, path={}, query={}, amzDate={}, region={}, baseUrl={}",
                LG.USBLOGINFO, method, path, query, amzDate, region, baseUrl);
        URI uri = URI.create(baseUrl);
        String host = uri.getHost();
        int port = uri.getPort();
        String s3Host;
        if (port == -1 || port == 443 || port == 80) {
            // Standard ports - omit port in host header
            s3Host = host;
        } else {
            s3Host = host + ":" + port;
        }
        String date   = amzDate.substring(0, 8);

        String contentHash = "GET".equals(method) || "DELETE".equals(method)
                ? "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
                : hex(sha256(body));

        boolean includeContentType = "POST".equals(method) && contentType != null;

        String canonicalHeaders = (includeContentType ? "content-type:" + contentType + "\n" : "")
                + "host:" + s3Host + "\n"
                + "x-amz-content-sha256:" + contentHash + "\n"
                + "x-amz-date:" + amzDate + "\n";

        String signedHeaders = (includeContentType ? "content-type;" : "")
                + "host;x-amz-content-sha256;x-amz-date";

        String canonicalRequest = String.join("\n",
                method, path, query != null ? query : "",
                canonicalHeaders, signedHeaders, contentHash);

        String stringToSign  = buildStringToSign(amzDate, date, region, "s3", canonicalRequest);
        String signature     = calculateSignature(secretKey, date, region, "s3", stringToSign);
        String authorization = "AWS4-HMAC-SHA256 Credential=" + accessKey + "/" + date
                + "/" + region + "/s3/aws4_request"
                + ", SignedHeaders=" + signedHeaders
                + ", Signature=" + signature;

        log.debug("{} Authorization header: {}", LG.USBLOGINFO, authorization);
        log.debug("{} Canonical request:\n{}", LG.USBLOGINFO, canonicalRequest);
        log.debug("{} String to sign:\n{}", LG.USBLOGINFO, stringToSign);
        log.debug("{} Calculated signature: {}", LG.USBLOGINFO, signature);

        String url = baseUrl + path + (query != null ? "?" + query : "");

        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .header("x-amz-date", amzDate)
                .header("x-amz-content-sha256", contentHash)
                .header("Authorization", authorization);

        if (includeContentType) builder.header("Content-Type", contentType);

        switch (method) {
            case "GET"    -> builder.GET();
            case "DELETE" -> builder.DELETE();
            case "PUT"    -> builder.PUT(HttpRequest.BodyPublishers.ofByteArray(body));
            case "POST"   -> builder.POST(HttpRequest.BodyPublishers.ofByteArray(body));
            default       -> builder.method(method, HttpRequest.BodyPublishers.ofByteArray(body));
        }

        if ("PUT".equals(method) && contentType != null) builder.header("Content-Type", contentType);

        return builder.build();
    }

    /**
     * Преобразует ответ HttpClient в ResponseEntity.
     * Пробрасывает заголовки: Content-Type, Content-Disposition, ETag, Location.
     */
    private ResponseEntity<?> buildResponse(HttpResponse<byte[]> response) {
        ResponseEntity.BodyBuilder rb = ResponseEntity.status(response.statusCode());
        String ct = response.headers().firstValue("content-type")
                .orElse(MediaType.APPLICATION_OCTET_STREAM_VALUE);
        rb.contentType(MediaType.parseMediaType(ct));
        response.headers().firstValue("content-disposition").ifPresent(v -> rb.header("Content-Disposition", v));
        response.headers().firstValue("etag").ifPresent(v -> rb.header("ETag", v));
        response.headers().firstValue("location").ifPresent(v -> rb.header("Location", v));
        return rb.body(response.body());
    }

    // -------------------------------------------------------------------------
    // AWS4 validation
    // -------------------------------------------------------------------------

    /**
     * Валидирует AWS Signature V4 из заголовка Authorization входящего запроса.
     * Проверяет: наличие заголовка, accessKey, вычисляет и сравнивает подпись.
     * Возвращает null если подпись корректна, иначе ResponseEntity с XML ошибкой S3.
     */
    private ResponseEntity<?> validateAwsAuth(HttpServletRequest request, String resource) {
        String authorization = request.getHeader("authorization");

        if (authorization == null || !authorization.startsWith("AWS4-HMAC-SHA256")) {
            log.warn("{} Authorization header missing or not AWS4, resource={}", LG.USBLOGWARNING, resource);
            return buildS3AuthError("InvalidAccessKeyId",
                    "The Access Key Id you provided does not exist in our records.", resource);
        }

        String accessKey = extractCredentialPart(authorization, 0);
        String date      = extractCredentialPart(authorization, 1);
        String region    = extractCredentialPart(authorization, 2);
        String service   = extractCredentialPart(authorization, 3);
        String signature = extractSignature(authorization);

        log.info("{} AWS4 auth: accessKey={} date={} region={} service={} resource={}",
                LG.USBLOGINFO, accessKey, date, region, service, resource);

        if (accessKey == null || !accessKey.equals(props.getAccessKey())) {
            log.warn("{} AccessKey MISMATCH: request accessKey={} expected={} resource={}",
                    LG.USBLOGWARNING, accessKey, props.getAccessKey(), resource);
            return buildS3AuthError("InvalidAccessKeyId",
                    "The Access Key Id you provided does not exist in our records.", resource);
        }

        try {
            String amzDate       = request.getHeader("x-amz-date");
            String contentHash   = request.getHeader("x-amz-content-sha256");
            String signedHeaders = extractSignedHeaders(authorization);
            String canonicalRequest  = buildCanonicalRequest(request, signedHeaders, contentHash, resource);
            String stringToSign      = buildStringToSign(amzDate, date, region, service, canonicalRequest);
            String expectedSignature = calculateSignature(props.getSecretKey(), date, region, service, stringToSign);

            log.info("{} Signature check: received={} expected={}", LG.USBLOGINFO, signature, expectedSignature);

            if (!expectedSignature.equals(signature)) {
                log.warn("{} Signature MISMATCH resource={}", LG.USBLOGWARNING, resource);
                return buildS3AuthError("SignatureDoesNotMatch",
                        "The request signature we calculated does not match the signature you provided.", resource);
            }
        } catch (Exception e) {
            log.error("{} Signature verification error: {}", LG.USBLOGERROR, e.getMessage());
            return buildS3AuthError("SignatureDoesNotMatch",
                    "The request signature we calculated does not match the signature you provided.", resource);
        }

        log.info("{} AWS4 auth SUCCESS: accessKey={} resource={}", LG.USBLOGINFO, accessKey, resource);
        return null;
    }

    /**
     * Строит канонический запрос AWS4 из входящего HttpServletRequest.
     * Заголовки сортируются в алфавитном порядке согласно спецификации AWS4.
     */
    private String buildCanonicalRequest(HttpServletRequest request, String signedHeaders,
                                         String contentHash, String resource) {
        String method      = request.getMethod();
        String queryString = request.getQueryString() != null ? request.getQueryString() : "";
        String canonicalHeaders = java.util.Arrays.stream(signedHeaders.split(";"))
                .sorted()
                .map(h -> h + ":" + request.getHeader(h).trim())
                .collect(Collectors.joining("\n")) + "\n";
        return String.join("\n", method, resource, queryString, canonicalHeaders, signedHeaders, contentHash);
    }

    /**
     * Строит строку для подписи (StringToSign) по спецификации AWS4:
     * AWS4-HMAC-SHA256 + дата + credential scope + SHA256 от canonical request.
     */
    private String buildStringToSign(String amzDate, String date, String region,
                                     String service, String canonicalRequest) throws Exception {
        String credentialScope = String.join("/", date, region, service, "aws4_request");
        String hashedCanonical = hex(sha256(canonicalRequest.getBytes(StandardCharsets.UTF_8)));
        return String.join("\n", "AWS4-HMAC-SHA256", amzDate, credentialScope, hashedCanonical);
    }

    /**
     * Вычисляет HMAC-SHA256 подпись AWS4:
     * signing key = HMAC(HMAC(HMAC(HMAC("AWS4"+secretKey, date), region), service), "aws4_request")
     */
    private String calculateSignature(String secretKey, String date, String region,
                                      String service, String stringToSign) throws Exception {
        byte[] kDate    = hmac(("AWS4" + secretKey).getBytes(StandardCharsets.UTF_8), date);
        byte[] kRegion  = hmac(kDate, region);
        byte[] kService = hmac(kRegion, service);
        byte[] kSigning = hmac(kService, "aws4_request");
        return hex(hmac(kSigning, stringToSign));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Проверяет, является ли путь системным (actuator, swagger, api).
     * Такие пути не обрабатываются контроллером и пропускаются дальше.
     */
    private boolean isBypass(String path) {
        return BYPASS_PREFIXES.stream().anyMatch(path::startsWith);
    }

    /** Извлекает часть Credential из заголовка Authorization по индексу: 0=accessKey, 1=date, 2=region, 3=service. */
    private String extractCredentialPart(String authorization, int index) {
        try {
            String credential = java.util.regex.Pattern.compile("Credential=([^,]+)")
                    .matcher(authorization).results().findFirst()
                    .map(m -> m.group(1)).orElse("");
            String[] parts = credential.split("/");
            return parts.length > index ? parts[index] : null;
        } catch (Exception e) { return null; }
    }

    /** Извлекает значение Signature из заголовка Authorization. */
    private String extractSignature(String authorization) {
        try {
            return java.util.regex.Pattern.compile("Signature=([a-f0-9]+)")
                    .matcher(authorization).results().findFirst()
                    .map(m -> m.group(1)).orElse("");
        } catch (Exception e) { return ""; }
    }

    /** Извлекает список SignedHeaders из заголовка Authorization. */
    private String extractSignedHeaders(String authorization) {
        try {
            return java.util.regex.Pattern.compile("SignedHeaders=([^,]+)")
                    .matcher(authorization).results().findFirst()
                    .map(m -> m.group(1)).orElse("");
        } catch (Exception e) { return ""; }
    }

    /** Вычисляет HMAC-SHA256 от data с ключом key. */
    private byte[] hmac(byte[] key, String data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
    }

    /** Вычисляет SHA-256 хэш от массива байт. */
    private byte[] sha256(byte[] data) throws Exception {
        return java.security.MessageDigest.getInstance("SHA-256").digest(data);
    }

    /** Преобразует массив байт в lowercase hex строку. */
    private String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    /**
     * Формирует XML ответ с ошибкой аутентификации в формате S3 (HTTP 403).
     * Используется при невалидном accessKey или неверной подписи.
     */
    private ResponseEntity<String> buildS3AuthError(String code, String message, String resource) {
        String requestId = UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase();
        String xml = String.format(
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                        "<Error>\n" +
                        "    <Code>%s</Code>\n" +
                        "    <Message>%s</Message>\n" +
                        "    <Resource>%s</Resource>\n" +
                        "    <RequestId>%s</RequestId>\n" +
                        "    <HostId>%s</HostId>\n" +
                        "</Error>", code, message, resource, requestId, hostId);
        return ResponseEntity.status(403)
                .contentType(MediaType.APPLICATION_XML)
                .body(xml);
    }

    /** Логирует входящий запрос: метод, URI, все заголовки, IP адрес клиента. */
    private void logRequest(HttpServletRequest request) {
        String headers = Collections.list(request.getHeaderNames()).stream()
                .map(name -> name + "=" + request.getHeader(name))
                .collect(Collectors.joining(", "));
        log.info("{} {} {} | headers: [{}] | remoteAddr={}",
                LG.USBLOGINFO,
                request.getMethod(),
                request.getRequestURI(),
                headers,
                request.getRemoteAddr());
    }

    // -------------------------------------------------------------------------
    // Presign helpers
    // -------------------------------------------------------------------------

    /**
     * Обрабатывает запрос с Presigned URL (параметр X-Amz-Signature в query string).
     * Выполняет валидацию: наличие обязательных параметров, срок действия, подпись.
     * При успехе — проксирует запрос к S3 как обычный AWS4 подписанный запрос.
     * Хост в подписи заменяется: s3.presign.host.source → s3.presign.host.destination.
     */
    private ResponseEntity<?> handlePresigned(HttpServletRequest request, String path) {
        String rawQuery = request.getQueryString();
        log.info("{} GET presigned path={}", LG.USBLOGINFO, path);
        try {
            Map<String, String> params = parsePresignQuery(rawQuery);

            String[] required = {"X-Amz-Algorithm", "X-Amz-Date", "X-Amz-SignedHeaders",
                    "X-Amz-Credential", "X-Amz-Expires", "X-Amz-Signature"};
            for (String p : required) {
                if (!params.containsKey(p)) {
                    log.warn("{} Presigned missing param={} path={}", LG.USBLOGWARNING, p, path);
                    return buildS3AuthError("InvalidArgument", "Missing parameter: " + p, path);
                }
            }

            String amzDate       = params.get("X-Amz-Date");
            long   expiresSeconds = Long.parseLong(params.get("X-Amz-Expires"));
            LocalDateTime signedAt  = LocalDateTime.parse(amzDate,
                    DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'"));
            if (LocalDateTime.now(ZoneOffset.UTC).isAfter(signedAt.plusSeconds(expiresSeconds))) {
                log.warn("{} Presigned URL expired path={}", LG.USBLOGWARNING, path);
                return buildS3AuthError("AccessDenied", "Request has expired.", path);
            }

            String credential    = java.net.URLDecoder.decode(params.get("X-Amz-Credential"), StandardCharsets.UTF_8);
            String[] credParts   = credential.split("/");
            String accessKey     = credParts[0];
            String dateStamp     = credParts[1];
            String region        = credParts[2];
            String service       = credParts[3];
            String signedHeaders = params.get("X-Amz-SignedHeaders");
            String signature     = params.get("X-Amz-Signature");

            if (!accessKey.equals(props.getAccessKey())) {
                log.warn("{} Presigned accessKey mismatch: got={} path={}", LG.USBLOGWARNING, accessKey, path);
                return buildS3AuthError("InvalidAccessKeyId",
                        "The Access Key Id you provided does not exist in our records.", path);
            }

            // Подмена хоста для проверки подписи
            // В Presigned URL указан хост прокси (source), но для проверки подписи нужен хост S3 (destination)
            String hostSource = props.getPresignHostSource();
            String hostDestination = props.getPresignHostDestination();

            // Значения по умолчанию, если свойства не загружены
            if (hostSource == null) hostSource = "s3failover.intgr-t.fc.uralsibbank.ru";
            if (hostDestination == null) hostDestination = "client.cfa-dev.uralsib.ru/s3";

            String host = hostDestination;
            log.info("{} Presigned verify host (replaced {} -> {}) path={}",
                    LG.USBLOGINFO, hostSource, hostDestination, path);
            String canonicalHeaders = buildPresignCanonicalHeaders(request, signedHeaders, host);
            String canonicalQuery   = buildCanonicalQueryString(rawQuery);
            String canonicalRequest = String.join("\n",
                    "GET", path, canonicalQuery, canonicalHeaders, signedHeaders, "UNSIGNED-PAYLOAD");

            String credentialScope = String.join("/", dateStamp, region, service, "aws4_request");
            String stringToSign    = String.join("\n",
                    "AWS4-HMAC-SHA256", amzDate, credentialScope, hex(sha256(canonicalRequest.getBytes(StandardCharsets.UTF_8))));

            String expectedSig = hex(hmac(getSigningKey(props.getSecretKey(), dateStamp, region, service), stringToSign));

            log.info("{} Presigned signature check: received={} expected={} path={}",
                    LG.USBLOGINFO, signature, expectedSig, path);

            if (!expectedSig.equals(signature)) {
                log.warn("{} Presigned signature MISMATCH path={}", LG.USBLOGWARNING, path);
                return buildS3AuthError("SignatureDoesNotMatch",
                        "The request signature we calculated does not match the signature you provided.", path);
            }

            log.info("{} Presigned auth SUCCESS path={}", LG.USBLOGINFO, path);
            return sendWithFailoverPresigned(path, region);

        } catch (Exception e) {
            log.error("{} Presigned validation error path={} error={}", LG.USBLOGERROR, path, e.getMessage());
            return buildS3AuthError("SignatureDoesNotMatch",
                    "The request signature we calculated does not match the signature you provided.", path);
        }
    }

    /**
     * Presigned GET — подпись проверена локально, запрос к S3 выполняется
     * как обычный подписанный GET без presigned query, чтобы избежать
     * конфликта хостов между прокси и хранилищем.
     */
    /**
     * Выполняет GET запрос к S3 после успешной валидации Presigned URL.
     * Создаёт новую AWS4 подпись с текущим временем (presigned query не используется).
     * При недоступности primary переключается на backup, подставляя бакет из s3backup.bucket.base.
     */
    private ResponseEntity<?> sendWithFailoverPresigned(String path, String region) {
        String amzDate = java.time.ZonedDateTime.now(ZoneOffset.UTC)
                .format(DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'"));
        String fileName = java.net.URLDecoder.decode(
                path.contains("/") ? path.substring(path.lastIndexOf('/') + 1) : path,
                StandardCharsets.UTF_8);
        log.info("{} GET presigned fileName={} path={}", LG.USBLOGINFO, fileName, path);

        // primary
        try {
            HttpRequest s3Request = buildS3Request("GET", new byte[0], path, null, amzDate, region,
                    props.getUrl(), props.getAccessKey(), props.getSecretKey(), null);
            log.info("{} GET presigned -> primary: url={}", LG.USBLOGINFO, props.getUrl() + path);
            HttpResponse<byte[]> response = httpClient.send(s3Request, HttpResponse.BodyHandlers.ofByteArray());
            if (!FAILOVER_STATUSES.contains(response.statusCode())) {
                log.info("{} GET presigned primary OK: status={} path={}", LG.USBLOGINFO, response.statusCode(), path);
                return buildResponseWithFilename(response, fileName);
            }
            log.warn("{} GET presigned primary returned {} — switching to backup, path={}",
                    LG.USBLOGWARNING, response.statusCode(), path);
        } catch (HttpTimeoutException | ConnectException | UnknownHostException e) {
            log.warn("{} GET presigned primary unavailable: {} — switching to backup, path={}",
                    LG.USBLOGWARNING, e.getMessage(), path);
        } catch (Exception e) {
            log.warn("{} GET presigned primary error: {} — switching to backup, path={}",
                    LG.USBLOGWARNING, e.getMessage(), path);
        }

        // backup: подменяем бакет primary на backup в path
        String backupPath = "/" + backupProps.getBucket().getBase() + "/" + fileName;
        try {
            HttpRequest backupRequest = buildS3Request("GET", new byte[0], backupPath, null, amzDate, region,
                    backupProps.getUrl(), backupProps.getAccessKey(), backupProps.getSecretKey(), null);
            log.info("{} GET presigned -> backup: url={}", LG.USBLOGINFO, backupProps.getUrl() + backupPath);
            HttpResponse<byte[]> response = httpClient.send(backupRequest, HttpResponse.BodyHandlers.ofByteArray());
            log.info("{} GET presigned backup response: status={} path={}", LG.USBLOGINFO, response.statusCode(), backupPath);
            return buildResponseWithFilename(response, fileName);
        } catch (Exception e) {
            log.error("{} GET presigned backup error path={} error={}", LG.USBLOGERROR, backupPath, e.getMessage());
            return ResponseEntity.internalServerError().body(e.getMessage());
        }
    }

    /** Вычисляет signing key для AWS4: HMAC цепочка от секретного ключа через date, region, service. */
    private byte[] getSigningKey(String secretKey, String date, String region, String service) throws Exception {
        byte[] kDate    = hmac(("AWS4" + secretKey).getBytes(StandardCharsets.UTF_8), date);
        byte[] kRegion  = hmac(kDate, region);
        byte[] kService = hmac(kRegion, service);
        return hmac(kService, "aws4_request");
    }

    /**
     * Строит canonical headers для валидации Presigned URL.
     * Для заголовка host использует целевой хост S3 (не хост прокси).
     */
    private String buildPresignCanonicalHeaders(HttpServletRequest request, String signedHeaders, String host) {
        StringBuilder sb = new StringBuilder();
        for (String h : signedHeaders.split(";")) {
            String headerName = h.toLowerCase();
            sb.append(headerName).append(":");

            if (headerName.equals("host")) {
                // В Presigned URL host заголовок не передается в запросе,
                // а вычисляется из URL. Используем целевой хост S3.
                sb.append(host);
            } else {
                // Для других заголовков получаем значение из запроса
                // (хотя в Presigned URL обычно только host)
                String headerValue = request.getHeader(h);
                if (headerValue != null) {
                    sb.append(headerValue.trim());
                }
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    /**
     * Строит канонический query string для Presigned URL валидации.
     * Параметры сортируются, URI-кодируются, X-Amz-Signature исключается согласно спецификации AWS4.
     */
    private String buildCanonicalQueryString(String rawQuery) {
        TreeMap<String, String> sorted = new TreeMap<>();
        for (String pair : rawQuery.split("&")) {
            int idx = pair.indexOf('=');
            String key = pair.substring(0, idx);
            String val = idx + 1 < pair.length() ? pair.substring(idx + 1) : "";
            if (!key.equals("X-Amz-Signature")) {
                sorted.put(uriEncode(java.net.URLDecoder.decode(key, StandardCharsets.UTF_8)),
                        uriEncode(java.net.URLDecoder.decode(val, StandardCharsets.UTF_8)));
            }
        }
        StringBuilder sb = new StringBuilder();
        sorted.forEach((k, v) -> { if (sb.length() > 0) sb.append("&"); sb.append(k).append("=").append(v); });
        return sb.toString();
    }

    /**
     * URI-кодирует строку по правилам AWS4: unreserved символы не кодируются,
     * '/' кодируется как %2F (в отличие от стандартного URL encoding).
     */
    private String uriEncode(String value) {
        StringBuilder sb = new StringBuilder();
        for (char c : value.toCharArray()) {
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') ||
                    (c >= '0' && c <= '9') || c == '-' || c == '_' || c == '.' || c == '~') {
                sb.append(c);
            } else if (c == '/') {
                sb.append("%2F");
            } else {
                sb.append(String.format("%%%02X", (int) c));
            }
        }
        return sb.toString();
    }

    /** Парсит query string в Map. Ключи не декодируются для сохранения оригинальных имён параметров Presigned URL. */
    private Map<String, String> parsePresignQuery(String query) {
        Map<String, String> params = new java.util.LinkedHashMap<>();
        if (query == null) return params;
        for (String pair : query.split("&")) {
            int idx = pair.indexOf('=');
            if (idx > 0) params.put(pair.substring(0, idx), pair.substring(idx + 1));
        }
        return params;
    }

    /**
     * Преобразует ответ HttpClient в ResponseEntity с заголовком Content-Disposition.
     * Имя файла и размер берутся из заголовков ответа S3 (Content-Disposition, Content-Length).
     * Если Content-Disposition отсутствует в ответе S3 — строится из имени файла в URL.
     */
    private ResponseEntity<?> buildResponseWithFilename(HttpResponse<byte[]> response, String fileName) {
        ResponseEntity.BodyBuilder rb = ResponseEntity.status(response.statusCode());
        String ct = response.headers().firstValue("content-type")
                .orElse(MediaType.APPLICATION_OCTET_STREAM_VALUE);
        rb.contentType(MediaType.parseMediaType(ct));
        String disposition = response.headers().firstValue("content-disposition")
                .orElseGet(() -> "attachment; filename*=UTF-8''" +
                        java.net.URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20"));
        rb.header("Content-Disposition", disposition);
        response.headers().firstValue("content-length").ifPresent(v -> rb.header("Content-Length", v));
        response.headers().firstValue("etag").ifPresent(v -> rb.header("ETag", v));
        response.headers().firstValue("location").ifPresent(v -> rb.header("Location", v));
        return rb.body(response.body());
    }
}