package com.sih.materialmaster.controller;

import com.sih.materialmaster.entity.User;
import com.sih.materialmaster.repository.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
@Profile("demo")
public class AuthDemoController {
    private final UserRepository users;
    private final String adminPassword;
    private final String reviewerPassword;
    private final String operatorPassword;

    public AuthDemoController(UserRepository users,
            @Value("${demo.accounts.admin-password}") String adminPassword,
            @Value("${demo.accounts.reviewer-password}") String reviewerPassword,
            @Value("${demo.accounts.operator-password}") String operatorPassword) {
        this.users = users;
        this.adminPassword = adminPassword;
        this.reviewerPassword = reviewerPassword;
        this.operatorPassword = operatorPassword;
    }

    @GetMapping("/demo-accounts")
    @Transactional(readOnly = true)
    public ResponseEntity<List<Map<String, Object>>> accounts() {
        return ResponseEntity.ok(users.findAll().stream()
                .filter(User::getActive)
                .filter(user -> switch (user.getRole()) {
                    case "ADMIN", "SENIOR_REVIEWER" -> true;
                    case "OPERATOR" -> user.getCpse() != null && "ONGC".equalsIgnoreCase(user.getCpse().getName());
                    default -> false;
                })
                .map(this::toDemoAccount)
                .toList());
    }

    private Map<String, Object> toDemoAccount(User user) {
        Map<String, Object> account = new LinkedHashMap<>();
        account.put("label", switch (user.getRole()) {
            case "OPERATOR" -> "CPSE Operator";
            case "SENIOR_REVIEWER" -> "Senior Reviewer";
            default -> "Central Administrator";
        });
        account.put("email", user.getEmail());
        account.put("password", passwordFor(user.getRole()));
        account.put("role", user.getRole());
        account.put("org", user.getCpse() == null ? "National Committee" : user.getCpse().getName());
        account.put("blurb", switch (user.getRole()) {
            case "OPERATOR" -> "Ingests and tracks its CPSE material catalog";
            case "SENIOR_REVIEWER" -> "Reviews and adjudicates suggested material matches";
            default -> "Administers users and national analytics";
        });
        return account;
    }

    private String passwordFor(String role) {
        return switch (role) {
            case "ADMIN" -> adminPassword;
            case "OPERATOR" -> operatorPassword;
            default -> reviewerPassword;
        };
    }
}
