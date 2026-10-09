package com.fixgo.module.partner.service;
import com.fixgo.module.partner.entity.*;
import com.fixgo.module.partner.enums.*;
import com.fixgo.module.partner.dto.*;
import com.fixgo.module.partner.repository.*;
import com.fixgo.module.partner.service.*;

import com.fixgo.module.catalog.entity.ServiceCatalog;

import com.fixgo.module.catalog.service.ServiceCatalogCache;
import com.fixgo.module.dispatch.repository.OrderAssignmentRepository;
import com.fixgo.shared.util.Actor;
import com.fixgo.shared.exception.ApiException;
import com.fixgo.shared.util.PhoneNumbers;
import com.fixgo.module.iam.entity.*;
import com.fixgo.module.iam.enums.*;
import com.fixgo.module.iam.dto.*;
import com.fixgo.module.iam.service.*;
import com.fixgo.module.iam.repository.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
import java.util.List;
import java.util.UUID;

@Service
public class PartnerProfileService {
    private final PartnerProfileRepository profiles;
    private final PartnerDocumentRepository documents;
    private final PartnerServiceOfferRepository offers;
    private final OrderAssignmentRepository assignments;
    private final ServiceCatalogCache catalog;
    private final UserRepository users;
    private final UserIdentityRepository identities;
    private final KycDocumentPolicy kyc;
    private final Clock clock;

    public PartnerProfileService(PartnerProfileRepository profiles, PartnerDocumentRepository documents,
                                 PartnerServiceOfferRepository offers, OrderAssignmentRepository assignments,
                                 ServiceCatalogCache catalog, UserRepository users, UserIdentityRepository identities,
                                 KycDocumentPolicy kyc, Clock clock) {
        this.profiles = profiles;
        this.documents = documents;
        this.offers = offers;
        this.assignments = assignments;
        this.catalog = catalog;
        this.users = users;
        this.identities = identities;
        this.kyc = kyc;
        this.clock = clock;
    }

