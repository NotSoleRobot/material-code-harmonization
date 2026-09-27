package com.sih.materialmaster.controller;

import com.sih.materialmaster.dto.CreateUserRequest;
import com.sih.materialmaster.dto.UserProfileDto;
import com.sih.materialmaster.entity.Cpse;
import com.sih.materialmaster.entity.MaterialCategory;
import com.sih.materialmaster.entity.ReviewerAssignment;
import com.sih.materialmaster.entity.User;
import com.sih.materialmaster.repository.CpseRepository;
import com.sih.materialmaster.repository.MaterialCategoryRepository;
import com.sih.materialmaster.repository.ReviewerAssignmentRepository;
import com.sih.materialmaster.repository.UserRepository;
import com.sih.materialmaster.security.UserPrincipal;
import com.sih.materialmaster.service.AuditService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/users")
@PreAuthorize("hasRole('ADMIN')")
public class AdminUserController {

    private final UserRepository userRepository;
    private final CpseRepository cpseRepository;
    private final MaterialCategoryRepository categoryRepository;
    private final ReviewerAssignmentRepository reviewerAssignmentRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuditService auditService;

    public AdminUserController(UserRepository userRepository,
                               CpseRepository cpseRepository,
                               MaterialCategoryRepository categoryRepository,
                               ReviewerAssignmentRepository reviewerAssignmentRepository,
                               PasswordEncoder passwordEncoder,
                               AuditService auditService) {
        this.userRepository = userRepository;
        this.cpseRepository = cpseRepository;
        this.categoryRepository = categoryRepository;
        this.reviewerAssignmentRepository = reviewerAssignmentRepository;
        this.passwordEncoder = passwordEncoder;
        this.auditService = auditService;
    }

    @GetMapping
    public List<UserProfileDto> listAllUsers() {
        List<User> users = userRepository.findAll();
        List<UserProfileDto> result = new ArrayList<>();
        for (User u : users) {
            List<Long> assignedCats = reviewerAssignmentRepository.findCategoryIdsByUserId(u.getUserId());
            UserProfileDto dto = new UserProfileDto(
                    u.getUserId(),
                    u.getName(),
                    u.getEmail(),
                    u.getRole(),
                    u.getCpse() != null ? new UserProfileDto.CpseInfo(u.getCpse().getCpseId(), u.getCpse().getName(), u.getCpse().getSector()) : null,
                    assignedCats
            );
            result.add(dto);
        }
        return result;
    }

    @GetMapping("/options")
    public Map<String, Object> getProvisioningOptions() {
        List<Map<String, Object>> cpses = cpseRepository.findAll().stream()
                .sorted(Comparator.comparing(Cpse::getName))
                .map(cpse -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("id", cpse.getCpseId());
                    item.put("name", cpse.getName());
                    item.put("sector", cpse.getSector());
                    return item;
                })
                .toList();
        List<Map<String, Object>> categories = categoryRepository.findAll().stream()
                .filter(category -> category.getLevel() != null && category.getLevel() == 3)
                .sorted(Comparator.comparing(MaterialCategory::getName))
                .map(category -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("id", category.getCategoryId());
                    item.put("name", category.getName());
                    return item;
                })
                .toList();
        return Map.of("cpses", cpses, "categories", categories);
    }

    @PostMapping
    @Transactional
    public ResponseEntity<UserProfileDto> createUser(
            @Valid @RequestBody CreateUserRequest request,
            @AuthenticationPrincipal UserPrincipal adminPrincipal) {

        if (userRepository.existsByEmail(request.getEmail().trim().toLowerCase())) {
            throw new IllegalArgumentException("User with email " + request.getEmail() + " already exists.");
        }

        String role = request.getRole().trim().toUpperCase();
        if (!List.of("OPERATOR", "REVIEWER", "SENIOR_REVIEWER", "ADMIN").contains(role)) {
            throw new IllegalArgumentException("Invalid role: " + role);
        }

        Cpse cpse = null;
        if ("OPERATOR".equals(role)) {
            if (request.getCpseId() == null) {
                throw new IllegalArgumentException("CPSE is mandatory for OPERATOR accounts.");
            }
            cpse = cpseRepository.findById(request.getCpseId())
                    .orElseThrow(() -> new IllegalArgumentException("No CPSE found with ID: " + request.getCpseId()));
        }

        User user = new User();
        user.setName(request.getName().trim());
        user.setEmail(request.getEmail().trim().toLowerCase());
        user.setPasswordHash(passwordEncoder.encode(request.getPassword()));
        user.setRole(role);
        user.setCpse(cpse);
        user.setActive(true);

        User savedUser = userRepository.save(user);

        // Process reviewer assignments if provided
        List<Long> assignedCategoryIds = new ArrayList<>();
        if (request.getAssignedCategoryIds() != null && ("REVIEWER".equals(role) || "SENIOR_REVIEWER".equals(role))) {
            for (Long catId : request.getAssignedCategoryIds()) {
                MaterialCategory cat = categoryRepository.findById(catId).orElse(null);
                if (cat != null) {
                    ReviewerAssignment ra = new ReviewerAssignment();
                    ra.setUser(savedUser);
                    ra.setCategory(cat);
                    reviewerAssignmentRepository.save(ra);
                    assignedCategoryIds.add(catId);
                }
            }
        }

        User adminUser = adminPrincipal != null ? userRepository.findById(adminPrincipal.getUserId()).orElse(null) : null;
        auditService.logEvent(adminUser, "USER_CREATED", "USER", savedUser.getUserId(), null, "Created user: " + savedUser.getEmail() + " (" + role + ")");

        UserProfileDto response = new UserProfileDto(
                savedUser.getUserId(),
                savedUser.getName(),
                savedUser.getEmail(),
                savedUser.getRole(),
                cpse != null ? new UserProfileDto.CpseInfo(cpse.getCpseId(), cpse.getName(), cpse.getSector()) : null,
                assignedCategoryIds
        );

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
}
