package com.fixgo.shared.storage;

import com.fixgo.shared.exception.ApiException;
import com.fixgo.shared.util.Actor;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Image uploads. Scene photos for orders (BR05) are public; KYC identity documents are private and are only
 * ever read back by an admin through the backend (docs/api.md). The file type comes from the bytes, not from
 * the Content-Type the client claims.
 */
@RestController
@RequestMapping("/api/v1/uploads")
public class UploadController {
    private final ObjectStorage storage;

    public UploadController(ObjectStorage storage) { this.storage = storage; }

    /** Scene photo → public URL to put in an order's photoUrls (multipart/form-data). */
    @PostMapping(consumes = "multipart/form-data")
    @ResponseStatus(HttpStatus.CREATED)
    public UploadResponse upload(@RequestParam("file") MultipartFile file) throws IOException {
        var image = readImage(file);
        String key = StorageKeys.photoKey(image.mime());
        storage.put(ObjectStorage.Visibility.PUBLIC, key, image.bytes(), image.mime());
        return new UploadResponse(storage.publicUrl(key));
    }

    /** Scene photo → public URL via JSON Base64 (reliable fallback for React Native / Expo Go). */
    @PostMapping(consumes = "application/json")
    @ResponseStatus(HttpStatus.CREATED)
    public UploadResponse uploadBase64(@RequestBody Base64UploadRequest request) {
        var image = readBase64Image(request);
        String key = StorageKeys.photoKey(image.mime());
        storage.put(ObjectStorage.Visibility.PUBLIC, key, image.bytes(), image.mime());
        return new UploadResponse(storage.publicUrl(key));
    }

    /**
     * KYC document → private storage key, to be sent in POST /partner-registration. The key is bound to the
     * uploader, so nobody can register with a file somebody else uploaded.
     */
    @PostMapping(path = "/kyc", consumes = "multipart/form-data")
    @ResponseStatus(HttpStatus.CREATED)
    public KycUploadResponse uploadKyc(Authentication auth, @RequestParam("file") MultipartFile file) throws IOException {
        var actor = Actor.of(auth);
        var image = readImage(file);
        String key = StorageKeys.kycKey(actor.userId(), image.mime());
        storage.put(ObjectStorage.Visibility.PRIVATE, key, image.bytes(), image.mime());
        return new KycUploadResponse(key);
    }

    /**
     * KYC document → private storage key via JSON Base64 (avoids React Native FormData networking bugs on Expo Go).
     */
    @PostMapping(path = "/kyc", consumes = "application/json")
    @ResponseStatus(HttpStatus.CREATED)
    public KycUploadResponse uploadKycBase64(Authentication auth, @RequestBody Base64UploadRequest request) {
        var actor = Actor.of(auth);
        var image = readBase64Image(request);
        String key = StorageKeys.kycKey(actor.userId(), image.mime());
        storage.put(ObjectStorage.Visibility.PRIVATE, key, image.bytes(), image.mime());
        return new KycUploadResponse(key);
    }

    private record Image(byte[] bytes, String mime) { }

    public record Base64UploadRequest(String base64) { }

    private static final int MAX_IMAGE_BYTES = 10 * 1024 * 1024;

    private static Image readImage(MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "EMPTY_FILE", "Ảnh tải lên bị trống hoặc không đọc được dữ liệu.");
        }
        return validateImageBytes(file.getBytes());
    }

    private static Image readBase64Image(Base64UploadRequest request) {
        if (request == null || request.base64() == null || request.base64().isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "EMPTY_FILE", "Ảnh tải lên bị trống hoặc không đọc được dữ liệu.");
        }
        String raw = request.base64().strip();
        int comma = raw.indexOf(',');
        if (raw.startsWith("data:") && comma >= 0) {
            raw = raw.substring(comma + 1);
        }
        raw = raw.replaceAll("\\s+", "");
        byte[] bytes;
        try {
            bytes = java.util.Base64.getDecoder().decode(raw);
        } catch (IllegalArgumentException ex) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_BASE64", "Dữ liệu ảnh Base64 không hợp lệ.");
        }
        return validateImageBytes(bytes);
    }

    private static Image validateImageBytes(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "EMPTY_FILE", "Ảnh tải lên bị trống hoặc không đọc được dữ liệu.");
        }
        if (bytes.length > MAX_IMAGE_BYTES) {
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "FILE_TOO_LARGE", "Ảnh tải lên vượt quá dung lượng tối đa 10MB.");
        }
        String mime = ImageSniffer.detect(bytes);
        if (mime == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "UNSUPPORTED_FILE", "Chỉ chấp nhận ảnh định dạng JPEG, PNG, WebP hoặc HEIC.");
        }
        return new Image(bytes, mime);
    }

    public record UploadResponse(String url) { }

    public record KycUploadResponse(String storageKey) { }

    @ConfigurationProperties("fixgo.uploads")
    public record UploadProperties(String dir, String publicBaseUrl) { }

    /** Serves the locally stored public files (development). */
    @Configuration
    static class StaticUploads implements WebMvcConfigurer {
        private final UploadProperties properties;
        StaticUploads(UploadProperties properties) { this.properties = properties; }

        @Override
        public void addResourceHandlers(ResourceHandlerRegistry registry) {
            String location = Path.of(properties.dir()).toAbsolutePath().toUri().toString();
            String normalizedLocation = location.endsWith("/") ? location : location + "/";
            registry.addResourceHandler("/uploads/**", "/api/v1/uploads/**")
                    .addResourceLocations(normalizedLocation);
        }
    }
}
