package com.sih.materialmaster.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sih.materialmaster.exception.ConflictOfInterestException;
import com.sih.materialmaster.entity.*;
import com.sih.materialmaster.repository.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

/**
 * FR8: Human review and approval/rejection of AI-suggested mappings.
 * Enforces 4-eyes governance, Conflict of Interest (COI) isolation,
 * and authorized National Code minting (D3 / WP4).
 */
@Service
public class GovernanceService {

    private final MaterialMappingRepository mappingRepository;
    private final MaterialGroupRepository groupRepository;
    private final NationalCodeGenerator codeGenerator;
    private final AuditService auditService;
    private final ReviewerAssignmentRepository reviewerAssignmentRepository;
    private final MatchingFeedbackRepository matchingFeedbackRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public GovernanceService(MaterialMappingRepository mappingRepository,
                             MaterialGroupRepository groupRepository,
                             NationalCodeGenerator codeGenerator,
                             AuditService auditService,
                             ReviewerAssignmentRepository reviewerAssignmentRepository,
                             MatchingFeedbackRepository matchingFeedbackRepository) {
        this.mappingRepository = mappingRepository;
        this.groupRepository = groupRepository;
        this.codeGenerator = codeGenerator;
        this.auditService = auditService;
        this.reviewerAssignmentRepository = reviewerAssignmentRepository;
        this.matchingFeedbackRepository = matchingFeedbackRepository;
    }

    /**
     * Technical sign-off on a proposed mapping (Step 1 of Governance Loop).
     * Enforces Conflict of Interest (COI): A reviewer cannot approve a material submitted by their own CPSE.
     */
    @Transactional
    public MaterialMapping decideMapping(Long mappingId, User reviewer, String decision, String notes) {
        MaterialMapping mapping = mappingRepository.findById(mappingId)
                .orElseThrow(() -> new IllegalArgumentException("No mapping found with mappingId=" + mappingId));

        checkAssignment(mapping, reviewer);
        if (!Set.of("CONFIRMED", "APPROVE", "REJECTED").contains(decision.toUpperCase())) {
            throw new IllegalArgumentException("Invalid decision: " + decision);
        }
        if (!"PENDING".equalsIgnoreCase(mapping.getStatus())) {
            throw new IllegalStateException("Mapping #" + mappingId + " is already " + mapping.getStatus() + " — cannot decide without Admin override.");
        }

        // Conflict of interest check (W4.1 / D3):
        // Standard reviewer cannot decide a mapping where the material belongs to their own CPSE
        if (reviewer.getCpse() != null && mapping.getMaterial() != null && mapping.getMaterial().getCpse() != null) {
            if (reviewer.getCpse().getCpseId().equals(mapping.getMaterial().getCpse().getCpseId())) {
                if ("REVIEWER".equalsIgnoreCase(reviewer.getRole())) {
                    String reviewerCpseName = reviewer.getCpse().getName();
                    String materialCpseName = mapping.getMaterial().getCpse().getName();
                    throw new ConflictOfInterestException(
                            "Conflict of Interest: Reviewer belongs to the submitting CPSE (" + reviewerCpseName + "). Escalated to senior catalog committee.",
                            reviewerCpseName,
                            materialCpseName
                    );
                }
            }
        }

        String targetStatus = ("CONFIRMED".equalsIgnoreCase(decision) || "APPROVE".equalsIgnoreCase(decision))
                ? "CONFIRMED" : "REJECTED";

        return applyDecision(mapping, reviewer, targetStatus, notes);
    }

