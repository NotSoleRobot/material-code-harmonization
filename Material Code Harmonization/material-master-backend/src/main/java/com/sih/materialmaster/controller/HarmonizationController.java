package com.sih.materialmaster.controller;

import com.sih.materialmaster.dto.CompareResponse;
import com.sih.materialmaster.dto.HarmonizationResultDto;
import com.sih.materialmaster.dto.MaterialInfoDto;
import com.sih.materialmaster.entity.HarmonizationJob;
import com.sih.materialmaster.entity.Material;
import com.sih.materialmaster.entity.User;
import com.sih.materialmaster.repository.MaterialRepository;
import com.sih.materialmaster.repository.UserRepository;
import com.sih.materialmaster.security.UserPrincipal;
import com.sih.materialmaster.service.HarmonizationJobService;
import com.sih.materialmaster.service.HarmonizationService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * Controller exposing AI harmonization and matching operations (FR2, FR4).
 */
@RestController
@RequestMapping("/api/harmonization")
public class HarmonizationController {

    private final HarmonizationService harmonizationService;
    private final HarmonizationJobService jobService;
    private final MaterialRepository materialRepository;
    private final UserRepository userRepository;

    public HarmonizationController(HarmonizationService harmonizationService,
                                   HarmonizationJobService jobService,
                                   MaterialRepository materialRepository,
                                   UserRepository userRepository) {
        this.harmonizationService = harmonizationService;
        this.jobService = jobService;
        this.materialRepository = materialRepository;
        this.userRepository = userRepository;
    }

    /**
     * Pairwise comparison between two arbitrary material definitions.
     */
    @PostMapping("/compare")
    public ResponseEntity<CompareResponse> compare(@RequestBody Map<String, MaterialInfoDto> body) {
        MaterialInfoDto a = body.get("material_a");
        MaterialInfoDto b = body.get("material_b");
        if (a == null || b == null) {
            throw new IllegalArgumentException("Both 'material_a' and 'material_b' must be provided.");
        }
        return ResponseEntity.ok(harmonizationService.compare(a, b));
    }

    /**
     * Trigger candidate retrieval and match proposals for a single ingested material.
     */
    @PostMapping("/material/{materialId}")
    @Transactional
    public ResponseEntity<HarmonizationResultDto> harmonizeSingle(@PathVariable Long materialId,
            @AuthenticationPrincipal UserPrincipal user) {
        Material material = materialRepository.findById(materialId).orElseThrow(() -> new IllegalArgumentException("Material not found"));
        if (user != null && "OPERATOR".equals(user.getRole()) && !Objects.equals(user.getCpseId(), material.getCpse().getCpseId())) {
            throw new AccessDeniedException("Material belongs to another CPSE");
        }
        return ResponseEntity.ok(harmonizationService.harmonizeMaterial(materialId));
    }

    /**
     * WP3 Task 10 / P-05: Asynchronously batch harmonize all currently unmatched materials.
     * Returns 202 Accepted with jobId so frontend can poll /api/jobs/{id}.
     */
    @PostMapping("/harmonize-all")
    public ResponseEntity<Map<String, Object>> harmonizeAll(@AuthenticationPrincipal UserPrincipal currentUser) {
        List<Material> unmatched = materialRepository.findUnmatched();
        List<Long> materialIds = unmatched.stream().map(Material::getMaterialId).toList();

        User user = currentUser != null ? userRepository.findById(currentUser.getUserId()).orElse(null) : null;
        HarmonizationJob job = jobService.createJob(user, materialIds.size());

        jobService.processAsync(job.getJobId(), materialIds);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("jobId", job.getJobId());
        response.put("status", job.getStatus());
        response.put("totalItems", materialIds.size());
        response.put("message", "Asynchronous harmonization job #" + job.getJobId() + " started.");

        return ResponseEntity.accepted().body(response);
    }
}
