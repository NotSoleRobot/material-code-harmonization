package com.sih.materialmaster.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sih.materialmaster.dto.*;
import com.sih.materialmaster.entity.*;
import com.sih.materialmaster.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

/**
 * HarmonizationService implements the 4-step decision order (D1) specified in NUMM:
 * 1. Deterministic Attribute Signature Match (only when signature is complete)
 * 2. Policy-routed AI match (default auto-confirm >= 0.85 with a >= 0.10 candidate margin)
 * 3. Group Relation Classification (FUNCTIONALLY_EQUIVALENT / VARIANT)
 * 4. Novel Specification Registration (complete identities receive a National Material Code;
 *    incomplete identities are routed to review)
 */
@Service
public class HarmonizationService {

    private static final Logger log = LoggerFactory.getLogger(HarmonizationService.class);

    private final MaterialRepository materialRepository;
    private final MaterialGroupRepository groupRepository;
    private final MaterialMappingRepository mappingRepository;
    private final GroupRelationRepository groupRelationRepository;
    private final MatchingPolicyRepository matchingPolicyRepository;
    private final MatchCandidateRepository matchCandidateRepository;
    private final MatchingClient matchingClient;
    private final MaterialIdentityService identityService;
    private final AuditService auditService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public HarmonizationService(MaterialRepository materialRepository,
                                MaterialGroupRepository groupRepository,
                                MaterialMappingRepository mappingRepository,
                                GroupRelationRepository groupRelationRepository,
                                MatchingPolicyRepository matchingPolicyRepository,
                                MatchCandidateRepository matchCandidateRepository,
                                MatchingClient matchingClient,
                                MaterialIdentityService identityService,
                                AuditService auditService) {
        this.materialRepository = materialRepository;
        this.groupRepository = groupRepository;
        this.mappingRepository = mappingRepository;
        this.groupRelationRepository = groupRelationRepository;
        this.matchingPolicyRepository = matchingPolicyRepository;
        this.matchCandidateRepository = matchCandidateRepository;
        this.matchingClient = matchingClient;
        this.identityService = identityService;
        this.auditService = auditService;
    }

    public CompareResponse compare(MaterialInfoDto a, MaterialInfoDto b) {
        try {
            return matchingClient.compareDetailed(a, b);
        } catch (Exception ex) {
            log.warn("Matching client compareDetailed failed ({}); using heuristic comparison fallback", ex.getMessage());
            return fallbackCompare(a, b);
        }
    }

    /** Extracts and persists attributes in one matching-service request with local fallback. */
    public void extractAttributesForMaterials(List<Long> materialIds) {
        if (materialIds == null || materialIds.isEmpty()) return;

        List<Material> materials = new ArrayList<>();
        materialRepository.findAllById(materialIds).forEach(materials::add);
        if (materials.size() != materialIds.size()) {
            throw new IllegalArgumentException("One or more materials no longer exist");
        }

        List<AttributeExtractionResult> results = null;
        try {
            results = matchingClient.extractAttributes(
                    materials.stream().map(this::toDto).toList());
        } catch (Exception ex) {
            log.warn("Attribute extraction client failed ({}); extracting heuristic fallback attributes", ex.getMessage());
            results = fallbackExtractAttributes(materials);
        }
        Map<Long, AttributeExtractionResult> byMaterialId = new HashMap<>();
        if (results != null) {
            for (AttributeExtractionResult result : results) {
                if (result.getMaterialId() != null) {
                    byMaterialId.put(result.getMaterialId(), result);
                }
            }
        }

        for (Material material : materials) {
            AttributeExtractionResult result = byMaterialId.get(material.getMaterialId());
            if (result == null || result.getExtractedAttributes() == null) continue;

            Map<String, Object> attrs = new HashMap<>(result.getExtractedAttributes());
            material.setExtractedAttributes(attrs);
            material.setAttributesExtractedAt(LocalDateTime.now());
            Object dimension = attrs.getOrDefault("nominal_size_mm", attrs.get("dimension"));
            Object materialType = attrs.getOrDefault(
                    "material", attrs.getOrDefault("body_material", attrs.get("material_type")));
            if (dimension != null) material.setExtractedDimension(dimension.toString());
            if (attrs.get("grade") != null) material.setExtractedGrade(attrs.get("grade").toString());
            if (attrs.get("standard") != null) material.setExtractedStandardCode(attrs.get("standard").toString());
            if (materialType != null) material.setExtractedMaterialType(materialType.toString());
        }
        materialRepository.saveAll(materials);
    }

    /**
     * Core harmonization pipeline for a single material (FR2, FR4, FR6, Innovation #1 & #2, D1).
     */
    public HarmonizationResultDto harmonizeMaterial(Long materialId) {
        return harmonizeMaterial(materialId, null);
    }

