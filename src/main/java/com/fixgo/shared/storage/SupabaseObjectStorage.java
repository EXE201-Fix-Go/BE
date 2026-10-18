package com.fixgo.shared.storage;

import com.fixgo.shared.exception.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Optional;

/**
 * Supabase Storage over its REST API, authenticated with the project's service key (it bypasses RLS, so it stays on
 * the backend: set SUPABASE_SERVICE_KEY only in the server's environment). Buckets are created on first use:
 * a public one for scene photos and a private one for KYC documents.
 * Paths used: POST /storage/v1/bucket, POST /storage/v1/object/{bucket}/{key},
 * GET /storage/v1/object/authenticated/{bucket}/{key}, public URLs under /storage/v1/object/public/{bucket}/{key}.
 */
@Component
@ConditionalOnProperty(name = "fixgo.storage.provider", havingValue = "supabase")
public class SupabaseObjectStorage implements ObjectStorage {
    private static final Logger log = LoggerFactory.getLogger(SupabaseObjectStorage.class);
    private static final long MAX_BYTES = 10L * 1024 * 1024;

    private final StorageProperties.Supabase config;
    private final HttpClient http;
    private volatile boolean bucketsReady;

    public SupabaseObjectStorage(StorageProperties properties) {
        this.config = properties.supabase();
        if (config.url().isEmpty() || config.serviceKey() == null || config.serviceKey().isBlank()) {
            throw new IllegalStateException("STORAGE_PROVIDER=supabase needs SUPABASE_URL and SUPABASE_SERVICE_KEY.");
        }
        this.http = HttpClient.newBuilder().connectTimeout(config.timeout()).build();
    }

    @Override
    public void put(Visibility visibility, String key, byte[] bytes, String contentType) {
        ensureBuckets();
        var request = base(visibility, key, "/object/")
                .header("Content-Type", contentType)
                .header("x-upsert", "false")
                .POST(HttpRequest.BodyPublishers.ofByteArray(bytes)).build();
        var response = send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            log.error("storage.supabase upload failed bucket={} status={}", bucket(visibility), response.statusCode());
            throw new ApiException(HttpStatus.BAD_GATEWAY, "STORAGE_FAILED", "Could not store the file. Please try again.");
        }
    }

    @Override
    public Optional<StoredObject> get(Visibility visibility, String key) {
        var request = base(visibility, key, "/object/authenticated/").GET().build();
        var response = send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() == 404 || response.statusCode() == 400) return Optional.empty();
        if (response.statusCode() / 100 != 2) {
            log.error("storage.supabase read failed bucket={} status={}", bucket(visibility), response.statusCode());
            throw new ApiException(HttpStatus.BAD_GATEWAY, "STORAGE_FAILED", "Could not read the file.");
        }
        String type = response.headers().firstValue("Content-Type").orElse(StorageKeys.contentTypeOf(key));
        return Optional.of(new StoredObject(response.body(), type));
    }

    @Override
    public boolean exists(Visibility visibility, String key) {
        // GET of the first byte only: it works on every Storage version and does not download the whole image.
        var request = base(visibility, key, "/object/authenticated/").header("Range", "bytes=0-0").GET().build();
        var response = send(request, HttpResponse.BodyHandlers.discarding());
        if (response.statusCode() / 100 == 2) return true;
        if (response.statusCode() == 404 || response.statusCode() == 400) return false;
        log.error("storage.supabase exists failed bucket={} status={}", bucket(visibility), response.statusCode());
        throw new ApiException(HttpStatus.BAD_GATEWAY, "STORAGE_FAILED", "Could not check the file.");
    }

    @Override
    public String publicUrl(String key) {
        return config.url() + "/storage/v1/object/public/" + config.publicBucket() + "/" + key;
    }

    private String bucket(Visibility v) { return v == Visibility.PUBLIC ? config.publicBucket() : config.privateBucket(); }

    private HttpRequest.Builder base(Visibility visibility, String key, String route) {
        return HttpRequest.newBuilder(URI.create(config.url() + "/storage/v1" + route + bucket(visibility) + "/" + key))
                .timeout(config.timeout())
                .header("Authorization", "Bearer " + config.serviceKey())
                .header("apikey", config.serviceKey());
    }

    private void ensureBuckets() {
        if (bucketsReady) return;
        synchronized (this) {
            if (bucketsReady) return;
            createBucket(config.publicBucket(), true);
            createBucket(config.privateBucket(), false);
            bucketsReady = true;
        }
    }

    /** Idempotent: an existing bucket answers with a 4xx, which is fine. */
    private void createBucket(String name, boolean isPublic) {
        String body = "{\"name\":\"" + name + "\",\"public\":" + isPublic + ",\"file_size_limit\":" + MAX_BYTES
                + ",\"allowed_mime_types\":[\"image/jpeg\",\"image/png\",\"image/webp\",\"image/heic\"]}";
        var request = HttpRequest.newBuilder(URI.create(config.url() + "/storage/v1/bucket"))
                .timeout(config.timeout())
                .header("Authorization", "Bearer " + config.serviceKey())
                .header("apikey", config.serviceKey())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        var response = send(request, HttpResponse.BodyHandlers.ofString());
        int status = response.statusCode();
        if (status / 100 == 2) log.info("storage.supabase created bucket {}", name);
        else if (status / 100 == 5 || status == 401 || status == 403) {
            log.error("storage.supabase bucket {} unavailable, status={}", name, status);
            throw new ApiException(HttpStatus.BAD_GATEWAY, "STORAGE_FAILED", "File storage is unavailable.");
        }   // 400/409: it already exists
    }

    private <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
        try {
            return http.send(request, handler);
        } catch (IOException ex) {
            log.error("storage.supabase request failed: {}", ex.toString());
            throw new ApiException(HttpStatus.BAD_GATEWAY, "STORAGE_FAILED", "File storage is unavailable.");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ApiException(HttpStatus.BAD_GATEWAY, "STORAGE_FAILED", "File storage is unavailable.");
        }
    }
}
