package com.fixgo.storage;

import com.fixgo.module.partner.dto.PartnerDtos;
import com.fixgo.module.partner.entity.PartnerDocument;
import com.fixgo.module.partner.service.KycDocumentPolicy;
import com.fixgo.shared.exception.ApiException;
import com.fixgo.shared.storage.ImageSniffer;
import com.fixgo.shared.storage.LocalObjectStorage;
import com.fixgo.shared.storage.ObjectStorage;
import com.fixgo.shared.storage.StorageKeys;
import com.fixgo.shared.storage.StorageProperties;
import com.fixgo.shared.storage.UploadController;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StorageTest {
    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0x10, 'J', 'F', 'I', 'F', 0, 1};
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 0, 0, 0, 0};

    // ------------------------------------------------------------------ what counts as an image

    @Test
    void imagesAreRecognisedByTheirBytesNotByAClaimedType() {
        assertThat(ImageSniffer.detect(JPEG)).isEqualTo("image/jpeg");
        assertThat(ImageSniffer.detect(PNG)).isEqualTo("image/png");
        assertThat(ImageSniffer.detect("RIFF\0\0\0\0WEBPVP8 ".getBytes())).isEqualTo("image/webp");
        assertThat(ImageSniffer.detect("\0\0\0\u0018ftypheic\0\0\0\0".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1))).isEqualTo("image/heic");
        assertThat(ImageSniffer.detect("<html><script>".getBytes())).isNull();
        assertThat(ImageSniffer.detect("GIF89a......".getBytes())).isNull();
        assertThat(ImageSniffer.detect(new byte[0])).isNull();
        assertThat(ImageSniffer.detect(null)).isNull();
    }

    @Test
    void kycKeysBelongToTheirUploaderOnly() {
        var me = UUID.randomUUID();
        var someoneElse = UUID.randomUUID();
        String key = StorageKeys.kycKey(me, "image/jpeg");
        assertThat(StorageKeys.isKycKeyOf(me, key)).isTrue();
        assertThat(StorageKeys.isKycKeyOf(someoneElse, key)).isFalse();
        assertThat(StorageKeys.isKycKey(key)).isTrue();
        for (String bad : new String[] {"kyc/front.jpg", "kyc/" + me + "/../x.jpg", "kyc/" + me + "/front.jpg", "photos/" + UUID.randomUUID() + ".jpg", "", null}) {
            assertThat(StorageKeys.isKycKeyOf(me, bad)).as(String.valueOf(bad)).isFalse();
        }
        assertThat(StorageKeys.isPhotoKey(StorageKeys.photoKey("image/png"))).isTrue();
        assertThat(StorageKeys.isPhotoKey("../../etc/passwd")).isFalse();
    }

    // ------------------------------------------------------------------ local disk

    private LocalObjectStorage local(Path dir) {
        return new LocalObjectStorage(new UploadController.UploadProperties(dir.resolve("public").toString(), "http://localhost:8080/"),
                new StorageProperties("local", dir.resolve("private").toString(), null));
    }

    @Test
    void localStorageSeparatesPublicAndPrivateFiles(@TempDir Path dir) {
        var storage = local(dir);
        String photo = StorageKeys.photoKey("image/png");
        storage.put(ObjectStorage.Visibility.PUBLIC, photo, PNG, "image/png");
        assertThat(storage.publicUrl(photo)).isEqualTo("http://localhost:8080/uploads/" + photo);
        assertThat(storage.exists(ObjectStorage.Visibility.PUBLIC, photo)).isTrue();
        assertThat(storage.exists(ObjectStorage.Visibility.PRIVATE, photo)).isFalse();

        String doc = StorageKeys.kycKey(UUID.randomUUID(), "image/jpeg");
        storage.put(ObjectStorage.Visibility.PRIVATE, doc, JPEG, "image/jpeg");
        assertThat(storage.get(ObjectStorage.Visibility.PRIVATE, doc)).hasValueSatisfying(o -> {
            assertThat(o.bytes()).isEqualTo(JPEG);
            assertThat(o.contentType()).isEqualTo("image/jpeg");
        });
        assertThat(storage.exists(ObjectStorage.Visibility.PUBLIC, doc)).as("private files are not in the public folder").isFalse();
        assertThat(storage.get(ObjectStorage.Visibility.PRIVATE, StorageKeys.kycKey(UUID.randomUUID(), "image/jpeg"))).isEmpty();
    }

    @Test
    void localStorageRefusesAKeyThatEscapesItsFolder(@TempDir Path dir) {
        var storage = local(dir);
        assertThatThrownBy(() -> storage.exists(ObjectStorage.Visibility.PRIVATE, "../public/x.png"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ------------------------------------------------------------------ KYC policy

    private static List<PartnerDtos.DocumentUpload> uploads(UUID owner, String... types) {
        return java.util.Arrays.stream(types).map(t -> new PartnerDtos.DocumentUpload(t, StorageKeys.kycKey(owner, "image/jpeg"))).toList();
    }

    @Test
    void registrationNeedsThreeOwnUploadsThatExist() {
        var storage = mock(ObjectStorage.class);
        when(storage.exists(org.mockito.ArgumentMatchers.eq(ObjectStorage.Visibility.PRIVATE), org.mockito.ArgumentMatchers.anyString())).thenReturn(true);
        var policy = new KycDocumentPolicy(storage);
        var me = UUID.randomUUID();
        policy.requireOwnUploads(me, uploads(me, "ID_FRONT", "ID_BACK", "SELFIE"));
        assertThatThrownBy(() -> policy.requireOwnUploads(me, uploads(me, "ID_FRONT", "ID_BACK")))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("KYC_DOCUMENTS_MISSING"));
        assertThatThrownBy(() -> policy.requireOwnUploads(me, uploads(UUID.randomUUID(), "ID_FRONT", "ID_BACK", "SELFIE")))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("INVALID_DOCUMENT"));
        var missingFile = mock(ObjectStorage.class);
        when(missingFile.exists(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString())).thenReturn(false);
        assertThatThrownBy(() -> new KycDocumentPolicy(missingFile).requireOwnUploads(me, uploads(me, "ID_FRONT", "ID_BACK", "SELFIE")))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("INVALID_DOCUMENT"));
    }

    @Test
    void approvalNeedsTheFullSetBackedByRealFiles() {
        var storage = mock(ObjectStorage.class);
        var owner = UUID.randomUUID();
        var docs = new java.util.ArrayList<PartnerDocument>();
        for (String t : new String[] {"ID_FRONT", "ID_BACK", "SELFIE"}) {
            docs.add(new PartnerDocument(owner, t, StorageKeys.kycKey(owner, "image/jpeg"), Instant.now()));
        }
        when(storage.exists(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString())).thenReturn(true);
        new KycDocumentPolicy(storage).requireCompleteSet(docs);
        // Legacy placeholder keys ("kyc/front.jpg") can never be approved.
        var legacy = List.of(new PartnerDocument(owner, "ID_FRONT", "kyc/front.jpg", Instant.now()),
                new PartnerDocument(owner, "ID_BACK", "kyc/back.jpg", Instant.now()),
                new PartnerDocument(owner, "SELFIE", "kyc/selfie.jpg", Instant.now()));
        assertThatThrownBy(() -> new KycDocumentPolicy(storage).requireCompleteSet(legacy))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("KYC_DOCUMENTS_MISSING"));
    }
}
