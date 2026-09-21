package com.fixgo.upload;

import com.fixgo.common.ApiException;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Scene photos for orders (BR05). Pilot storage: local disk served at /uploads/**. Production swaps this
 * for Cloudinary (BRD §8) without changing the API: clients only ever see the returned URL.
 * KYC documents are NOT handled here — they belong in private storage with signed URLs.
 */
@RestController
@RequestMapping("/api/v1/uploads")
public class UploadController {
    private static final Set<String> ALLOWED = Set.of("image/jpeg", "image/png", "image/webp", "image/heic");
    private final UploadProperties properties;

    public UploadController(UploadProperties properties) { this.properties = properties; }

    @PostMapping(consumes = "multipart/form-data")
    @ResponseStatus(HttpStatus.CREATED)
    public UploadResponse upload(@RequestParam("file") MultipartFile file) throws IOException {
        String type = file.getContentType() == null ? "" : file.getContentType().toLowerCase(Locale.ROOT);
        if (file.isEmpty() || !ALLOWED.contains(type)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "UNSUPPORTED_FILE", "Only JPEG/PNG/WebP/HEIC images are accepted.");
        }
        String ext = switch (type) { case "image/png" -> "png"; case "image/webp" -> "webp"; case "image/heic" -> "heic"; default -> "jpg"; };
        String name = UUID.randomUUID() + "." + ext;
        Path dir = Path.of(properties.dir()).toAbsolutePath();
        Files.createDirectories(dir);
        file.transferTo(dir.resolve(name));
        return new UploadResponse(properties.publicBaseUrl().replaceAll("/+$", "") + "/uploads/" + name);
    }

    public record UploadResponse(String url) { }

    @ConfigurationProperties("fixgo.uploads")
    public record UploadProperties(String dir, String publicBaseUrl) { }

    /** Serves the stored files. */
    @Configuration
    static class StaticUploads implements WebMvcConfigurer {
        private final UploadProperties properties;
        StaticUploads(UploadProperties properties) { this.properties = properties; }

        @Override
        public void addResourceHandlers(ResourceHandlerRegistry registry) {
            String location = Path.of(properties.dir()).toAbsolutePath().toUri().toString();
            registry.addResourceHandler("/uploads/**").addResourceLocations(location.endsWith("/") ? location : location + "/");
        }
    }
}