    /**
     * Internal status transition logic applied by both normal review and admin supersede paths.
     */
    private MaterialMapping applyDecision(MaterialMapping mapping, User actor, String targetStatus, String notes) {
        String oldStatus = mapping.getStatus();

        mapping.setStatus(targetStatus);
        mapping.setDecisionSource("HUMAN");
        mapping.setRoutingDecision("REVIEW_REQUIRED");
        mapping.setAutomaticallyDecidedAt(null);
        mapping.setReviewedBy(actor);
        mapping.setReviewedAt(LocalDateTime.now());
        mapping.setDecisionNotes(notes);
        MaterialMapping savedMapping = mappingRepository.save(mapping);

        MatchingFeedback feedback = new MatchingFeedback();
        feedback.setMapping(savedMapping);
        feedback.setMaterial(savedMapping.getMaterial());
        feedback.setCandidateGroup(savedMapping.getGroup());
        feedback.setReviewer(actor);
        feedback.setModelVersion(savedMapping.getModelVersion());
        feedback.setSuggestedRelationship(savedMapping.getMatchBasis());
        feedback.setReviewerDecision(targetStatus);
        feedback.setNotes(notes);
        matchingFeedbackRepository.save(feedback);

        // Audit the mapping decision (FR9)
        auditService.logEvent(
                actor,
                "MAPPING_" + targetStatus,
                "MATERIAL_MAPPING",
                savedMapping.getMappingId(),
                "status: " + oldStatus,
                "status: " + targetStatus + (notes != null ? ", notes: " + notes : "")
        );

        MaterialGroup group = savedMapping.getGroup();
        if (group != null) {
            if ("CONFIRMED".equals(targetStatus)) {
                // Confirmation keeps the group PROPOSED until senior reviewer publishes & mints
            } else if ("REJECTED".equals(targetStatus)) {
                // Check if any confirmed/pending mapping remains for this group
                long confirmedCount = mappingRepository.countByGroup_GroupIdAndStatus(group.getGroupId(), "CONFIRMED");
                long pendingCount = mappingRepository.countByGroup_GroupIdAndStatus(group.getGroupId(), "PENDING");

                if (confirmedCount == 0 && pendingCount == 0) {
                    if ("ACTIVE".equals(group.getStatus())) {
                        group.setStatus("DEPRECATED");
                        groupRepository.save(group);
                        auditService.logEvent(actor, "GROUP_DEPRECATED", "MATERIAL_GROUP", group.getGroupId(), "status: ACTIVE", "status: DEPRECATED (no active mappings)");
                    } else if ("PROPOSED".equals(group.getStatus())) {
                        group.setStatus("REJECTED");
                        groupRepository.save(group);
                        auditService.logEvent(actor, "GROUP_REJECTED", "MATERIAL_GROUP", group.getGroupId(), "status: PROPOSED", "status: REJECTED");
                    }
                }
            }
        }

        return savedMapping;
    }

    /**
     * Edit mapping and group attributes before approval (BUG-12).
     */
    @Transactional
    public MaterialMapping editMapping(Long mappingId, User reviewer, String standardizedDesc, String standardizedSpec, String standardizedUom, String notes) {
        MaterialMapping mapping = mappingRepository.findById(mappingId)
                .orElseThrow(() -> new IllegalArgumentException("Mapping not found: " + mappingId));

        checkAssignment(mapping, reviewer);
        if (!"PENDING".equals(mapping.getStatus())) {
            throw new IllegalStateException("Only pending mappings can be edited");
        }
        MaterialGroup group = mapping.getGroup();
        if (group != null && !"PROPOSED".equals(group.getStatus())) {
            throw new IllegalStateException("Published groups cannot be edited through a mapping");
        }
        if (group != null) {
            String oldDesc = group.getStandardizedDescription();
            if (standardizedDesc != null && !standardizedDesc.isBlank()) {
                group.setStandardizedDescription(standardizedDesc.trim().toUpperCase());
            }
            if (standardizedSpec != null) {
                group.setStandardizedSpecification(standardizedSpec.trim());
            }
            if (standardizedUom != null) {
                group.setStandardizedUom(standardizedUom.trim().toUpperCase());
            }
            groupRepository.save(group);

            auditService.logEvent(
                    reviewer,
                    "MAPPING_EDITED",
                    "MATERIAL_GROUP",
                    group.getGroupId(),
                    "desc: " + oldDesc,
                    "desc: " + group.getStandardizedDescription() + (notes != null ? ", notes: " + notes : "")
            );
        }

        return mapping;
    }

    /**
     * Bulk approve HIGH confidence mappings in assigned commodity class (Innovation #2 / D4).
     * Server-side guard: Additionally requires zero identity-critical conflicts in explanation.
     */
    @Transactional
    public BulkApprovalResult bulkApproveHighConfidence(User reviewer, Long categoryId) {
        if ("REVIEWER".equals(reviewer.getRole()) && categoryId != null && !reviewerAssignmentRepository.findCategoryIdsByUserId(reviewer.getUserId()).contains(categoryId)) {
            throw new AccessDeniedException("Category is not assigned to you");
        }
        List<MaterialMapping> candidates;
        if (categoryId != null) {
            candidates = mappingRepository.findHighConfidencePendingForCategories(List.of(categoryId));
        } else {
            List<Long> assignedCats = reviewerAssignmentRepository.findCategoryIdsByUserId(reviewer.getUserId());
            if (assignedCats.isEmpty()) {
                candidates = "REVIEWER".equals(reviewer.getRole()) ? List.of() : mappingRepository.findHighConfidencePendingAll();
            } else {
                candidates = mappingRepository.findHighConfidencePendingForCategories(assignedCats);
            }
        }

        int count = 0;
        List<BulkApprovalFailure> skipped = new ArrayList<>();
        for (MaterialMapping mm : candidates) {
            // Server-side check: Skip if explanation has identity-critical conflicts (D4)
            if (hasIdentityCriticalConflicts(mm.getExplanationJson())) {
                skipped.add(new BulkApprovalFailure(mm.getMappingId(), "Identity-critical conflict requires individual review"));
                continue;
            }
            try {
                decideMapping(mm.getMappingId(), reviewer, "CONFIRMED", "Automated batch sign-off on High Confidence candidate");
                count++;
            } catch (Exception e) {
                skipped.add(new BulkApprovalFailure(mm.getMappingId(), e.getMessage() == null ? "Approval failed" : e.getMessage()));
            }
        }

        if (count > 0) {
            auditService.logEvent(
                    reviewer,
                    "BULK_APPROVAL",
                    "MATERIAL_MAPPING",
                    0L,
                    "pending_high_confidence",
                    "Approved " + count + " HIGH-confidence mappings in category " + (categoryId != null ? categoryId : "assigned")
            );
        }

        return new BulkApprovalResult(candidates.size(), count, skipped.size(), skipped);
    }

