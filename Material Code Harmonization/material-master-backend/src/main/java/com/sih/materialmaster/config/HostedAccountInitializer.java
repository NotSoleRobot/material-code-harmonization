package com.sih.materialmaster.config;

import com.sih.materialmaster.entity.User;
import com.sih.materialmaster.repository.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** Replaces known seed credentials whenever the hosted profile starts. */
@Component
@Profile("hosted")
public class HostedAccountInitializer implements ApplicationRunner {

    private static final List<String> SEEDED_NON_ADMIN_EMAILS = List.of(
            "senior.reviewer@numm.gov.in",
            "reviewer.mech@numm.gov.in",
            "reviewer.elec@numm.gov.in",
            "operator@ongc.co.in",
            "operator@iocl.in",
            "operator@gail.co.in",
            "operator@bhel.in",
            "operator@sail.in"
    );

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final String adminEmail;
    private final String adminPassword;
    private final boolean disableSeededAccounts;

    public HostedAccountInitializer(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            @Value("${app.bootstrap.admin-email}") String adminEmail,
            @Value("${app.bootstrap.admin-password}") String adminPassword,
            @Value("${app.bootstrap.disable-seeded-accounts:true}") boolean disableSeededAccounts) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.adminEmail = adminEmail;
        this.adminPassword = adminPassword;
        this.disableSeededAccounts = disableSeededAccounts;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (adminPassword == null || adminPassword.length() < 14) {
            throw new IllegalStateException(
                    "BOOTSTRAP_ADMIN_PASSWORD must contain at least 14 characters in the hosted profile");
        }

        User admin = userRepository.findByEmail(adminEmail.trim().toLowerCase())
                .orElseThrow(() -> new IllegalStateException(
                        "Bootstrap admin account does not exist: " + adminEmail));
        if (!"ADMIN".equalsIgnoreCase(admin.getRole())) {
            throw new IllegalStateException("Bootstrap account must have ADMIN role: " + adminEmail);
        }
        admin.setPasswordHash(passwordEncoder.encode(adminPassword));
        admin.setActive(true);
        userRepository.save(admin);

        if (disableSeededAccounts) {
            for (String email : SEEDED_NON_ADMIN_EMAILS) {
                userRepository.findByEmail(email).ifPresent(user -> {
                    user.setActive(false);
                    userRepository.save(user);
                });
            }
        }
    }
}
