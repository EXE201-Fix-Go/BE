package com.fixgo.module.admin.service;

import com.fixgo.module.admin.dto.TrustedPartnerDtos;
import com.fixgo.module.catalog.entity.ServiceCatalog;
import com.fixgo.module.catalog.service.ServiceCatalogCache;
import com.fixgo.module.iam.entity.User;
import com.fixgo.module.iam.entity.UserIdentity;
import com.fixgo.module.iam.enums.IdentityProvider;
import com.fixgo.module.iam.enums.Role;
import com.fixgo.module.iam.repository.UserIdentityRepository;
import com.fixgo.module.iam.repository.UserRepository;
import com.fixgo.module.partner.entity.PartnerProfile;
import com.fixgo.module.partner.entity.PartnerServiceOffer;
import com.fixgo.module.partner.enums.PartnerType;
import com.fixgo.module.partner.enums.VerificationStatus;
import com.fixgo.module.partner.repository.PartnerProfileRepository;
import com.fixgo.module.partner.repository.PartnerServiceOfferRepository;
import com.fixgo.shared.exception.ApiException;
import com.fixgo.shared.util.Actor;
import com.fixgo.shared.util.PhoneNumbers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
import java.util.List;

/**
 * Onboards an individual partner the admin already knows and has verified in person (BR06 is satisfied by the
 * admin's own check). By design no KYC files are uploaded or stored for them; the approval records which admin
 * vouched for the partner (verified_by) and when. The partner signs in with OTP on their own phone (C-06).
 */
@Service
public class AdminTrustedPartnerService {
    private static final Logger log = LoggerFactory.getLogger(AdminTrustedPartnerService.class);

    private final UserRepository users;
    private final UserIdentityRepository identities;
    private final PartnerProfileRepository profiles;
    private final PartnerServiceOfferRepository offers;
    private final ServiceCatalogCache catalog;
    private final Clock clock;

    public AdminTrustedPartnerService(UserRepository users, UserIdentityRepository identities,
                                      PartnerProfileRepository profiles, PartnerServiceOfferRepository offers,
                                      ServiceCatalogCache catalog, Clock clock) {
        this.users = users;
        this.identities = identities;
        this.profiles = profiles;
        this.offers = offers;
        this.catalog = catalog;
        this.clock = clock;
    }

    @Transactional
    public TrustedPartnerDtos.Response create(Actor admin, TrustedPartnerDtos.CreateRequest request) {
        if (!admin.is(Role.ADMIN)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "You do not have permission to perform this action.");
        }
        String phone = PhoneNumbers.toE164(request.phone());
        List<String> codes = request.serviceCodes().stream().map(String::strip).distinct().toList();
        List<ServiceCatalog> services = catalog.activeByCodes(codes);
        if (services.size() != codes.size()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "UNKNOWN_SERVICE", "One of the service codes is unknown.");
        }
        var now = clock.instant();
        String name = request.fullName().strip();

        User user;
        boolean created;
        var identity = identities.findByProviderAndProviderUid(IdentityProvider.PHONE, phone).orElse(null);
        if (identity == null) {
            user = users.save(new User(Role.PARTNER, name, now));
            // Not verified: the phone is proven when its owner completes an OTP sign-in (RB-03).
            identities.save(new UserIdentity(user, IdentityProvider.PHONE, phone, true, now, null));
            created = true;
        } else {
            user = users.lockById(identity.getUser().getId()).orElseThrow();
            if (user.getRole() == Role.ADMIN) {
                throw new ApiException(HttpStatus.CONFLICT, "ADMIN_ACCOUNT", "An admin account cannot become a partner.");
            }
            if (profiles.existsById(user.getId())) {
                throw new ApiException(HttpStatus.CONFLICT, "ALREADY_PARTNER", "This phone already belongs to a partner.");
            }
            user.changeRole(Role.PARTNER);
            created = false;
        }
        String avatar = request.avatarUrl() == null || request.avatarUrl().isBlank() ? user.getAvatarUrl() : request.avatarUrl().strip();
        user.updateProfile(name, user.getEmail(), user.getDateOfBirth(), avatar);

        var profile = new PartnerProfile(user.getId(), PartnerType.INDIVIDUAL, null, null);
        profile.verify(VerificationStatus.APPROVED, admin.userId(), now);     // the admin who vouched for them
        profiles.save(profile);
        services.forEach(s -> offers.save(new PartnerServiceOffer(user.getId(), s.getId())));

        log.info("admin.partner.trusted adminId={} partnerId={} services={} accountCreated={} kyc=checked-outside-system",
                admin.userId(), user.getId(), codes.size(), created);
        return new TrustedPartnerDtos.Response(user.getId(), user.getFullName(), phone, user.getAvatarUrl(),
                profile.getVerificationStatus(), admin.userId(), now,
                services.stream().map(ServiceCatalog::getCode).sorted().toList(), created);
    }
}
