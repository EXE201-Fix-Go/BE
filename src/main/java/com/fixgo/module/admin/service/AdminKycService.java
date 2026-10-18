package com.fixgo.module.admin.service;

import com.fixgo.module.iam.enums.Role;
import com.fixgo.module.partner.entity.PartnerDocument;
import com.fixgo.module.partner.repository.PartnerDocumentRepository;
import com.fixgo.shared.exception.ApiException;
import com.fixgo.shared.storage.ObjectStorage;
import com.fixgo.shared.storage.StorageKeys;
import com.fixgo.shared.util.Actor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Admin-only access to a partner's KYC documents (AUTHZ: the shop owner sees status only, never the files). */
@Service
public class AdminKycService {
    private static final Logger log = LoggerFactory.getLogger(AdminKycService.class);

    private final PartnerDocumentRepository documents;
    private final ObjectStorage storage;

    public AdminKycService(PartnerDocumentRepository documents, ObjectStorage storage) {
        this.documents = documents;
        this.storage = storage;
    }

    public record DocumentRow(UUID id, String documentType, String reviewStatus, Instant createdAt, boolean fileAvailable) { }

    @Transactional(readOnly = true)
    public List<DocumentRow> list(Actor actor, UUID partnerId) {
        requireAdmin(actor);
        return documents.findByPartnerIdOrderByCreatedAtAsc(partnerId).stream()
                .map(d -> new DocumentRow(d.getId(), d.getDocumentType(), d.getReviewStatus(), d.getCreatedAt(), hasFile(d)))
                .toList();
    }

    @Transactional(readOnly = true)
    public ObjectStorage.StoredObject content(Actor actor, UUID partnerId, UUID documentId) {
        requireAdmin(actor);
        var doc = documents.findById(documentId).filter(d -> d.getPartnerId().equals(partnerId)).orElseThrow(AdminKycService::notFound);
        if (!StorageKeys.isKycKey(doc.getStorageKey())) throw notFound();             // legacy rows with placeholder keys
        log.info("kyc.document.viewed adminId={} partnerId={} documentId={}", actor.userId(), partnerId, documentId);
        return storage.get(ObjectStorage.Visibility.PRIVATE, doc.getStorageKey()).orElseThrow(AdminKycService::notFound);
    }

    private boolean hasFile(PartnerDocument d) {
        return StorageKeys.isKycKey(d.getStorageKey()) && storage.exists(ObjectStorage.Visibility.PRIVATE, d.getStorageKey());
    }

    private static void requireAdmin(Actor actor) {
        if (!actor.is(Role.ADMIN)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "You do not have permission to perform this action.");
        }
    }

    private static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "DOCUMENT_NOT_FOUND", "Document does not exist.");
    }
}
