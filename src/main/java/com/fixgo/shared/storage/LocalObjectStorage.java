package com.fixgo.shared.storage;

import com.fixgo.shared.exception.ApiException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/** Development storage on local disk. PUBLIC objects are served at /uploads/**, PRIVATE ones never are. */
@Component
@ConditionalOnProperty(name = "fixgo.storage.provider", havingValue = "local", matchIfMissing = true)
public class LocalObjectStorage implements ObjectStorage {
    private final Path publicDir;
    private final Path privateDir;
    private final String publicBaseUrl;

    public LocalObjectStorage(UploadController.UploadProperties uploads, StorageProperties storage) {
        this.publicDir = Path.of(uploads.dir()).toAbsolutePath().normalize();
        this.privateDir = Path.of(storage.privateDir()).toAbsolutePath().normalize();
        this.publicBaseUrl = uploads.publicBaseUrl().replaceAll("/+$", "");
    }

    @Override
    public void put(Visibility visibility, String key, byte[] bytes, String contentType) {
        try {
            Path target = resolve(visibility, key);
            Files.createDirectories(target.getParent());
            Files.write(target, bytes);
        } catch (IOException ex) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "STORAGE_FAILED", "Could not store the file.");
        }
    }

    @Override
    public Optional<StoredObject> get(Visibility visibility, String key) {
        try {
            Path file = resolve(visibility, key);
            if (!Files.isRegularFile(file)) return Optional.empty();
            return Optional.of(new StoredObject(Files.readAllBytes(file), StorageKeys.contentTypeOf(key)));
        } catch (IOException ex) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "STORAGE_FAILED", "Could not read the file.");
        }
    }

    @Override
    public boolean exists(Visibility visibility, String key) {
        return Files.isRegularFile(resolve(visibility, key));
    }

    @Override
    public String publicUrl(String key) { return publicBaseUrl + "/uploads/" + key; }

    private Path resolve(Visibility visibility, String key) {
        Path base = visibility == Visibility.PUBLIC ? publicDir : privateDir;
        Path file = base.resolve(key).normalize();
        if (!file.startsWith(base)) throw new IllegalArgumentException("Key escapes the storage directory.");
        return file;
    }
}