    public record BulkApprovalFailure(Long mappingId, String reason) {}
    public record BulkApprovalResult(int eligibleCount, int approvedCount, int skippedCount,
                                     List<BulkApprovalFailure> skipped) {}

    /** Single command for the eligible high-confidence fast-track path. */
    @Transactional
    public String approveAndPublish(Long mappingId, User actor) {
        MaterialMapping mapping = mappingRepository.findById(mappingId)
                .orElseThrow(() -> new IllegalArgumentException("Mapping not found: " + mappingId));
        if (mapping.getConfidenceScore() == null || mapping.getConfidenceScore().doubleValue() < 0.90
                || !"HIGH".equalsIgnoreCase(mapping.getConfidenceTier())) {
            throw new IllegalStateException("Fast-track requires a HIGH confidence score of at least 90%");
        }
        if (hasIdentityCriticalConflicts(mapping.getExplanationJson())) {
            throw new IllegalStateException("Fast-track is unavailable because identity-critical conflicts require review");
        }
        if ("PENDING".equalsIgnoreCase(mapping.getStatus())) {
            if (!"ADMIN".equalsIgnoreCase(actor.getRole())) {
                throw new AccessDeniedException("Four-eyes policy: a senior reviewer cannot approve and publish the same mapping. Ask another reviewer to confirm it first.");
            }
            mapping = decideMapping(mappingId, actor, "CONFIRMED", "Administrative high-confidence fast-track approval");
        } else if (!"CONFIRMED".equalsIgnoreCase(mapping.getStatus())) {
            throw new IllegalStateException("Only PENDING or CONFIRMED mappings can be fast-tracked");
        }
        return mintGroup(mapping.getGroup().getGroupId(), actor);
    }

    private boolean hasIdentityCriticalConflicts(String explanationJson) {
        if (explanationJson == null || explanationJson.isBlank()) return false;
        try {
            JsonNode root = objectMapper.readTree(explanationJson);
            JsonNode conflicts = root.get("conflicts");
            if (conflicts != null && conflicts.isArray()) {
                for (JsonNode c : conflicts) {
                    String text = c.asText("");
                    if (text.toLowerCase().contains("identity_critical")) {
                        return true;
                    }
                }
            }
        } catch (Exception ignored) {}
        return false;
    }

    public void checkAssignment(MaterialMapping mapping, User user) {
        if ("REVIEWER".equals(user.getRole()) && (mapping.getGroup() == null || mapping.getGroup().getCategory() == null ||
                !reviewerAssignmentRepository.findCategoryIdsByUserId(user.getUserId()).contains(mapping.getGroup().getCategory().getCategoryId()))) {
            throw new AccessDeniedException("Mapping is outside your assigned categories");
        }
    }