    /** Uses a batch-precomputed ML response when invoked by an ingestion job. */
    @Transactional
    public HarmonizationResultDto harmonizeMaterial(Long materialId, FindMatchesResponse precomputedResponse) {
        Material target = materialRepository.findById(materialId)
                .orElseThrow(() -> new IllegalArgumentException("Material not found: " + materialId));

        String categoryName = target.getCategory() != null ? target.getCategory().getName() : "GENERAL";

        // 0. Attribute Extraction if not yet extracted
        Map<String, Object> targetAttrs = parseExtractedAttributes(target);
        if (targetAttrs.isEmpty()) {
            try {
                MaterialInfoDto info = toDto(target);
                List<AttributeExtractionResult> extractionResults = matchingClient.extractAttributes(List.of(info));
                if (extractionResults != null && !extractionResults.isEmpty()) {
                    AttributeExtractionResult aer = extractionResults.get(0);
                    if (aer.getExtractedAttributes() != null && !aer.getExtractedAttributes().isEmpty()) {
                        targetAttrs = aer.getExtractedAttributes();
                        target.setExtractedAttributes(new HashMap<>(targetAttrs));
                        target.setAttributesExtractedAt(LocalDateTime.now());
                        materialRepository.save(target);
                    }
                }
            } catch (Exception e) {
                log.warn("Attribute extraction client failed for material {}; using heuristic fallback: {}", materialId, e.getMessage());
                targetAttrs = extractHeuristicAttributes(target.getDescription(), target.getSpecification());
                target.setExtractedAttributes(new HashMap<>(targetAttrs));
                target.setAttributesExtractedAt(LocalDateTime.now());
                materialRepository.save(target);
            }
        }

        // Fetch category schema to know identity-critical keys
        CategorySchemaDto schema;
        try {
            schema = matchingClient.getCategorySchema(categoryName);
        } catch (Exception ex) {
            log.warn("Category schema lookup failed for {}; using fallback schema: {}", categoryName, ex.getMessage());
            schema = fallbackCategorySchema(categoryName);
        }
        List<String> identityKeys = new ArrayList<>();
        if (schema != null) {
            identityKeys.addAll(schema.getIdentityCriticalAttributes());
            if (schema.getVariantCritical() != null) identityKeys.addAll(schema.getVariantCritical());
        }
        if (identityKeys.isEmpty()) identityKeys.add("material");

        // Compute Attribute Signature
        MaterialIdentityService.SignatureResult sigResult = identityService.computeAttributeSignature(categoryName, identityKeys, targetAttrs);

        HarmonizationResultDto result = new HarmonizationResultDto();
        result.setMaterialId(target.getMaterialId());
        result.setMaterialDescription(target.getDescription());
        result.setCpseName(target.getCpse() != null ? target.getCpse().getName() : "Unknown");
        result.setCategory(categoryName);

        // =========================================================================
        // Step 1 (D1): Deterministic Signature Match
        // Only if signature is complete (all identity keys present & valid)
        // =========================================================================
        if (sigResult.complete()) {
            Optional<MaterialGroup> sigGroup = groupRepository.findByAttributeSignature(sigResult.signature());
            if (sigGroup.isPresent()) {
                MaterialGroup group = sigGroup.get();
                boolean approvedNationalRecord = "ACTIVE".equals(group.getStatus())
                        && group.getCommonMaterialCode() != null;
                String explanation = "{\"checks\":[\"Exact deterministic attribute signature match\"],\"warnings\":[],\"conflicts\":[]}";
                RoutingEvidence routing = new RoutingEvidence(approvedNationalRecord ? "AUTO_CONFIRM" : "REVIEW_REQUIRED", "deterministic-signature-1.0",
                        0.0, 1.0, Map.of("deterministicSignature", 1.0), List.of());
                MaterialMapping mapping = createOrUpdateMapping(target, group, 1.0, "HIGH", explanation,
                        "DETERMINISTIC_SIGNATURE", routing);

                result.setStatus(approvedNationalRecord ? "DETERMINISTIC_MATCH" : "REVIEW_REQUIRED");
                result.setGroupId(group.getGroupId());
                result.setMappingId(mapping.getMappingId());
                result.setProposedGroupCode(approvedNationalRecord ? group.getCommonMaterialCode() : group.getProvisionalRef());
                result.setConfidenceScore(1.0);
                result.setConfidenceTier("HIGH");
                result.setRoutingDecision(approvedNationalRecord ? "AUTO_CONFIRM" : "REVIEW_REQUIRED");
                result.setMatches(Collections.emptyList());

                auditService.logEvent(null, "HARMONIZATION_RUN", "MATERIAL", target.getMaterialId(), null,
                        "Deterministic signature match to group " + result.getProposedGroupCode());
                return result;
            }
        }

        // =========================================================================
        // Candidate Retrieval & AI Matching (Stage 1 Blocking & Stage 2 Matching)
        // =========================================================================
        List<Material> candidateEntities = findCandidateEntities(target);

        if (candidateEntities.isEmpty()) {
            // No comparison target exists. A complete novel identity can be catalogued directly;
            // review is only needed when the identity data is incomplete.
            MaterialGroup group = createOrFindGroupForMaterial(target, targetAttrs, sigResult);
            boolean autoRegister = isAutoRegisterableNovel(target, sigResult);
            RoutingEvidence routing = autoRegister ? RoutingEvidence.autoRegisteredNovel()
                    : RoutingEvidence.incompleteNovelReview();
            if (autoRegister) activateNationalRecord(group);
            String explanation = autoRegister
                    ? "{\"checks\":[\"No catalog candidates found\",\"Complete novel identity registered directly\"],\"warnings\":[],\"conflicts\":[]}"
                    : "{\"checks\":[\"No catalog candidates found\"],\"warnings\":[\"Novel identity is incomplete and requires validation\"],\"conflicts\":[]}";
            MaterialMapping mapping = createOrUpdateMapping(target, group, 0.0, "LOW", explanation, "NOVEL",
                    routing);

            result.setStatus(autoRegister ? "NOVEL_AUTO_HARMONIZED" : "REVIEW_REQUIRED");
            result.setGroupId(group.getGroupId());
            result.setMappingId(mapping.getMappingId());
            result.setProposedGroupCode(autoRegister ? group.getCommonMaterialCode() : group.getProvisionalRef());
            result.setConfidenceScore(0.0);
            result.setConfidenceTier("LOW");
            result.setRoutingDecision(routing.decision());
            result.setMatches(Collections.emptyList());

            auditService.logEvent(null, "HARMONIZATION_RUN", "MATERIAL", target.getMaterialId(), null,
                    (autoRegister ? "Novel specification auto-registered: " : "Incomplete novel specification sent to review: ")
                            + result.getProposedGroupCode());
            return result;
        }

        MaterialInfoDto targetDto = toDto(target);
        List<MaterialInfoDto> candidateDtos = candidateEntities.stream()
                .map(this::toDto)
                .toList();

        FindMatchesResponse matchResponse;
        if (precomputedResponse != null) {
            matchResponse = precomputedResponse;
        } else {
            try {
                matchResponse = matchingClient.findMatches(targetDto, candidateDtos, 5);
            } catch (Exception ex) {
                log.warn("Matching client findMatches failed for material {}; using fallback: {}", target.getMaterialId(), ex.getMessage());
                matchResponse = fallbackFindMatches(targetDto, candidateDtos);
            }
        }
        List<MatchCandidateResultDto> matches = matchResponse != null ? matchResponse.getMatches() : Collections.emptyList();
        result.setMatches(matches != null ? matches : Collections.emptyList());
        persistCandidateEvidence(target, candidateEntities, matches);

        if (matches == null || matches.isEmpty()) {
            MaterialGroup group = createOrFindGroupForMaterial(target, targetAttrs, sigResult);
            boolean autoRegister = isAutoRegisterableNovel(target, sigResult);
            RoutingEvidence routing = autoRegister ? RoutingEvidence.autoRegisteredNovel()
                    : RoutingEvidence.incompleteNovelReview();
            if (autoRegister) activateNationalRecord(group);
            String explanation = autoRegister
                    ? "{\"checks\":[\"No close AI matches found\",\"Complete novel identity registered directly\"],\"warnings\":[],\"conflicts\":[]}"
                    : "{\"checks\":[\"No close AI matches found\"],\"warnings\":[\"Novel identity is incomplete and requires validation\"],\"conflicts\":[]}";
            MaterialMapping mapping = createOrUpdateMapping(target, group, 0.0, "LOW", explanation,
                    "NOVEL_SPECIFICATION", routing);

            result.setStatus(autoRegister ? "NOVEL_AUTO_HARMONIZED" : "REVIEW_REQUIRED");
            result.setGroupId(group.getGroupId());
            result.setMappingId(mapping.getMappingId());
            result.setProposedGroupCode(autoRegister ? group.getCommonMaterialCode() : group.getProvisionalRef());
            result.setConfidenceScore(0.0);
            result.setConfidenceTier("LOW");
            result.setRoutingDecision(routing.decision());
            return result;
        }

        MatchCandidateResultDto topMatch = matches.get(0);
        String rel = topMatch.getPredictedRelationship() != null ? topMatch.getPredictedRelationship() : "NOT_A_MATCH";
        double score = topMatch.getMatchProbability() > 0 ? topMatch.getMatchProbability() : topMatch.getConfidence();
        String tier = topMatch.getConfidenceTier() != null ? topMatch.getConfidenceTier() : (score >= 0.85 ? "HIGH" : (score >= 0.60 ? "MEDIUM" : "LOW"));
        double secondBestScore = matches.size() > 1 ? scoreOf(matches.get(1)) : 0.0;
        double margin = Math.max(0.0, score - secondBestScore);
        List<String> criticalConflicts = criticalConflicts(topMatch);

        result.setConfidenceScore(score);
        result.setConfidenceTier(tier);
        result.setExplanation(topMatch.getExplanation());

        String explanationJson = "";
        try {
            explanationJson = objectMapper.writeValueAsString(topMatch.getExplanation());
        } catch (Exception e) {
            explanationJson = "{\"checks\":[],\"warnings\":[\"Explanation serialization skipped\"],\"conflicts\":[]}";
        }

        // Resolve matched candidate entity by ID
        Long matchedMaterialId = null;
        if (topMatch.getCandidate() != null && topMatch.getCandidate().get("material_id") != null) {
            Object idObj = topMatch.getCandidate().get("material_id");
            if (idObj instanceof Number num) {
                matchedMaterialId = num.longValue();
            }
        }

        Material matchedEntity = null;
        if (matchedMaterialId != null) {
            final Long finalMatchedId = matchedMaterialId;
            matchedEntity = candidateEntities.stream()
                    .filter(c -> c.getMaterialId().equals(finalMatchedId))
                    .findFirst()
                    .orElse(null);
        }

        // =========================================================================
        // Step 2 (D1): Strong AI Similarity Match (EXACT_DUPLICATE / NEAR_DUPLICATE)
        // =========================================================================
        boolean isDuplicateMerge = ("EXACT_DUPLICATE".equalsIgnoreCase(rel) || "NEAR_DUPLICATE".equalsIgnoreCase(rel)) && score >= 0.60;
        boolean isEquivalentOrVariant = ("FUNCTIONALLY_EQUIVALENT".equalsIgnoreCase(rel) || "VARIANT".equalsIgnoreCase(rel)) && score >= 0.60;
        MaterialGroup targetGroup = null;
        String matchBasis = "NOVEL";
        boolean approvedCandidate = false;

        if (isDuplicateMerge && matchedEntity != null) {
            Optional<MaterialMapping> candMapping = mappingRepository.findActiveByMaterialId(matchedEntity.getMaterialId());
            if (candMapping.isPresent() && candMapping.get().getGroup() != null) {
                targetGroup = candMapping.get().getGroup();
                matchBasis = "ML_PROPOSED";
                approvedCandidate = "CONFIRMED".equals(candMapping.get().getStatus())
                        && "ACTIVE".equals(targetGroup.getStatus())
                        && targetGroup.getCommonMaterialCode() != null;
            }
        }

        RoutingEvidence routing = route(target, topMatch, rel, score, secondBestScore, margin,
                criticalConflicts, isDuplicateMerge, approvedCandidate,
                isAutoRegisterableNovel(target, sigResult));

        if (targetGroup == null) {
            targetGroup = createOrFindGroupForMaterial(target, targetAttrs, sigResult);
            matchBasis = isDuplicateMerge ? "ML_PROPOSED" : "NOVEL";

            if (isDuplicateMerge && matchedEntity != null) {
                Optional<MaterialMapping> candActive = mappingRepository.findActiveByMaterialId(matchedEntity.getMaterialId());
                if (candActive.isEmpty()) {
                    createOrUpdateMapping(matchedEntity, targetGroup, score, tier, explanationJson, matchBasis, routing);
                }
            }
        }

        if ("AUTO_CONFIRM".equals(routing.decision()) && "NOVEL".equals(matchBasis)) {
            activateNationalRecord(targetGroup);
        }

        // Create target material mapping
        MaterialMapping targetMapping = createOrUpdateMapping(target, targetGroup, score, tier, explanationJson,
                matchBasis, routing);

        // =========================================================================
        // Step 3 (D1): Inter-Group Relations for Equivalent and Variant items
        // =========================================================================
        if (isEquivalentOrVariant && matchedEntity != null) {
            Optional<MaterialMapping> candMapping = mappingRepository.findActiveByMaterialId(matchedEntity.getMaterialId());
            if (candMapping.isPresent() && candMapping.get().getGroup() != null) {
                MaterialGroup otherGroup = candMapping.get().getGroup();
                if (!targetGroup.getGroupId().equals(otherGroup.getGroupId())) {
                    createGroupRelation(targetGroup, otherGroup, rel.toUpperCase(), score);
                }
            }
        }

        result.setMappingId(targetMapping.getMappingId());
        result.setGroupId(targetGroup.getGroupId());
        result.setProposedGroupCode(targetGroup.getCommonMaterialCode() != null ? targetGroup.getCommonMaterialCode() : targetGroup.getProvisionalRef());
        result.setRoutingDecision(routing.decision());
        result.setStatus("AUTO_CONFIRM".equals(routing.decision())
                ? ("NOVEL".equals(matchBasis) ? "NOVEL_AUTO_HARMONIZED" : "AUTO_HARMONIZED")
                : "REVIEW_REQUIRED");

        // Audit harmonization run (FR9)
        auditService.logEvent(
                null,
                "HARMONIZATION_RUN",
                "MATERIAL",
                target.getMaterialId(),
                null,
                "Routed " + routing.decision() + " to group " + result.getProposedGroupCode()
                        + " (" + rel + ", score: " + score + ", margin: " + margin + ", basis: " + matchBasis + ")"
        );

        return result;
    }

