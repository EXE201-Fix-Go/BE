package com.fixgo.storage;

import com.fixgo.shared.exception.ApiException;
import com.fixgo.shared.storage.ObjectStorage;
import com.fixgo.shared.storage.StorageKeys;
import com.fixgo.shared.storage.StorageProperties;
import com.fixgo.shared.storage.SupabaseObjectStorage;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The Supabase adapter against a local stand-in for the Storage REST API: paths, auth headers, privacy, failures. */
class SupabaseObjectStorageTest {
    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0x10, 'J', 'F', 'I', 'F', 0, 1};
    private HttpServer server;
    private final Map<String, byte[]> objects = new HashMap<>();
    private final List<String> calls = new ArrayList<>();
    private final List<String> buckets = new ArrayList<>();
    private volatile int bucketStatus = 200;
    private volatile int uploadStatus = 200;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/storage/v1", ex -> {
            String path = ex.getRequestURI().getPath().substring("/storage/v1".length());
            String method = ex.getRequestMethod();
            boolean authorised = "Bearer service-key".equals(ex.getRequestHeaders().getFirst("Authorization"))
                    && "service-key".equals(ex.getRequestHeaders().getFirst("apikey"));
            calls.add(method + " " + path + (authorised ? "" : " (UNAUTHORISED)"));
            byte[] body = ex.getRequestBody().readAllBytes();
            int status = 404;
            byte[] reply = "{}".getBytes(StandardCharsets.UTF_8);
            String type = "application/json";
            if (!authorised) status = 401;
            else if (method.equals("POST") && path.equals("/bucket")) { buckets.add(new String(body, StandardCharsets.UTF_8)); status = bucketStatus; }
            else if (method.equals("POST") && path.startsWith("/object/")) {
                if (uploadStatus == 200) objects.put(path.substring("/object/".length()), body);
                status = uploadStatus;
            } else if (method.equals("GET") && path.startsWith("/object/authenticated/")) {
                byte[] found = objects.get(path.substring("/object/authenticated/".length()));
                if (found != null) { status = 200; reply = found; type = "image/jpeg"; }
                else { status = 400; reply = "{\"error\":\"not_found\"}".getBytes(StandardCharsets.UTF_8); }
            }
            ex.getResponseHeaders().add("Content-Type", type);
            ex.sendResponseHeaders(status, reply.length == 0 ? -1 : reply.length);
            if (reply.length > 0) ex.getResponseBody().write(reply);
            ex.close();
        });
        server.start();
    }

    @AfterEach
    void stop() { server.stop(0); }

    private SupabaseObjectStorage storage() {
        var supabase = new StorageProperties.Supabase("http://127.0.0.1:" + server.getAddress().getPort() + "/", "service-key", null, null, null);
        return new SupabaseObjectStorage(new StorageProperties("supabase", null, supabase));
    }

    @Test
    void uploadsToTheRightBucketWithTheServiceKeyAndCreatesBucketsOnce() {
        var storage = storage();
        String doc = StorageKeys.kycKey(UUID.randomUUID(), "image/jpeg");
        String photo = StorageKeys.photoKey("image/png");
        storage.put(ObjectStorage.Visibility.PRIVATE, doc, JPEG, "image/jpeg");
        storage.put(ObjectStorage.Visibility.PUBLIC, photo, JPEG, "image/png");
        assertThat(objects).containsKeys("fixgo-kyc/" + doc, "fixgo-public/" + photo);
        assertThat(calls).noneMatch(c -> c.contains("UNAUTHORISED"));
        assertThat(buckets).hasSize(2);                        // created once each, not on every upload
        assertThat(buckets.get(0)).contains("\"name\":\"fixgo-public\"").contains("\"public\":true");
        assertThat(buckets.get(1)).contains("\"name\":\"fixgo-kyc\"").contains("\"public\":false");
    }

    @Test
    void publicUrlsPointAtThePublicBucketAndPrivateOnesAreNeverLinked() {
        var storage = storage();
        String photo = StorageKeys.photoKey("image/jpeg");
        assertThat(storage.publicUrl(photo)).endsWith("/storage/v1/object/public/fixgo-public/" + photo);
    }

    @Test
    void readsAndChecksPrivateObjectsThroughTheAuthenticatedRoute() {
        var storage = storage();
        String doc = StorageKeys.kycKey(UUID.randomUUID(), "image/jpeg");
        storage.put(ObjectStorage.Visibility.PRIVATE, doc, JPEG, "image/jpeg");
        assertThat(storage.exists(ObjectStorage.Visibility.PRIVATE, doc)).isTrue();
        assertThat(storage.get(ObjectStorage.Visibility.PRIVATE, doc)).hasValueSatisfying(o -> {
            assertThat(o.bytes()).isEqualTo(JPEG);
            assertThat(o.contentType()).isEqualTo("image/jpeg");
        });
        String missing = StorageKeys.kycKey(UUID.randomUUID(), "image/jpeg");
        assertThat(storage.exists(ObjectStorage.Visibility.PRIVATE, missing)).isFalse();
        assertThat(storage.get(ObjectStorage.Visibility.PRIVATE, missing)).isEmpty();
    }

    @Test
    void existingBucketsAreNotAnError() {
        bucketStatus = 400;                                     // "already exists"
        storage().put(ObjectStorage.Visibility.PUBLIC, StorageKeys.photoKey("image/jpeg"), JPEG, "image/jpeg");
        assertThat(objects).hasSize(1);
    }

    @Test
    void failuresBecomeA502WithoutLeakingTheKey() {
        uploadStatus = 500;
        assertThatThrownBy(() -> storage().put(ObjectStorage.Visibility.PRIVATE, StorageKeys.kycKey(UUID.randomUUID(), "image/jpeg"), JPEG, "image/jpeg"))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("STORAGE_FAILED");
                    assertThat(e.getMessage()).doesNotContain("service-key");
                });
        bucketStatus = 401;
        assertThatThrownBy(() -> storage().put(ObjectStorage.Visibility.PUBLIC, StorageKeys.photoKey("image/jpeg"), JPEG, "image/jpeg"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("STORAGE_FAILED"));
    }

    @Test
    void refusesToStartWithoutUrlAndKey() {
        var none = new StorageProperties("supabase", null, new StorageProperties.Supabase("", null, null, null, null));
        assertThatThrownBy(() -> new SupabaseObjectStorage(none)).hasMessageContaining("SUPABASE_URL");
    }
}