    /**
     * WP4 Task 3 / D3: Publication & National Code Minting (Step 2 of Governance Loop).
     * Rule: The publisher (SENIOR_REVIEWER) must NOT be the user who confirmed any mapping in this group.
     */
    @Transactional
    public String mintGroup(Long id, User approver) {
        if (!"SENIOR_REVIEWER".equalsIgnoreCase(approver.getRole()) && !"ADMIN".equalsIgnoreCase(approver.getRole())) {
            throw new AccessDeniedException("Senior reviewer approval required to mint national material codes");
        }

        MaterialGroup group = groupRepository.findLockedById(id)
                .orElseThrow(() -> new IllegalArgumentException("Material group not found: " + id));

        if (!"PROPOSED".equals(group.getStatus())) {
            throw new IllegalStateException("Only PROPOSED groups can be minted (current status: " + group.getStatus() + ")");
        }

        List<MaterialMapping> confirmed = mappingRepository.findByGroup_GroupId(id).stream()
                .filter(m -> "CONFIRMED".equals(m.getStatus()))
                .toList();

        if (confirmed.isEmpty()) {
            throw new IllegalStateException("At least one CONFIRMED mapping is required before publishing a group");
        }

        // Four-eyes rule: The publisher cannot be the same user who confirmed the underlying mapping (unless ADMIN override)
        boolean isSelfReview = confirmed.stream().anyMatch(
                m -> m.getReviewedBy() != null && Objects.equals(m.getReviewedBy().getUserId(), approver.getUserId()));
        if (isSelfReview && !"ADMIN".equalsIgnoreCase(approver.getRole())) {
            throw new AccessDeniedException("Four-eyes policy violation: A second authorized reviewer must publish the national code.");
        }

        // Mint authoritative Semi-Significant National Material Code with ISO 7064 MOD 37,36 checksum
        Map<String, Object> attrs = new HashMap<>();
        if (group.getSignatureAttributes() != null) {
            try {
                attrs = objectMapper.readValue(group.getSignatureAttributes(), new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
            } catch (Exception ignored) {}
        }
        if (group.getCodeSerial() == null) {
            String parsed = codeGenerator.extractSerialFromProvisional(group.getProvisionalRef());
            group.setCodeSerial(parsed != null ? Long.parseLong(parsed) : codeGenerator.allocateSerial(group.getCategory()));
            group.setProvisionalRef(codeGenerator.generateProvisionalRef(group.getCategory(), group.getCodeSerial()));
        }
        String code = codeGenerator.mintNationalCode(group.getCategory(), attrs, group.getProvisionalRef());
        group.setCommonMaterialCode(code);
        group.setStatus("ACTIVE");
        groupRepository.save(group);

        auditService.logEvent(approver, "CODE_MINTED", "MATERIAL_GROUP", id, "PROPOSED", code);
        return code;
    }

    /**
     * WP4 Task 4: Retrieve groups in PROPOSED status with >= 1 CONFIRMED mapping for publication.
     */
    public List<MaterialGroup> getPublishableGroups() {
        return groupRepository.findPublishableGroups();
    }

    /**
     * Administrative override (B-21). Sets old mapping to SUPERSEDED and applies new decision without COI check.
     */
    @Transactional
    public MaterialMapping supersedeMapping(Long mappingId, User admin, String newDecision, String mandatoryReason) {
        if (!"ADMIN".equalsIgnoreCase(admin.getRole()) && !"SENIOR_REVIEWER".equalsIgnoreCase(admin.getRole())) {
            throw new AccessDeniedException("Only ADMIN or SENIOR_REVIEWER can supersede a decided mapping.");
        }
        if (mandatoryReason == null || mandatoryReason.trim().length() < 5) {
            throw new IllegalArgumentException("A mandatory justification note (at least 5 characters) is required to supersede a decided mapping.");
        }

        MaterialMapping mapping = mappingRepository.findById(mappingId)
                .orElseThrow(() -> new IllegalArgumentException("Mapping not found: " + mappingId));

        String oldStatus = mapping.getStatus();
        if (!Set.of("CONFIRMED", "REJECTED").contains(oldStatus.toUpperCase())) {
            throw new IllegalStateException("Only a decided mapping can be superseded");
        }

        String targetStatus = ("CONFIRMED".equalsIgnoreCase(newDecision) || "APPROVE".equalsIgnoreCase(newDecision))
                ? "CONFIRMED" : "REJECTED";

        mapping.setStatus("SUPERSEDED");
        mapping.setDecisionNotes((mapping.getDecisionNotes() == null ? "" : mapping.getDecisionNotes() + " | ") +
                "Superseded: " + mandatoryReason);
        mappingRepository.saveAndFlush(mapping);

        MaterialMapping replacement = new MaterialMapping();
        replacement.setMaterial(mapping.getMaterial());
        replacement.setGroup(mapping.getGroup());
        replacement.setConfidenceScore(mapping.getConfidenceScore());
        replacement.setConfidenceTier(mapping.getConfidenceTier());
        replacement.setExplanationJson(mapping.getExplanationJson());
        replacement.setMatchBasis(mapping.getMatchBasis());
        replacement.setStatus(targetStatus);
        replacement.setReviewedBy(admin);
        replacement.setReviewedAt(LocalDateTime.now());
        replacement.setDecisionNotes("OVERRIDE: " + mandatoryReason);
        replacement.setSupersedesMapping(mapping);
        replacement = mappingRepository.saveAndFlush(replacement);
        mapping.setSupersededByMapping(replacement);
        mappingRepository.save(mapping);

        auditService.logEvent(admin, "MAPPING_SUPERSEDED", "MATERIAL_MAPPING", mapping.getMappingId(),
                "status: " + oldStatus,
                "status: SUPERSEDED, replacementMappingId: " + replacement.getMappingId() + ", justification: " + mandatoryReason);
        return replacement;
    }
}