    /**
     * Builds deterministic candidate sets and scores them in one Flask request.
     * Results are keyed by material id and consumed by HarmonizationJobService.
     */
    @Transactional(readOnly = true)
    public Map<Long, FindMatchesResponse> prepareBatchMatches(List<Long> materialIds) {
        List<FindMatchesBatchQuery> queries = new ArrayList<>();
        List<Long> queryIds = new ArrayList<>();
        for (Long materialId : materialIds) {
            Material target = materialRepository.findById(materialId)
                    .orElseThrow(() -> new IllegalArgumentException("Material not found: " + materialId));
            List<MaterialInfoDto> candidates = findCandidateEntities(target).stream().map(this::toDto).toList();
            queries.add(new FindMatchesBatchQuery(toDto(target), candidates, 5));
            queryIds.add(materialId);
        }
        List<FindMatchesResponse> responses = new ArrayList<>(queries.size());
        try {
            responses = matchingClient.findMatchesBatch(queries);
        } catch (RuntimeException batchFailure) {
            log.warn("Batch matching failed for {} materials; falling back to individual requests: {}",
                    queries.size(), batchFailure.getMessage());
            for (FindMatchesBatchQuery query : queries) {
                try {
                    responses.add(matchingClient.findMatches(
                            query.material(), query.candidates(), query.topK()));
                } catch (Exception indFailure) {
                    log.warn("Individual match failed for material {}; using heuristic fallback: {}",
                            query.material().getMaterialId(), indFailure.getMessage());
                    responses.add(fallbackFindMatches(query.material(), query.candidates()));
                }
            }
        }
        Map<Long, FindMatchesResponse> byMaterial = new LinkedHashMap<>();
        for (int i = 0; i < queryIds.size(); i++) {
            byMaterial.put(queryIds.get(i), responses.get(i));
        }
        return byMaterial;
    }

