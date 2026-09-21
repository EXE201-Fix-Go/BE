package com.fixgo.admin;

import com.fixgo.common.PhoneNumbers;
import com.fixgo.user.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;

@Component
public class AdminBootstrap implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);
    private final BootstrapAdminProperties properties;
    private final UserRepository users;
    private final UserIdentityRepository identities;
    private final Clock clock;

    public AdminBootstrap(BootstrapAdminProperties properties, UserRepository users,
                          UserIdentityRepository identities, Clock clock) {
        this.properties = properties;
        this.users = users;
        this.identities = identities;
        this.clock = clock;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (properties.phone() == null || properties.phone().isBlank()) return;
        String phone = PhoneNumbers.toE164(properties.phone());
        var now = clock.instant();
        var existing = identities.findByProviderAndProviderUid(IdentityProvider.PHONE, phone).orElse(null);
        if (existing != null) {
            if (existing.getUser().getRole() != Role.ADMIN) {
                existing.getUser().changeRole(Role.ADMIN);
                log.info("Bootstrap: promoted existing account to ADMIN");
            }
            return;
        }
        var admin = users.save(new User(Role.ADMIN, properties.fullName(), now));
        identities.save(new UserIdentity(admin, IdentityProvider.PHONE, phone, true, now, null));
        log.info("Bootstrap: created ADMIN account (signs in via OTP)");
    }
}
