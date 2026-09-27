package com.sih.materialmaster.controller;

import com.sih.materialmaster.entity.HarmonizationJob;
import com.sih.materialmaster.service.HarmonizationJobService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/jobs")
public class JobController {

    private final HarmonizationJobService jobService;

    public JobController(HarmonizationJobService jobService) {
        this.jobService = jobService;
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> getJobStatus(@PathVariable Long id,
            @org.springframework.security.core.annotation.AuthenticationPrincipal com.sih.materialmaster.security.UserPrincipal user) {
        HarmonizationJob job = jobService.getJob(id);
        if ("OPERATOR".equals(user.getRole()) && (job.getCpse() == null || !java.util.Objects.equals(user.getCpseId(), job.getCpse().getCpseId()))) {
            throw new org.springframework.security.access.AccessDeniedException("Job belongs to another CPSE");
        }
        java.util.Map<String,Object> response = new java.util.LinkedHashMap<>();
        response.put("jobId", job.getJobId());
        response.put("status", job.getStatus());
        response.put("totalItems", job.getTotalItems());
        response.put("processedItems", job.getProcessedItems());
        response.put("autoHarmonized", job.getAutoHarmonized());
        response.put("pendingReview", job.getPendingReview());
        response.put("distinctMaterials", job.getDistinctMaterials());
        response.put("errorMessage", job.getErrorMessage());
        return ResponseEntity.ok(response);
    }
}