    private List<Material> findCandidateEntities(Material target) {
        List<Material> candidates;
        if (target.getCategory() != null) {
            candidates = materialRepository.findRelevantCandidates(
                    target.getCategory().getCategoryId(), target.getMaterialId(), target.getDescription(),
                    target.getExtractedDimension(), target.getExtractedMaterialType());
            if (candidates.isEmpty()) {
                candidates = materialRepository.findCandidatesByCategory(
                        target.getCategory().getCategoryId(), target.getMaterialId(), PageRequest.of(0, 50));
            }
        } else {
            candidates = materialRepository.findCandidatesAll(target.getMaterialId(), PageRequest.of(0, 50));
        }
        return candidates;
    }

    private MaterialGroup createOrFindGroupForMaterial(Material material, Map<String, Object> attrs, MaterialIdentityService.SignatureResult sigResult) {
        if (sigResult != null && sigResult.complete()) {
            Optional<MaterialGroup> existingGroup = groupRepository.findByAttributeSignature(sigResult.signature());
            if (existingGroup.isPresent()) {
                return existingGroup.get();
            }
        }

        MaterialGroup newGroup = new MaterialGroup();
        if (sigResult != null) {
            newGroup.setAttributeSignature(sigResult.signature());
            newGroup.setSignatureComplete(sigResult.complete());
        }
        try {
            newGroup.setSignatureAttributes(objectMapper.writeValueAsString(attrs));
        } catch (Exception ignored) {
            newGroup.setSignatureAttributes("{}");
        }
        newGroup.setSignatureVersion(1);
        newGroup.setStandardizedDescription(generateStandardizedDescription(material.getCategory(), attrs, material.getDescription()));
        newGroup.setStandardizedSpecification(material.getSpecification());
        newGroup.setStandardizedUom(material.getUnitOfMeasure() != null ? material.getUnitOfMeasure() : "NOS");
        newGroup.setCategory(material.getCategory());
        newGroup.setStatus("PROPOSED");

        newGroup.setCodeSerial(null);
        newGroup.setProvisionalRef(identityService.generateDraftReference());

        try {
            return groupRepository.save(newGroup);
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            if (sigResult != null && sigResult.signature() != null) {
                return groupRepository.findByAttributeSignature(sigResult.signature())
                        .orElseThrow(() -> e);
            }
            throw e;
        }
    }

    private MaterialMapping createOrUpdateMapping(Material material, MaterialGroup group, double score, String tier,
                                                   String explanationJson, String matchBasis,
                                                   RoutingEvidence routing) {
        Optional<MaterialMapping> activeMapping = mappingRepository.findActiveByMaterialId(material.getMaterialId());
        MaterialMapping mapping;
        boolean sameAutomaticDecision = activeMapping.isPresent()
                && "CONFIRMED".equals(activeMapping.get().getStatus())
                && "AUTO_CONFIRM".equals(routing.decision())
                && "AUTO".equals(activeMapping.get().getDecisionSource())
                && activeMapping.get().getGroup() != null
                && Objects.equals(activeMapping.get().getGroup().getGroupId(), group.getGroupId());
        if (activeMapping.isPresent() && Set.of("CONFIRMED", "REJECTED").contains(activeMapping.get().getStatus())
                && !sameAutomaticDecision) {
            MaterialMapping decided = activeMapping.get();
            String oldStatus = decided.getStatus();
            decided.setStatus("SUPERSEDED");
            mappingRepository.saveAndFlush(decided);
            auditService.logEvent(null, "MAPPING_SUPERSEDED", "MATERIAL_MAPPING", decided.getMappingId(),
                    "status: " + oldStatus, "status: SUPERSEDED; re-harmonization requested");
            mapping = new MaterialMapping();
        } else {
            mapping = activeMapping.orElseGet(MaterialMapping::new);
        }

        mapping.setMaterial(material);
        mapping.setGroup(group);
        mapping.setConfidenceScore(BigDecimal.valueOf(score).setScale(4, RoundingMode.HALF_UP));
        mapping.setConfidenceTier(tier);
        boolean autoConfirm = "AUTO_CONFIRM".equals(routing.decision());
        mapping.setStatus(autoConfirm ? "CONFIRMED" : "PENDING");
        mapping.setExplanationJson(explanationJson);
        mapping.setMatchBasis(matchBasis);
        mapping.setDecisionSource("AUTO");
        mapping.setRoutingDecision(routing.decision());
        mapping.setModelVersion(routing.modelVersion());
        mapping.setSecondBestScore(decimal(routing.secondBestScore()));
        mapping.setCandidateMargin(decimal(routing.margin()));
        mapping.setScoreBreakdown(writeJson(routing.scoreBreakdown(), "{}"));
        mapping.setCriticalConflicts(writeJson(routing.criticalConflicts(), "[]"));
        mapping.setAutomaticallyDecidedAt(autoConfirm ? LocalDateTime.now() : null);

        MaterialMapping saved = mappingRepository.save(mapping);
        if (autoConfirm && !sameAutomaticDecision) {
            auditService.logEvent(null, "MAPPING_AUTO_CONFIRMED", "MATERIAL_MAPPING", saved.getMappingId(),
                    null, "Policy-confirmed mapping at score " + score + " with margin " + routing.margin());
        }
        return saved;
    }

