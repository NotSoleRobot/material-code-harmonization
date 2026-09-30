package com.sih.materialmaster.controller;

import com.sih.materialmaster.dto.LoginRequest;
import com.sih.materialmaster.dto.LoginResponse;
import com.sih.materialmaster.dto.UserProfileDto;
import com.sih.materialmaster.entity.User;
import com.sih.materialmaster.repository.ReviewerAssignmentRepository;
import com.sih.materialmaster.repository.UserRepository;
import com.sih.materialmaster.security.JwtTokenProvider;
import com.sih.materialmaster.security.UserPrincipal;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthenticationManager authenticationManager;
    private final JwtTokenProvider tokenProvider;
    private final UserRepository userRepository;
    private final ReviewerAssignmentRepository reviewerAssignmentRepository;
    private final long jwtExpirationMs;

    public AuthController(AuthenticationManager authenticationManager,
                          JwtTokenProvider tokenProvider,
                          UserRepository userRepository,
                          ReviewerAssignmentRepository reviewerAssignmentRepository,
                          @Value("${jwt.expiration-ms:86400000}") long jwtExpirationMs) {
        this.authenticationManager = authenticationManager;
        this.tokenProvider = tokenProvider;
        this.userRepository = userRepository;
        this.reviewerAssignmentRepository = reviewerAssignmentRepository;
        this.jwtExpirationMs = jwtExpirationMs;
    }

    @PostMapping("/login")
    @Transactional(readOnly = true)
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest loginRequest) {
        try {
            Authentication authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(
                            loginRequest.getEmail().trim().toLowerCase(),
                            loginRequest.getPassword()
                    )
            );

            SecurityContextHolder.getContext().setAuthentication(authentication);
            UserPrincipal userPrincipal = (UserPrincipal) authentication.getPrincipal();
            String jwt = tokenProvider.generateToken(userPrincipal);

            User user = userRepository.findById(userPrincipal.getUserId()).orElse(null);
            List<Long> assignedCats = reviewerAssignmentRepository.findCategoryIdsByUserId(userPrincipal.getUserId());

            UserProfileDto profile = buildProfile(user, assignedCats);
            LoginResponse response = new LoginResponse(jwt, "Bearer", jwtExpirationMs / 1000, profile);

            return ResponseEntity.ok(response);
        } catch (BadCredentialsException ex) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body("{\"error\":\"Unauthorized\",\"message\":\"Invalid email or password\"}");
        }
    }

    @GetMapping("/me")
    @Transactional(readOnly = true)
    public ResponseEntity<UserProfileDto> getCurrentUser(@AuthenticationPrincipal UserPrincipal currentUser) {
        if (currentUser == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        User user = userRepository.findById(currentUser.getUserId())
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        List<Long> assignedCats = reviewerAssignmentRepository.findCategoryIdsByUserId(user.getUserId());
        return ResponseEntity.ok(buildProfile(user, assignedCats));
    }

    @PostMapping("/logout")
    public ResponseEntity<?> logout() {
        SecurityContextHolder.clearContext();
        return ResponseEntity.ok("{\"message\":\"Logged out successfully\"}");
    }

    private UserProfileDto buildProfile(User user, List<Long> assignedCategoryIds) {
        UserProfileDto dto = new UserProfileDto();
        if (user == null) return dto;

        dto.setUserId(user.getUserId());
        dto.setName(user.getName());
        dto.setEmail(user.getEmail());
        dto.setRole(user.getRole());

        if (user.getCpse() != null) {
            dto.setCpse(new UserProfileDto.CpseInfo(
                    user.getCpse().getCpseId(),
                    user.getCpse().getName(),
                    user.getCpse().getSector()
            ));
        }

        dto.setAssignedCategoryIds(assignedCategoryIds != null ? assignedCategoryIds : List.of());
        return dto;
    }
}
