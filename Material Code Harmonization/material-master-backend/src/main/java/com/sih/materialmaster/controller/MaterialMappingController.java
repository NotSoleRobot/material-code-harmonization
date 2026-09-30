package com.sih.materialmaster.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sih.materialmaster.dto.*;
import com.sih.materialmaster.entity.AuditTrail;
import com.sih.materialmaster.entity.MaterialMapping;
import com.sih.materialmaster.entity.User;
import com.sih.materialmaster.repository.AuditTrailRepository;
import com.sih.materialmaster.repository.MaterialMappingRepository;
import com.sih.materialmaster.repository.ReviewerAssignmentRepository;
import com.sih.materialmaster.repository.UserRepository;
import com.sih.materialmaster.security.UserPrincipal;
import com.sih.materialmaster.service.AuditService;
import com.sih.materialmaster.service.GovernanceService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * FR8: Human review & governance decision endpoints.
 * FR9: Cryptographic audit log retrieval & verification (W3.6).
 */
@RestController
@RequestMapping("/api/mappings")
public class MaterialMappingController {

    private static final List<String> MATERIAL_AUDIT_ENTITY_TYPES = List.of(
            "MATERIAL", "MATERIAL_GROUP", "MATERIAL_MAPPING", "GROUP_RELATION"
    );

    private final MaterialMappingRepository mappingRepository;
    private final UserRepository userRepository;
    private final ReviewerAssignmentRepository reviewerAssignmentRepository;
    private final AuditTrailRepository auditTrailRepository;
    private final GovernanceService governanceService;
    private final AuditService auditService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public MaterialMappingController(MaterialMappingRepository mappingRepository,
                                     UserRepository userRepository,
                                     ReviewerAssignmentRepository reviewerAssignmentRepository,
                                     AuditTrailRepository auditTrailRepository,
                                     GovernanceService governanceService,
                                     AuditService auditService) {
        this.mappingRepository = mappingRepository;
        this.userRepository = userRepository;
        this.reviewerAssignmentRepository = reviewerAssignmentRepository;
        this.auditTrailRepository = auditTrailRepository;
        this.governanceService = governanceService;
        this.auditService = auditService;
    }

