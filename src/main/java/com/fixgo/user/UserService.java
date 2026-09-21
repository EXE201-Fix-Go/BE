package com.fixgo.user;

import com.fixgo.common.ApiException;
import com.fixgo.partner.PartnerProfileRepository;
import com.fixgo.partner.PartnerType;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;

@Service
public class UserService {
    private final UserRepository users;
    private final UserIdentityRepository identities;
    private final PartnerProfileRepository partners;

    public UserService(UserRepository users, UserIdentityRepository identities, PartnerProfileRepository partners) {
        this.users = users;
        this.identities = identities;
        this.partners = partners;
    }

    @Transactional(readOnly = true)
    public UserResponse get(UUID id) {
        return toResponse(users.findById(id).orElseThrow(UserService::notFound));
    }

    @Transactional
    public UserResponse rename(UUID id, String fullName) {
        var user = users.lockById(id).orElseThrow(UserService::notFound);
        if (!user.isActive()) throw new ApiException(HttpStatus.FORBIDDEN, "ACCOUNT_LOCKED", "This account is locked.");
        user.rename(fullName);
        return toResponse(user);
    }

    /** Builds the public view: phone from the primary identity, app role from the partner profile. */
    @Transactional(readOnly = true)
    public UserResponse toResponse(User user) {
        String phone = identities.findPrimaryUid(user.getId()).orElse(null);
        PartnerType type = user.getRole() == Role.PARTNER
                ? partners.findById(user.getId()).map(p -> p.getPartnerType()).orElse(null) : null;
        return UserResponse.from(user, phone, type);
    }

    public static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "User does not exist.");
    }
}