    /**
     * A novel material is safe to register without comparison only when its category-specific
     * identity signature is complete and its basic catalog fields are present.
     */
    private boolean isAutoRegisterableNovel(Material material,
                                            MaterialIdentityService.SignatureResult signature) {
        if (material == null || signature == null || !signature.complete()) return false;
        if (material.getCategory() == null || isBlank(material.getDescription())
                || isBlank(material.getSpecification()) || isBlank(material.getUnitOfMeasure())) {
            return false;
        }
        String category = material.getCategory().getName();
        return category != null
                && !"GENERAL".equalsIgnoreCase(category)
                && !"GENERAL_MRO".equalsIgnoreCase(category);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private synchronized void activateNationalRecord(MaterialGroup group) {
        if (group == null || "ACTIVE".equals(group.getStatus())) return;
        long serial = identityService.allocateReferenceSerial(group.getCategory());
        String nationalCode = identityService.generateCatalogReference(group.getCategory(), serial);
        group.setCodeSerial(serial);
        group.setCommonMaterialCode(nationalCode);
        group.setStatus("ACTIVE");
        groupRepository.saveAndFlush(group);
        auditService.logEvent(null, "NATIONAL_CODE_ASSIGNED", "MATERIAL_GROUP", group.getGroupId(),
                "status: PROPOSED", "status: ACTIVE, nationalMaterialCode: " + nationalCode
                        + ", assignment: automatic novel registration");
    }

    private RoutingEvidence route(Material target, MatchCandidateResultDto topMatch, String relationship,
                                  double score, double secondBestScore, double margin,
                                  List<String> criticalConflicts, boolean duplicateMerge,
                                  boolean approvedCandidate, boolean autoRegisterableNovel) {
        MatchingPolicy policy = target.getCategory() == null ? null
                : matchingPolicyRepository.findByCategory_CategoryId(target.getCategory().getCategoryId()).orElse(null);
        double autoThreshold = policy != null ? policy.getAutoConfirmThreshold().doubleValue() : 0.85;
        double reviewThreshold = policy != null ? policy.getReviewThreshold().doubleValue() : 0.60;
        double minimumMargin = policy != null ? policy.getMinimumCandidateMargin().doubleValue() : 0.10;
        boolean autoEnabled = policy == null || policy.isAutoConfirmEnabled();

        String decision;
        if (autoEnabled && approvedCandidate && duplicateMerge && score >= autoThreshold && margin >= minimumMargin
                && criticalConflicts.isEmpty()) {
            decision = "AUTO_CONFIRM";
        } else if (score >= reviewThreshold || duplicateMerge
                || "NEEDS_REVIEW".equalsIgnoreCase(relationship)
                || "FUNCTIONALLY_EQUIVALENT".equalsIgnoreCase(relationship)
                || "VARIANT".equalsIgnoreCase(relationship)) {
            decision = "REVIEW_REQUIRED";
        } else {
            decision = autoRegisterableNovel ? "AUTO_CONFIRM" : "REVIEW_REQUIRED";
        }
        String modelVersion = topMatch.getModelVersion() != null
                ? topMatch.getModelVersion() : "hybrid-rf-1.0";
        Map<String, Double> breakdown = topMatch.getScoreBreakdown() != null
                ? topMatch.getScoreBreakdown() : Map.of("modelProbability", score);
        return new RoutingEvidence(decision, modelVersion, secondBestScore, margin, breakdown, criticalConflicts);
    }

    private void persistCandidateEvidence(Material target, List<Material> candidates,
                                          List<MatchCandidateResultDto> matches) {
        matchCandidateRepository.deleteByMaterial_MaterialId(target.getMaterialId());
        if (matches == null) return;
        int rank = 0;
        for (MatchCandidateResultDto result : matches.stream().limit(5).toList()) {
            rank++;
            Long candidateId = candidateId(result);
            if (candidateId == null) continue;
            Material candidate = candidates.stream()
                    .filter(item -> candidateId.equals(item.getMaterialId())).findFirst().orElse(null);
            if (candidate == null) continue;
            MatchCandidate evidence = new MatchCandidate();
            evidence.setMaterial(target);
            evidence.setCandidateMaterial(candidate);
            evidence.setCandidateRank(rank);
            evidence.setPredictedRelationship(result.getPredictedRelationship());
            evidence.setMatchScore(decimal(scoreOf(result)));
            evidence.setScoreBreakdown(writeJson(result.getScoreBreakdown(), "{}"));
            evidence.setCriticalConflicts(writeJson(criticalConflicts(result), "[]"));
            evidence.setModelVersion(result.getModelVersion() != null ? result.getModelVersion() : "hybrid-rf-1.0");
            mappingRepository.findActiveByMaterialId(candidateId).ifPresent(m -> evidence.setCandidateGroup(m.getGroup()));
            matchCandidateRepository.save(evidence);
        }
    }

    private Long candidateId(MatchCandidateResultDto result) {
        if (result.getCandidate() == null) return null;
        Object value = result.getCandidate().get("material_id");
        if (value instanceof Number number) return number.longValue();
        try { return value == null ? null : Long.valueOf(value.toString()); }
        catch (NumberFormatException ignored) { return null; }
    }

    private double scoreOf(MatchCandidateResultDto result) {
        return result.getMatchProbability() > 0 ? result.getMatchProbability() : result.getConfidence();
    }

    private List<String> criticalConflicts(MatchCandidateResultDto result) {
        if (result.getCriticalConflicts() != null) return result.getCriticalConflicts();
        if (result.getExplanation() == null || result.getExplanation().getConflicts() == null) return List.of();
        return result.getExplanation().getConflicts().stream()
                .filter(value -> value != null && (value.toLowerCase().contains("identity_critical")
                        || value.toLowerCase().contains("variant_critical")))
                .toList();
    }

    private BigDecimal decimal(double value) {
        return BigDecimal.valueOf(Math.max(0.0, Math.min(1.0, value))).setScale(4, RoundingMode.HALF_UP);
    }

    private String writeJson(Object value, String fallback) {
        try { return objectMapper.writeValueAsString(value); }
        catch (Exception ignored) { return fallback; }
    }

    private record RoutingEvidence(String decision, String modelVersion, double secondBestScore,
                                   double margin, Map<String, Double> scoreBreakdown,
                                   List<String> criticalConflicts) {
        static RoutingEvidence autoRegisteredNovel() {
            return new RoutingEvidence("AUTO_CONFIRM", "novel-identity-1.0", 0.0, 1.0,
                    Map.of("completeNovelIdentity", 1.0), List.of());
        }

        static RoutingEvidence incompleteNovelReview() {
            return new RoutingEvidence("REVIEW_REQUIRED", "novel-identity-1.0", 0.0, 0.0,
                    Map.of("modelProbability", 0.0), List.of());
        }
    }

    private void createGroupRelation(MaterialGroup g1, MaterialGroup g2, String relationType, double confidence) {
        if (g1 == null || g2 == null || Objects.equals(g1.getGroupId(), g2.getGroupId())) {
            return; // Reject self-relations
        }
        MaterialGroup first = g1.getGroupId() < g2.getGroupId() ? g1 : g2;
        MaterialGroup second = g1.getGroupId() < g2.getGroupId() ? g2 : g1;

        Optional<GroupRelation> existing = groupRelationRepository.findRelationBetween(first.getGroupId(), second.getGroupId());
        if (existing.isEmpty()) {
            GroupRelation gr = new GroupRelation();
            gr.setGroupA(first);
            gr.setGroupB(second);
            gr.setRelationType(relationType);
            gr.setConfidence(BigDecimal.valueOf(confidence).setScale(4, RoundingMode.HALF_UP));
            GroupRelation saved = groupRelationRepository.save(gr);
            auditService.logEvent(null, "GROUP_RELATION_CREATED", "GROUP_RELATION", saved.getRelationId(), null,
                    relationType + ": " + first.getGroupId() + " <-> " + second.getGroupId());
        }
    }

    private String generateStandardizedDescription(MaterialCategory category, Map<String, Object> attrs, String fallback) {
        String catName = (category != null && category.getName() != null) ? category.getName().toUpperCase() : "GENERAL";
        if (attrs == null || attrs.isEmpty()) {
            return cleanStandardizedDescription(fallback);
        }

        StringBuilder sb = new StringBuilder();
        if (catName.contains("PIPE") || catName.contains("TUBE")) {
            sb.append("PIPE");
            appendAttr(sb, attrs, "material_type", "material");
            appendAttr(sb, attrs, "nominal_size_mm", "dimension", "size");
            appendAttr(sb, attrs, "schedule");
            appendAttr(sb, attrs, "standard_code", "standard");
        } else if (catName.contains("VALVE")) {
            sb.append("VALVE");
            appendAttr(sb, attrs, "valve_type", "type");
            appendAttr(sb, attrs, "material_type", "material");
            appendAttr(sb, attrs, "nominal_size_mm", "dimension", "size");
            appendAttr(sb, attrs, "pressure_class", "rating");
            appendAttr(sb, attrs, "standard_code", "standard");
        } else if (catName.contains("FLANGE")) {
            sb.append("FLANGE");
            appendAttr(sb, attrs, "flange_type", "type");
            appendAttr(sb, attrs, "material_type", "material");
            appendAttr(sb, attrs, "nominal_size_mm", "dimension", "size");
            appendAttr(sb, attrs, "pressure_class", "rating");
        } else if (catName.contains("PUMP")) {
            sb.append("PUMP");
            appendAttr(sb, attrs, "pump_type", "type");
            appendAttr(sb, attrs, "capacity");
            appendAttr(sb, attrs, "head");
            appendAttr(sb, attrs, "material_type", "material");
        } else if (catName.contains("BEARING")) {
            sb.append("BEARING");
            appendAttr(sb, attrs, "bearing_type", "type");
            appendAttr(sb, attrs, "bore_diameter_mm", "dimension");
            appendAttr(sb, attrs, "clearance");
        } else if (catName.contains("FASTENER")) {
            sb.append("FASTENER");
            appendAttr(sb, attrs, "fastener_type", "type");
            appendAttr(sb, attrs, "thread_size", "dimension");
            appendAttr(sb, attrs, "length");
            appendAttr(sb, attrs, "material_type", "material");
        } else if (catName.contains("MOTOR")) {
            sb.append("MOTOR");
            appendAttr(sb, attrs, "power_rating", "power");
            appendAttr(sb, attrs, "voltage");
            appendAttr(sb, attrs, "rpm");
        } else if (catName.contains("CABLE")) {
            sb.append("CABLE");
            appendAttr(sb, attrs, "core_count", "cores");
            appendAttr(sb, attrs, "cross_section_sqmm", "size");
            appendAttr(sb, attrs, "voltage_grade", "voltage");
            appendAttr(sb, attrs, "insulation");
        } else {
            return cleanStandardizedDescription(fallback);
        }

        String result = sb.toString().trim();
        return result.isEmpty() || result.equals(catName) ? cleanStandardizedDescription(fallback) : result;
    }

    private void appendAttr(StringBuilder sb, Map<String, Object> attrs, String... keys) {
        for (String k : keys) {
            Object v = attrs.get(k);
            if (v != null && !v.toString().isBlank()) {
                sb.append(", ").append(v.toString().trim().toUpperCase());
                return;
            }
        }
    }

    private String cleanStandardizedDescription(String desc) {
        if (desc == null) return "STANDARDIZED MATERIAL";
        return desc.toUpperCase().replaceAll("\\s+", " ").trim();
    }

    private Map<String, Object> parseExtractedAttributes(Material m) {
        if (m.getExtractedAttributes() != null && !m.getExtractedAttributes().isEmpty()) {
            return new HashMap<>(m.getExtractedAttributes());
        }
        Map<String, Object> map = new HashMap<>();
        if (m.getExtractedDimension() != null) map.put("dimension", m.getExtractedDimension());
        if (m.getExtractedGrade() != null) map.put("grade", m.getExtractedGrade());
        if (m.getExtractedStandardCode() != null) map.put("standard", m.getExtractedStandardCode());
        if (m.getExtractedMaterialType() != null) map.put("material_type", m.getExtractedMaterialType());
        return map;
    }

    private MaterialInfoDto toDto(Material m) {
        MaterialInfoDto dto = new MaterialInfoDto();
        dto.setMaterialId(m.getMaterialId());
        dto.setDescription(m.getDescription());
        dto.setCategory(m.getCategory() != null ? m.getCategory().getName() : "GENERAL");
        dto.setSpecification(m.getSpecification());
        dto.setCpseMaterialCode(m.getCpseMaterialCode());
        dto.setCpseName(m.getCpse() != null ? m.getCpse().getName() : "Unknown");
        dto.setExtractedAttributes(parseExtractedAttributes(m));
        return dto;
    }

    private static final Pattern DIM_PATTERN = Pattern.compile(
            "\\b(?:(?:DN|NB|OD|ID)\\s*)?(\\d+(?:\\.\\d+)?)\\s*(?:MM|INCH|IN|\"|')\\b|\\bDN\\s*(\\d+(?:\\.\\d+)?)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern MAT_PATTERN = Pattern.compile(
            "\\b(CS|CARBON\\s+STEEL|SS304|SS316|SS|STAINLESS\\s+STEEL|MS|MILD\\s+STEEL|CI|CAST\\s+IRON|GI|PVC|HDPE|BRASS|BRONZE|COPPER|ALUMINIUM|ALUMINUM)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern STD_PATTERN = Pattern.compile(
            "\\b(ASTM\\s*[A-Z]?\\d+|ASME\\s*B?\\d+(?:\\.\\d+)?|IS\\s*\\d+|DIN\\s*\\d+|ISO\\s*\\d+|API\\s*\\d+|IEC\\s*\\d+)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern GRD_PATTERN = Pattern.compile(
            "\\b(?:GRADE|GR\\.?|TYPE|TP)\\s*([A-Z0-9.]+)\\b|\\bGR(?:ADE)?([A-Z0-9.]+)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SCH_PATTERN = Pattern.compile(
            "\\b(?:SCH(?:EDULE)?)\\s*(\\d+[A-Z]?)\\b",
            Pattern.CASE_INSENSITIVE);

    public Map<String, Object> extractHeuristicAttributes(String description, String specification) {
        String text = ((description != null ? description : "") + " " + (specification != null ? specification : "")).trim();
        Map<String, Object> attrs = new LinkedHashMap<>();
        if (text.isBlank()) return attrs;

        Matcher matM = MAT_PATTERN.matcher(text);
        if (matM.find()) {
            String m = matM.group(1).toUpperCase(Locale.ROOT).replaceAll("\\s+", " ");
            if (m.contains("CARBON")) m = "CS";
            else if (m.contains("STAINLESS")) m = "SS";
            else if (m.contains("MILD")) m = "MS";
            else if (m.contains("CAST")) m = "CI";
            attrs.put("material", m);
            attrs.put("material_type", m);
        }

        Matcher dimM = DIM_PATTERN.matcher(text);
        if (dimM.find()) {
            String val = dimM.group(1) != null ? dimM.group(1) : dimM.group(2);
            if (val != null) {
                try {
                    double num = Double.parseDouble(val);
                    attrs.put("nominal_size_mm", num);
                    attrs.put("dimension", num + "MM");
                } catch (Exception ignored) {}
            }
        }

        Matcher schM = SCH_PATTERN.matcher(text);
        if (schM.find()) {
            attrs.put("schedule", "SCH" + schM.group(1).toUpperCase(Locale.ROOT));
        }

        Matcher stdM = STD_PATTERN.matcher(text);
        if (stdM.find()) {
            attrs.put("standard", stdM.group(1).toUpperCase(Locale.ROOT).replaceAll("\\s+", " "));
        }

        Matcher grdM = GRD_PATTERN.matcher(text);
        if (grdM.find()) {
            String g = grdM.group(1) != null ? grdM.group(1) : grdM.group(2);
            if (g != null && !g.isBlank()) {
                attrs.put("grade", g.toUpperCase(Locale.ROOT).trim());
            }
        }
        return attrs;
    }

    public List<AttributeExtractionResult> fallbackExtractAttributes(List<Material> materials) {
        List<AttributeExtractionResult> results = new ArrayList<>(materials.size());
        for (Material m : materials) {
            Map<String, Object> attrs = extractHeuristicAttributes(m.getDescription(), m.getSpecification());
            AttributeExtractionResult r = new AttributeExtractionResult();
            r.setMaterialId(m.getMaterialId());
            r.setCategory(m.getCategory() != null ? m.getCategory().getName() : "GENERAL");
            r.setAttributes(attrs);
            r.setIdentityCriticalPresent(attrs.containsKey("material"));
            r.setMissingIdentityKeys(attrs.containsKey("material") ? List.of() : List.of("material"));
            results.add(r);
        }
        return results;
    }

    public CategorySchemaDto fallbackCategorySchema(String category) {
        String cat = (category != null) ? category.toUpperCase(Locale.ROOT) : "GENERAL";
        return switch (cat) {
            case "PIPE" -> new CategorySchemaDto("PIPE", List.of("material"), List.of("nominal_size_mm", "schedule", "grade"), List.of("material", "nominal_size_mm", "schedule", "grade", "standard"));
            case "VALVE" -> new CategorySchemaDto("VALVE", List.of("valve_type", "body_material"), List.of("nominal_size_mm", "pressure_class"), List.of("valve_type", "body_material", "nominal_size_mm", "pressure_class"));
            case "FLANGE" -> new CategorySchemaDto("FLANGE", List.of("flange_type", "material"), List.of("nominal_size_mm", "pressure_class"), List.of("flange_type", "material", "nominal_size_mm", "pressure_class"));
            case "FITTING" -> new CategorySchemaDto("FITTING", List.of("fitting_type", "material"), List.of("nominal_size_mm", "schedule"), List.of("fitting_type", "material", "nominal_size_mm", "schedule"));
            case "BEARING" -> new CategorySchemaDto("BEARING", List.of("bearing_type", "bearing_number"), List.of("bore_diameter_mm"), List.of("bearing_type", "bearing_number", "bore_diameter_mm"));
            case "GASKET" -> new CategorySchemaDto("GASKET", List.of("gasket_type", "material"), List.of("nominal_size_mm", "pressure_class"), List.of("gasket_type", "material", "nominal_size_mm", "pressure_class"));
            case "FASTENER", "BOLT" -> new CategorySchemaDto(cat, List.of("fastener_type", "material"), List.of("nominal_diameter_mm", "length_mm", "grade"), List.of("fastener_type", "material", "nominal_diameter_mm", "length_mm", "grade"));
            default -> new CategorySchemaDto(cat, List.of("material"), List.of("nominal_size_mm", "standard"), List.of("material", "nominal_size_mm", "standard"));
        };
    }

    public CompareResponse fallbackCompare(MaterialInfoDto a, MaterialInfoDto b) {
        Map<String, Object> attrsA = extractHeuristicAttributes(a.getDescription(), a.getSpecification());
        Map<String, Object> attrsB = extractHeuristicAttributes(b.getDescription(), b.getSpecification());
        return fallbackCompare(a, b, attrsA, attrsB);
    }

    public CompareResponse fallbackCompare(MaterialInfoDto a, MaterialInfoDto b,
                                           Map<String, Object> attrsA, Map<String, Object> attrsB) {
        List<String> checks = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<String> conflicts = new ArrayList<>();

        boolean sameCat = a.getCategory() != null && b.getCategory() != null
                && a.getCategory().equalsIgnoreCase(b.getCategory());
        if (!sameCat) {
            conflicts.add("Category divergence: " + a.getCategory() + " vs " + b.getCategory());
        }

        double lexical = computeTokenSimilarity(a.getDescription(), b.getDescription());
        int compared = 0;
        int agree = 0;

        String matA = (String) attrsA.get("material");
        String matB = (String) attrsB.get("material");
        if (matA != null && matB != null) {
            compared++;
            if (matA.equalsIgnoreCase(matB)) {
                agree++;
                checks.add("Same material: " + matA);
            } else {
                conflicts.add("Material conflict: " + matA + " vs " + matB);
            }
        }

        Object sizeA = attrsA.get("nominal_size_mm");
        Object sizeB = attrsB.get("nominal_size_mm");
        if (sizeA != null && sizeB != null) {
            compared++;
            if (Objects.equals(sizeA, sizeB)) {
                agree++;
                checks.add("Same nominal size: " + sizeA + "mm");
            } else {
                conflicts.add("Size divergence: " + sizeA + "mm vs " + sizeB + "mm");
            }
        }

        String grdA = (String) attrsA.get("grade");
        String grdB = (String) attrsB.get("grade");
        if (grdA != null && grdB != null) {
            compared++;
            if (grdA.equalsIgnoreCase(grdB)) {
                agree++;
                checks.add("Same grade: " + grdA);
            } else {
                warnings.add("Grade variance: " + grdA + " vs " + grdB);
            }
        }

        String schA = (String) attrsA.get("schedule");
        String schB = (String) attrsB.get("schedule");
        if (schA != null && schB != null) {
            compared++;
            if (schA.equalsIgnoreCase(schB)) {
                agree++;
                checks.add("Same schedule: " + schA);
            } else {
                warnings.add("Schedule variance: " + schA + " vs " + schB);
            }
        }

        String stdA = (String) attrsA.get("standard");
        String stdB = (String) attrsB.get("standard");
        if (stdA != null && stdB != null) {
            if (stdA.equalsIgnoreCase(stdB)) {
                checks.add("Same standard: " + stdA);
            } else {
                conflicts.add("Standard divergence: " + stdA + " vs " + stdB);
            }
        }

        double attrScore = compared > 0 ? ((double) agree / compared) : 0.5;
        double overallScore = 0.5 * lexical + 0.4 * attrScore + (sameCat ? 0.1 : 0.0);
        if (!conflicts.isEmpty() && conflicts.stream().anyMatch(c -> c.contains("Material conflict"))) {
            overallScore = Math.min(overallScore, 0.25);
        }

        BigDecimal scoreBd = BigDecimal.valueOf(Math.min(1.0, Math.max(0.0, overallScore)))
                .setScale(4, RoundingMode.HALF_UP);
        double score = scoreBd.doubleValue();

        String tier = score >= 0.85 ? "HIGH" : (score >= 0.60 ? "MEDIUM" : "LOW");
        String relationship = score >= 0.85 ? "NEAR_DUPLICATE" : (score >= 0.60 ? "FUNCTIONALLY_EQUIVALENT" : "NOT_A_MATCH");

        ExplanationDto exp = new ExplanationDto(checks, warnings, conflicts);
        Map<String, Double> scoreBreakdown = Map.of(
                "modelProbability", score,
                "lexicalSimilarity", BigDecimal.valueOf(lexical).setScale(4, RoundingMode.HALF_UP).doubleValue(),
                "attributeCompatibility", BigDecimal.valueOf(attrScore).setScale(4, RoundingMode.HALF_UP).doubleValue(),
                "categoryCompatibility", sameCat ? 1.0 : 0.0
        );

        CompareResponse resp = new CompareResponse();
        resp.setPredictedRelationship(relationship);
        resp.setConfidence(score);
        resp.setMatchProbability(score);
        resp.setLabelProbability(score);
        resp.setConfidenceTier(tier);
        resp.setClassProbabilities(Map.of(relationship, score));
        resp.setExplanation(exp);
        resp.setScoreBreakdown(scoreBreakdown);
        resp.setCriticalConflicts(conflicts);
        resp.setModelVersion("java-heuristic-fallback-1.0");
        resp.setNote("Computed via local rule-based heuristic engine");
        return resp;
    }

    private double computeTokenSimilarity(String desc1, String desc2) {
        if (desc1 == null || desc2 == null) return 0.0;
        Set<String> set1 = new HashSet<>(Arrays.asList(desc1.toUpperCase(Locale.ROOT).split("[\\s,;/-]+")));
        Set<String> set2 = new HashSet<>(Arrays.asList(desc2.toUpperCase(Locale.ROOT).split("[\\s,;/-]+")));
        set1.removeIf(String::isBlank);
        set2.removeIf(String::isBlank);
        if (set1.isEmpty() && set2.isEmpty()) return 1.0;
        if (set1.isEmpty() || set2.isEmpty()) return 0.0;

        Set<String> union = new HashSet<>(set1);
        union.addAll(set2);
        Set<String> intersection = new HashSet<>(set1);
        intersection.retainAll(set2);

        return (double) intersection.size() / union.size();
    }

    public FindMatchesResponse fallbackFindMatches(MaterialInfoDto target, List<MaterialInfoDto> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return new FindMatchesResponse(Collections.emptyList());
        }
        List<MatchCandidateResultDto> matches = new ArrayList<>();
        Map<String, Object> targetAttrs = extractHeuristicAttributes(target.getDescription(), target.getSpecification());

        for (MaterialInfoDto candidate : candidates) {
            Map<String, Object> candAttrs = extractHeuristicAttributes(candidate.getDescription(), candidate.getSpecification());
            CompareResponse comp = fallbackCompare(target, candidate, targetAttrs, candAttrs);

            MatchCandidateResultDto match = new MatchCandidateResultDto();
            match.setCandidate(toPayloadMap(candidate));
            match.setMatchProbability(comp.getMatchProbability());
            match.setConfidence(comp.getConfidence());
            match.setLabelProbability(comp.getLabelProbability());
            match.setConfidenceTier(comp.getConfidenceTier());
            match.setPredictedRelationship(comp.getPredictedRelationship());
            match.setExplanation(comp.getExplanation());
            match.setScoreBreakdown(comp.getScoreBreakdown());
            matches.add(match);
        }

        matches.sort((m1, m2) -> Double.compare(m2.getMatchProbability(), m1.getMatchProbability()));
        int topK = Math.min(5, matches.size());
        return new FindMatchesResponse(matches.subList(0, topK));
    }

    private Map<String, Object> toPayloadMap(MaterialInfoDto info) {
        Map<String, Object> map = new HashMap<>();
        if (info.getMaterialId() != null) map.put("material_id", info.getMaterialId());
        map.put("description", info.getDescription() != null ? info.getDescription() : "");
        map.put("category", info.getCategory() != null ? info.getCategory() : "UNKNOWN");
        if (info.getSpecification() != null) map.put("specification", info.getSpecification());
        if (info.getCpseMaterialCode() != null) map.put("cpse_material_code", info.getCpseMaterialCode());
        if (info.getCpseName() != null) map.put("cpse_name", info.getCpseName());
        return map;
    }
}