    /**
     * FR8: Review queue. Filtered to reviewer's assigned commodity classes if assignments exist (W4.1).
     */
    @GetMapping
    public Map<String, Object> listMappings(
            @RequestParam(defaultValue = "PENDING") String status,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @AuthenticationPrincipal UserPrincipal currentUser) {

        PageRequest pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100));
        Page<MaterialMapping> mappings;
        if ("REVIEWER".equals(currentUser.getRole()) && categoryId != null &&
                !reviewerAssignmentRepository.findCategoryIdsByUserId(currentUser.getUserId()).contains(categoryId)) {
            throw new org.springframework.security.access.AccessDeniedException("Category is not assigned to you");
        }
        if (categoryId != null) {
            mappings = mappingRepository.findByStatusAndCategories(status.toUpperCase(), List.of(categoryId), pageable);
        } else if (currentUser != null && "REVIEWER".equalsIgnoreCase(currentUser.getRole())) {
            List<Long> assignedCats = reviewerAssignmentRepository.findCategoryIdsByUserId(currentUser.getUserId());
            if (!assignedCats.isEmpty()) {
                mappings = mappingRepository.findByStatusAndCategories(status.toUpperCase(), assignedCats, pageable);
            } else {
                mappings = Page.empty(pageable);
            }
        } else {
            mappings = mappingRepository.findByStatus(status.toUpperCase(), pageable);
        }

        return Map.of(
                "content", mappings.getContent().stream().map(this::toReviewResponse).toList(),
                "page", mappings.getNumber(),
                "size", mappings.getSize(),
                "totalElements", mappings.getTotalElements(),
                "totalPages", mappings.getTotalPages()
        );
    }

    @GetMapping("/{id}")
    @Transactional(readOnly = true)
    public ResponseEntity<MappingReviewResponse> getMappingById(@PathVariable Long id, @AuthenticationPrincipal UserPrincipal currentUser) {
        MaterialMapping mapping = mappingRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("No mapping found with ID: " + id));
        governanceService.checkAssignment(mapping, userRepository.findById(currentUser.getUserId()).orElseThrow());
        return ResponseEntity.ok(toReviewResponse(mapping));
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize("hasAnyRole('REVIEWER', 'SENIOR_REVIEWER', 'ADMIN')")
    public ResponseEntity<MappingReviewResponse> approve(
            @PathVariable Long id,
            @RequestBody(required = false) MappingDecisionRequest request,
            @AuthenticationPrincipal UserPrincipal currentUser) {

        User reviewer = userRepository.findById(currentUser.getUserId())
                .orElseThrow(() -> new IllegalArgumentException("Authenticated user not found"));

        String notes = request != null && request.getNotes() != null ? request.getNotes() : "Verified attributes match";
        MaterialMapping saved = governanceService.decideMapping(id, reviewer, "CONFIRMED", notes);
        return ResponseEntity.ok(toReviewResponse(saved));
    }

    @PostMapping("/{id}/reject")
    @PreAuthorize("hasAnyRole('REVIEWER', 'SENIOR_REVIEWER', 'ADMIN')")
    public ResponseEntity<MappingReviewResponse> reject(
            @PathVariable Long id,
            @RequestBody(required = false) MappingDecisionRequest request,
            @AuthenticationPrincipal UserPrincipal currentUser) {

        User reviewer = userRepository.findById(currentUser.getUserId())
                .orElseThrow(() -> new IllegalArgumentException("Authenticated user not found"));

        String notes = request != null && request.getNotes() != null ? request.getNotes() : "Specification mismatch identified";
        MaterialMapping saved = governanceService.decideMapping(id, reviewer, "REJECTED", notes);
        return ResponseEntity.ok(toReviewResponse(saved));
    }

    /**
     * Edit mapping and group standardized attributes before confirming (BUG-12).
     */
    @PostMapping("/{id}/edit")
    @PreAuthorize("hasAnyRole('REVIEWER', 'SENIOR_REVIEWER', 'ADMIN')")
    public ResponseEntity<MappingReviewResponse> edit(
            @PathVariable Long id,
            @Valid @RequestBody EditMappingRequest request,
            @AuthenticationPrincipal UserPrincipal currentUser) {

        User reviewer = userRepository.findById(currentUser.getUserId())
                .orElseThrow(() -> new IllegalArgumentException("Authenticated user not found"));

        MaterialMapping saved = governanceService.editMapping(
                id, reviewer,
                request.getStandardizedDescription(),
                request.getStandardizedSpecification(),
                request.getStandardizedUom(),
                request.getNotes()
        );
        return ResponseEntity.ok(toReviewResponse(saved));
    }

    /**
     * Bulk approve all HIGH-confidence pending mappings in assigned class (Innovation #2 / W4.6).
     */
    @PostMapping("/bulk-approve")
    @PreAuthorize("hasAnyRole('REVIEWER', 'SENIOR_REVIEWER', 'ADMIN')")
    public ResponseEntity<?> bulkApproveHighConfidence(
            @RequestParam(required = false) Long categoryId,
            @AuthenticationPrincipal UserPrincipal currentUser) {

        User reviewer = userRepository.findById(currentUser.getUserId())
                .orElseThrow(() -> new IllegalArgumentException("Authenticated user not found"));

        GovernanceService.BulkApprovalResult result = governanceService.bulkApproveHighConfidence(reviewer, categoryId);
        return ResponseEntity.ok(result);
    }

    @PostMapping("/{id}/approve-and-publish")
    @PreAuthorize("hasAnyRole('SENIOR_REVIEWER', 'ADMIN')")
    public ResponseEntity<Map<String, String>> approveAndPublish(
            @PathVariable Long id, @AuthenticationPrincipal UserPrincipal currentUser) {
        User actor = userRepository.findById(currentUser.getUserId())
                .orElseThrow(() -> new IllegalArgumentException("Authenticated user not found"));
        String code = governanceService.approveAndPublish(id, actor);
        return ResponseEntity.ok(Map.of("code", code, "status", "ACTIVE",
                "message", "Mapping approved and national code published"));
    }

    /**
     * Admin override to supersede a previously confirmed or rejected decision (W3.3 / BUG-12).
     */
    @PostMapping("/{id}/supersede")
    @PreAuthorize("hasAnyRole('ADMIN', 'SENIOR_REVIEWER')")
    public ResponseEntity<MappingReviewResponse> supersede(
            @PathVariable Long id,
            @Valid @RequestBody SupersedeMappingRequest request,
            @AuthenticationPrincipal UserPrincipal currentUser) {

        User admin = userRepository.findById(currentUser.getUserId())
                .orElseThrow(() -> new IllegalArgumentException("Authenticated admin not found"));

        MaterialMapping saved = governanceService.supersedeMapping(id, admin, request.getNewDecision(), request.getReason());
        return ResponseEntity.ok(toReviewResponse(saved));
    }

    /**
     * FR9: Audit trail log query.
     */
    @GetMapping("/audit")
    @Transactional(readOnly = true)
    public List<AuditTrailDto> getAuditLogs(@RequestParam(required = false) String action) {
        List<AuditTrail> logs = (action != null && !action.isBlank() && !"ALL".equalsIgnoreCase(action))
                ? auditTrailRepository.findByActionAndEntityTypeInOrderByTimestampDesc(
                        action, MATERIAL_AUDIT_ENTITY_TYPES)
                : auditTrailRepository.findTop500ByEntityTypeInOrderByTimestampDesc(
                        MATERIAL_AUDIT_ENTITY_TYPES);

        return logs.stream().map(this::toAuditDto).toList();
    }

    /**
     * Cryptographically verifies the unbroken tamper-evident audit hash chain (W3.6).
     */
    @GetMapping("/audit/verify")
    public ResponseEntity<Map<String, Object>> verifyAuditChain() {
        return ResponseEntity.ok(auditService.verifyChainIntegrity());
    }

    private AuditTrailDto toAuditDto(AuditTrail a) {
        return new AuditTrailDto(
                a.getAuditId(),
                a.getUser() != null ? a.getUser().getUserId() : null,
                a.getUser() != null ? a.getUser().getName() : "System Automation",
                a.getAction(),
                a.getEntityType(),
                a.getEntityId(),
                a.getOldValue(),
                a.getNewValue(),
                a.getPrevHash(),
                a.getRowHash(),
                a.getTimestamp()
        );
    }

    private MappingReviewResponse toReviewResponse(MaterialMapping mapping) {
        MappingReviewResponse resp = new MappingReviewResponse();
        resp.setMappingId(mapping.getMappingId());
        resp.setStatus(mapping.getStatus());
        resp.setConfidenceScore(mapping.getConfidenceScore());
        resp.setConfidenceTier(mapping.getConfidenceTier());

        if (mapping.getMaterial() != null) {
            resp.setMaterialId(mapping.getMaterial().getMaterialId());
            resp.setMaterialDescription(mapping.getMaterial().getDescription());
            resp.setMaterialSpecification(mapping.getMaterial().getSpecification());
            resp.setUnitOfMeasure(mapping.getMaterial().getUnitOfMeasure());
            resp.setCpseMaterialCode(mapping.getMaterial().getCpseMaterialCode());
            resp.setCpseName(mapping.getMaterial().getCpse() != null ? mapping.getMaterial().getCpse().getName() : "Unknown");
        }

        if (mapping.getGroup() != null) {
            resp.setGroupId(mapping.getGroup().getGroupId());
            resp.setCommonMaterialCode(mapping.getGroup().getCommonMaterialCode());
            resp.setProvisionalRef(mapping.getGroup().getProvisionalRef());
            resp.setStandardizedDescription(mapping.getGroup().getStandardizedDescription());
            resp.setStandardizedSpecification(mapping.getGroup().getStandardizedSpecification());
            resp.setStandardizedUom(mapping.getGroup().getStandardizedUom());
            if (mapping.getGroup().getCategory() != null) {
                resp.setCategoryName(mapping.getGroup().getCategory().getName());
            }
        }

        resp.setExplanationJson(mapping.getExplanationJson());
        if (mapping.getExplanationJson() != null && !mapping.getExplanationJson().isBlank()) {
            try {
                ExplanationDto exp = objectMapper.readValue(mapping.getExplanationJson(), ExplanationDto.class);
                resp.setExplanation(exp);
            } catch (Exception ignored) {}
        }

        if (mapping.getReviewedBy() != null) {
            resp.setReviewedByName(mapping.getReviewedBy().getName());
            resp.setReviewedByUserId(mapping.getReviewedBy().getUserId());
        }
        resp.setSupersedesMappingId(mapping.getSupersedesMapping() != null ? mapping.getSupersedesMapping().getMappingId() : null);
        resp.setSupersededByMappingId(mapping.getSupersededByMapping() != null ? mapping.getSupersededByMapping().getMappingId() : null);
        resp.setReviewedAt(mapping.getReviewedAt());
        resp.setDecisionNotes(mapping.getDecisionNotes());
        resp.setCreatedAt(mapping.getCreatedAt());

        return resp;
    }
}
