package com.sih.materialmaster.controller;

import com.sih.materialmaster.entity.HarmonizationJob;
import com.sih.materialmaster.security.UserPrincipal;
import com.sih.materialmaster.service.HarmonizationJobService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Objects;

@RestController
@RequestMapping("/api/jobs")
public class JobController {

    private final HarmonizationJobService jobService;

    public JobController(HarmonizationJobService jobService) {
        this.jobService = jobService;
    }

    @GetMapping("/{id}")
    @Transactional(readOnly = true)
    public ResponseEntity<?> getJobStatus(@PathVariable Long id,
            @AuthenticationPrincipal UserPrincipal user) {
        HarmonizationJob job = jobService.getJob(id);
        if ("OPERATOR".equals(user.getRole()) && (job.getCpse() == null || !Objects.equals(user.getCpseId(), job.getCpse().getCpseId()))) {
            throw new AccessDeniedException("Job belongs to another CPSE");
        }
        return ResponseEntity.ok(jobService.toJobStatusMap(job));
    }

    @GetMapping(value = "/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> streamJobEvents(@PathVariable Long id,
            @AuthenticationPrincipal UserPrincipal user) {
        HarmonizationJob job = jobService.getJob(id);
        if ("OPERATOR".equals(user.getRole()) && (job.getCpse() == null || !Objects.equals(user.getCpseId(), job.getCpse().getCpseId()))) {
            throw new AccessDeniedException("Job belongs to another CPSE");
        }
        return ResponseEntity.ok()
                .header("X-Accel-Buffering", "no")
                .header("Cache-Control", "no-cache, no-transform")
                .body(jobService.subscribe(id));
    }
}