    /** A logged-in phone registers as INDIVIDUAL or SHOP. Verification stays PENDING until an admin approves (BR06). */
    @Transactional
    public PartnerDtos.ProfileResponse register(Actor actor, PartnerDtos.RegisterRequest request) {
        if (actor.is(Role.ADMIN)) throw forbidden();
        if (request.partnerType() == PartnerType.SHOP_STAFF) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "STAFF_INVITE_ONLY",
                    "Shop staff are invited by their shop owner.");
        }
        if (profiles.existsById(actor.userId())) {
            throw new ApiException(HttpStatus.CONFLICT, "ALREADY_PARTNER", "This account is already a partner.");
        }
        kyc.requireOwnUploads(actor.userId(), request.documents());
        var now = clock.instant();
        var user = users.lockById(actor.userId()).orElseThrow(UserService::notFound);
        user.rename(request.fullName());
        user.changeRole(Role.PARTNER);
        var profile = profiles.save(new PartnerProfile(user.getId(), request.partnerType(), null,
                request.partnerType() == PartnerType.SHOP ? request.shopName() : null));
        replaceServices(profile.getUserId(), request.serviceCodes());
        for (var doc : request.documents()) {
            documents.save(new PartnerDocument(profile.getUserId(), doc.documentType(), doc.storageKey(), now));
        }
        return toResponse(profile, user);
    }

    @Transactional(readOnly = true)
    public PartnerDtos.ProfileResponse me(Actor actor) {
        var profile = profiles.findById(actor.userId()).orElseThrow(PartnerProfileService::notPartner);
        return toResponse(profile, users.findById(actor.userId()).orElseThrow(UserService::notFound));
    }

    @Transactional
    public PartnerDtos.ProfileResponse updatePresence(Actor actor, PartnerDtos.PresenceRequest request) {
        var profile = profiles.lockById(actor.userId()).orElseThrow(PartnerProfileService::notPartner);
        if (request.availability() == Availability.ONLINE && !profile.canTakeOrders()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "PARTNER_NOT_VERIFIED",
                    "Your profile must be approved before going online.");
        }
        // While an order is open the partner stays BUSY: going ONLINE would offer them a second job and going
        // OFFLINE would abandon the customer. Re-sending the current state (e.g. a location ping) is still fine.
        if (request.availability() != profile.getAvailability() && assignments.hasActiveJob(actor.userId())) {
            throw new ApiException(HttpStatus.CONFLICT, "PARTNER_HAS_ACTIVE_JOB",
                    "Finish or cancel your current order before changing your availability.");
        }
        profile.updatePresence(request.availability(), request.lat(), request.lng(), clock.instant());
        return toResponse(profile, users.findById(actor.userId()).orElseThrow(UserService::notFound));
    }

    @Transactional(readOnly = true)
    public List<PartnerDtos.StaffResponse> listStaff(Actor actor) {
        var shop = profiles.findById(actor.userId()).orElseThrow(PartnerProfileService::notPartner);
        if (shop.getPartnerType() != PartnerType.SHOP) {
            throw new ApiException(HttpStatus.FORBIDDEN, "SHOP_OWNER_ONLY", "Only a shop owner can view staff.");
        }
        return profiles.findByParentShopIdOrderByUserId(shop.getUserId()).stream().map(p -> {
            var u = users.findById(p.getUserId()).orElseThrow(UserService::notFound);
            return new PartnerDtos.StaffResponse(p.getUserId(), u.getFullName(),
                    identities.findPrimaryUid(p.getUserId()).orElse(null), p.getVerificationStatus(), p.getAvailability());
        }).toList();
    }

    @Transactional
    public PartnerDtos.ProfileResponse verify(Actor admin, UUID partnerId, VerificationStatus status) {
        if (!admin.is(Role.ADMIN)) throw forbidden();
        if (status == VerificationStatus.PENDING) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Choose APPROVED or REJECTED.");
        }
        var profile = profiles.lockById(partnerId).orElseThrow(PartnerProfileService::notPartner);
        if (status == VerificationStatus.APPROVED) kyc.requireCompleteSet(documents.findByPartnerIdOrderByCreatedAtAsc(partnerId));
        var now = clock.instant();
        profile.verify(status, admin.userId(), now);
        documents.findByPartnerIdOrderByCreatedAtAsc(partnerId).forEach(d -> d.review(status.name(), admin.userId(), now));
        if (status == VerificationStatus.REJECTED) profile.setAvailability(Availability.OFFLINE);
        return toResponse(profile, users.findById(partnerId).orElseThrow(UserService::notFound));
    }

    private void replaceServices(UUID partnerId, List<String> codes) {
        var found = catalog.activeByCodes(codes);
        if (found.size() != codes.stream().distinct().count()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "UNKNOWN_SERVICE", "One of the service codes is unknown.");
        }
        offers.deleteByPartnerId(partnerId);
        found.forEach(s -> offers.save(new PartnerServiceOffer(partnerId, s.getId())));
    }

    private PartnerDtos.ProfileResponse toResponse(PartnerProfile p, User u) {
        var codes = offers.findByPartnerId(p.getUserId()).stream()
                .map(o -> catalog.byId(o.getServiceId()).map(ServiceCatalog::getCode).orElse(null))
                .filter(c -> c != null).sorted().toList();
        var docs = documents.findByPartnerIdOrderByCreatedAtAsc(p.getUserId()).stream()
                .map(d -> new PartnerDtos.DocumentResponse(d.getId(), d.getDocumentType(), d.getReviewStatus())).toList();
        return new PartnerDtos.ProfileResponse(p.getUserId(), u.getFullName(),
                identities.findPrimaryUid(p.getUserId()).orElse(null), p.getPartnerType(), p.getParentShopId(),
                p.getShopName(), p.getVerificationStatus(), p.getAvailability(), p.getOperationalStatus(),
                p.getCurrentLat(), p.getCurrentLng(), p.getLocationUpdatedAt(), codes, docs);
    }

    static ApiException notPartner() {
        return new ApiException(HttpStatus.NOT_FOUND, "PARTNER_NOT_FOUND", "No partner profile for this account.");
    }

    private static ApiException forbidden() {
        return new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "You do not have permission to perform this action.");
    }
}
