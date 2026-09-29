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
    private final boolean demoAccountsEnabled;
    private final String demoAdminPassword;
    private final String demoReviewerPassword;
    private final String demoOperatorPassword;

    public HostedAccountInitializer(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            @Value("${app.bootstrap.admin-email}") String adminEmail,
            @Value("${app.bootstrap.admin-password}") String adminPassword,
            @Value("${app.bootstrap.disable-seeded-accounts:true}") boolean disableSeededAccounts,
            @Value("${demo.accounts.enabled:false}") boolean demoAccountsEnabled,
            @Value("${demo.accounts.admin-password:admin123}") String demoAdminPassword,
            @Value("${demo.accounts.reviewer-password:reviewer123}") String demoReviewerPassword,
            @Value("${demo.accounts.operator-password:operator123}") String demoOperatorPassword) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.adminEmail = adminEmail;
        this.adminPassword = adminPassword;
        this.disableSeededAccounts = disableSeededAccounts;
        this.demoAccountsEnabled = demoAccountsEnabled;
        this.demoAdminPassword = demoAdminPassword;
        this.demoReviewerPassword = demoReviewerPassword;
        this.demoOperatorPassword = demoOperatorPassword;
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
        admin.setPasswordHash(passwordEncoder.encode(
                demoAccountsEnabled ? demoAdminPassword : adminPassword));
        admin.setActive(true);
        userRepository.save(admin);

        if (disableSeededAccounts) {
            for (String email : SEEDED_NON_ADMIN_EMAILS) {
                userRepository.findByEmail(email).ifPresent(user -> {
                    user.setActive(false);
                    userRepository.save(user);
                });
            }
        } else {
            // The role accounts are part of the seeded operational workflow.
            // Re-enable accounts that an earlier hosted deployment disabled so
            // Operator, Reviewer and Senior Reviewer access recovers on restart.
            for (String email : SEEDED_NON_ADMIN_EMAILS) {
                userRepository.findByEmail(email).ifPresent(user -> {
                    user.setActive(true);
                    if (demoAccountsEnabled) {
                        String password = "OPERATOR".equalsIgnoreCase(user.getRole())
                                ? demoOperatorPassword : demoReviewerPassword;
                        user.setPasswordHash(passwordEncoder.encode(password));
                    }
                    userRepository.save(user);
                });
            }
        }
    }
}
