package com.fixgo.module.partner.service;

import com.fixgo.module.partner.dto.PartnerDtos;
import com.fixgo.module.partner.entity.PartnerDocument;
import com.fixgo.shared.exception.ApiException;
import com.fixgo.shared.storage.ObjectStorage;
import com.fixgo.shared.storage.StorageKeys;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * BR06: a partner is only verified against real identity documents. A registration may only reference files the
 * registrant uploaded themselves, and an admin cannot approve a profile that lacks the full set.
 */
@Component
public class KycDocumentPolicy {
    /** CCCD front + back and a selfie: the minimum set an admin needs to compare. */
    static final Set<String> REQUIRED = Set.of("ID_FRONT", "ID_BACK", "SELFIE");

    private final ObjectStorage storage;

    public KycDocumentPolicy(ObjectStorage storage) { this.storage = storage; }

    /** All three document types, each a key this user uploaded and that really exists. */
    public void requireOwnUploads(UUID userId, List<PartnerDtos.DocumentUpload> documents) {
        var types = new HashSet<String>();
        documents.forEach(d -> types.add(d.documentType()));
        if (!types.containsAll(REQUIRED)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "KYC_DOCUMENTS_MISSING",
                    "Send ID_FRONT, ID_BACK and SELFIE.");
        }
        for (var doc : documents) {
            if (!StorageKeys.isKycKeyOf(userId, doc.storageKey())
                    || !storage.exists(ObjectStorage.Visibility.PRIVATE, doc.storageKey())) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_DOCUMENT",
                        "Upload each document through /uploads/kyc first and send the storageKey it returns.");
            }
        }
    }

    /** Approval needs all three document types, each backed by a stored file. */
    public void requireCompleteSet(List<PartnerDocument> documents) {
        Set<String> present = new HashSet<>();
        for (var d : documents) {
            if (StorageKeys.isKycKey(d.getStorageKey()) && storage.exists(ObjectStorage.Visibility.PRIVATE, d.getStorageKey())) {
                present.add(d.getDocumentType());
            }
        }
        var missing = new java.util.TreeSet<>(REQUIRED);
        missing.removeAll(present);
        if (!missing.isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, "KYC_DOCUMENTS_MISSING",
                    "Cannot approve: missing or unreadable documents " + missing + ".");
        }
    }
}
