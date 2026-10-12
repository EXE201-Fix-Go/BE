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

    /** Scene photo → public URL to put in an order's photoUrls. */
    @PostMapping(consumes = "multipart/form-data")
    @ResponseStatus(HttpStatus.CREATED)
    public UploadResponse upload(@RequestParam("file") MultipartFile file) throws IOException {
        var image = readImage(file);
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

    private record Image(byte[] bytes, String mime) { }

    private static Image readImage(MultipartFile file) throws IOException {
        byte[] bytes = file.isEmpty() ? new byte[0] : file.getBytes();
        String mime = ImageSniffer.detect(bytes);
        if (mime == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "UNSUPPORTED_FILE", "Only JPEG/PNG/WebP/HEIC images are accepted.");
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
            registry.addResourceHandler("/uploads/**").addResourceLocations(location.endsWith("/") ? location : location + "/");
        }
    }
}
