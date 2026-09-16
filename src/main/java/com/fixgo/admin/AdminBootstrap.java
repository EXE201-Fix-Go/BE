package com.fixgo.admin;

import com.fixgo.auth.AuthDtos;
import com.fixgo.user.*;
import jakarta.validation.Validator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;

@Component
public class AdminBootstrap implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);
    private final BootstrapAdminProperties properties;
    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final Validator validator;
    private final Clock clock;

    public AdminBootstrap(BootstrapAdminProperties properties, UserRepository users,
                          PasswordEncoder encoder, Validator validator, Clock clock) {
        this.properties = properties;
        this.users = users;
        this.encoder = encoder;
        this.validator = validator;
        this.clock = clock;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        boolean emailEmpty = properties.email() == null || properties.email().isBlank();
        boolean passwordEmpty = properties.password() == null || properties.password().isBlank();
        if (emailEmpty && passwordEmpty) return;
        if (emailEmpty || passwordEmpty) {
            throw new IllegalStateException("Set both BOOTSTRAP_ADMIN_EMAIL and BOOTSTRAP_ADMIN_PASSWORD.");
        }
        var request = new AuthDtos.RegisterRequest(properties.email(), properties.password(), properties.fullName(), null);
        if (!validator.validate(request).isEmpty()) {
            throw new IllegalStateException("Invalid bootstrap administrator configuration.");
        }
        var existing = users.findByEmail(request.email());
        if (existing.isPresent()) {
            if (existing.get().getRole() != Role.ADMIN) {
                throw new IllegalStateException("Bootstrap email belongs to a non-admin account. Choose an unused email.");
            }
            return;
        }
        users.saveAndFlush(new User(request.email(), encoder.encode(request.password()),
                request.fullName(), null, Role.ADMIN, clock.instant()));
        log.info("Administrator account provisioned. Bootstrap credentials can now be removed from configuration.");
    }
}
