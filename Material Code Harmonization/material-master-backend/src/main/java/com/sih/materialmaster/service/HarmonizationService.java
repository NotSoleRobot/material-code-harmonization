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

/**
 * HarmonizationService implements the 4-step decision order (D1) specified in NUMM:
 * 1. Deterministic Attribute Signature Match (only when signature is complete)
 * 2. High-Confidence AI Match (Score >= 0.85, EXACT_DUPLICATE or NEAR_DUPLICATE)
 * 3. Group Relation Classification (FUNCTIONALLY_EQUIVALENT / VARIANT)
 * 4. Novel Specification Registration (PROPOSED group with provisional reference)
 */
@Service
public class HarmonizationService {

    private static final Logger log = LoggerFactory.getLogger(HarmonizationService.class);

    private final MaterialRepository materialRepository;
    private final MaterialGroupRepository groupRepository;
    private final MaterialMappingRepository mappingRepository;
    private final GroupRelationRepository groupRelationRepository;
    private final MatchingClient matchingClient;
    private final NationalCodeGenerator codeGenerator;
    private final AuditService auditService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public HarmonizationService(MaterialRepository materialRepository,
                                MaterialGroupRepository groupRepository,
                                MaterialMappingRepository mappingRepository,
                                GroupRelationRepository groupRelationRepository,
                                MatchingClient matchingClient,
                                NationalCodeGenerator codeGenerator,
                                AuditService auditService) {
        this.materialRepository = materialRepository;
        this.groupRepository = groupRepository;
        this.mappingRepository = mappingRepository;
        this.groupRelationRepository = groupRelationRepository;
        this.matchingClient = matchingClient;
        this.codeGenerator = codeGenerator;
        this.auditService = auditService;
    }

    public CompareResponse compare(MaterialInfoDto a, MaterialInfoDto b) {
        return matchingClient.compareDetailed(a, b);
    }

    /** Extracts and persists attributes in one matching-service request. */
    @Transactional
    public void extractAttributesForMaterials(List<Long> materialIds) {
        if (materialIds == null || materialIds.isEmpty()) return;

        List<Material> materials = new ArrayList<>();
        materialRepository.findAllById(materialIds).forEach(materials::add);
        if (materials.size() != materialIds.size()) {
            throw new IllegalArgumentException("One or more materials no longer exist");
        }

        List<AttributeExtractionResult> results = matchingClient.extractAttributes(
                materials.stream().map(this::toDto).toList());
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
    @Transactional
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
                throw new IllegalStateException("Attribute extraction failed for material " + materialId, e);
            }
        }

        // Fetch category schema to know identity-critical keys
        CategorySchemaDto schema = matchingClient.getCategorySchema(categoryName);
        List<String> identityKeys = schema.getIdentityCriticalAttributes();

        // Compute Attribute Signature
        NationalCodeGenerator.SignatureResult sigResult = codeGenerator.computeAttributeSignature(categoryName, identityKeys, targetAttrs);

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
                String explanation = "{\"checks\":[\"Exact deterministic attribute signature match\"],\"warnings\":[],\"conflicts\":[]}";
                MaterialMapping mapping = createOrUpdateMapping(target, group, 1.0, "HIGH", explanation, "DETERMINISTIC_SIGNATURE");

                result.setStatus("DETERMINISTIC_MATCH");
                result.setGroupId(group.getGroupId());
                result.setMappingId(mapping.getMappingId());
                result.setProposedGroupCode(group.getCommonMaterialCode() != null ? group.getCommonMaterialCode() : group.getProvisionalRef());
                result.setConfidenceScore(1.0);
                result.setConfidenceTier("HIGH");
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
            // Novel specification: create proposed group for standalone item
            MaterialGroup group = createOrFindGroupForMaterial(target, targetAttrs, sigResult);
            String explanation = "{\"checks\":[\"Initial novel specification item registered (no candidates)\"],\"warnings\":[],\"conflicts\":[]}";
            MaterialMapping mapping = createOrUpdateMapping(target, group, 0.0, "LOW", explanation, "NOVEL");

            result.setStatus("NOVEL_SPECIFICATION_REGISTERED");
            result.setGroupId(group.getGroupId());
            result.setMappingId(mapping.getMappingId());
            result.setProposedGroupCode(group.getProvisionalRef());
            result.setConfidenceScore(0.0);
            result.setConfidenceTier("LOW");
            result.setMatches(Collections.emptyList());

            auditService.logEvent(null, "HARMONIZATION_RUN", "MATERIAL", target.getMaterialId(), null,
                    "Novel specification registered: " + result.getProposedGroupCode());
            return result;
        }

        MaterialInfoDto targetDto = toDto(target);
        List<MaterialInfoDto> candidateDtos = candidateEntities.stream()
                .map(this::toDto)
                .toList();

        FindMatchesResponse matchResponse = precomputedResponse != null
                ? precomputedResponse
                : matchingClient.findMatches(targetDto, candidateDtos, 5);
        List<MatchCandidateResultDto> matches = matchResponse.getMatches();
        result.setMatches(matches != null ? matches : Collections.emptyList());

        if (matches == null || matches.isEmpty()) {
            MaterialGroup group = createOrFindGroupForMaterial(target, targetAttrs, sigResult);
            String explanation = "{\"checks\":[\"No close AI matches found — novel item\"],\"warnings\":[],\"conflicts\":[]}";
            MaterialMapping mapping = createOrUpdateMapping(target, group, 0.0, "LOW", explanation, "NOVEL_SPECIFICATION");

            result.setStatus("DISTINCT_MATERIAL_NO_MERGE");
            result.setGroupId(group.getGroupId());
            result.setMappingId(mapping.getMappingId());
            result.setProposedGroupCode(group.getProvisionalRef());
            result.setConfidenceScore(0.0);
            result.setConfidenceTier("LOW");
            return result;
        }

        MatchCandidateResultDto topMatch = matches.get(0);
        String rel = topMatch.getPredictedRelationship() != null ? topMatch.getPredictedRelationship() : "NOT_A_MATCH";
        double score = topMatch.getMatchProbability() > 0 ? topMatch.getMatchProbability() : topMatch.getConfidence();
        String tier = topMatch.getConfidenceTier() != null ? topMatch.getConfidenceTier() : (score >= 0.85 ? "HIGH" : (score >= 0.60 ? "MEDIUM" : "LOW"));

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

        if (isDuplicateMerge && matchedEntity != null) {
            Optional<MaterialMapping> candMapping = mappingRepository.findActiveByMaterialId(matchedEntity.getMaterialId());
            if (candMapping.isPresent() && candMapping.get().getGroup() != null) {
                targetGroup = candMapping.get().getGroup();
                matchBasis = "ML_PROPOSED";
            }
        }

        if (targetGroup == null) {
            targetGroup = createOrFindGroupForMaterial(target, targetAttrs, sigResult);
            matchBasis = isDuplicateMerge ? "ML_PROPOSED" : "NOVEL";

            if (isDuplicateMerge && matchedEntity != null) {
                Optional<MaterialMapping> candActive = mappingRepository.findActiveByMaterialId(matchedEntity.getMaterialId());
                if (candActive.isEmpty()) {
                    createOrUpdateMapping(matchedEntity, targetGroup, score, tier, explanationJson, matchBasis);
                }
            }
        }

        // Create target material mapping
        MaterialMapping targetMapping = createOrUpdateMapping(target, targetGroup, score, tier, explanationJson, matchBasis);

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
        result.setStatus("MAPPING_PROPOSED");

        // Audit harmonization run (FR9)
        auditService.logEvent(
                null,
                "HARMONIZATION_RUN",
                "MATERIAL",
                target.getMaterialId(),
                null,
                "Proposed group " + result.getProposedGroupCode() + " (" + rel + ", tier: " + tier + ", score: " + score + ", basis: " + matchBasis + ")"
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
        List<FindMatchesResponse> responses;
        try {
            responses = matchingClient.findMatchesBatch(queries);
        } catch (RuntimeException batchFailure) {
            // A free hosted worker can be running an older image or can time out
            // while warming the batch model. Do not fail every material when the
            // stable single-query endpoint can recover the same work.
            log.warn("Batch matching failed for {} materials; falling back to individual requests: {}",
                    queries.size(), batchFailure.getMessage());
            responses = new ArrayList<>(queries.size());
            for (FindMatchesBatchQuery query : queries) {
                responses.add(matchingClient.findMatches(
                        query.material(), query.candidates(), query.topK()));
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
                candidates = materialRepository.findCandidatesAll(target.getMaterialId(), PageRequest.of(0, 50));
            }
        } else {
            candidates = materialRepository.findCandidatesAll(target.getMaterialId(), PageRequest.of(0, 50));
        }
        return candidates;
    }

    private MaterialGroup createOrFindGroupForMaterial(Material material, Map<String, Object> attrs, NationalCodeGenerator.SignatureResult sigResult) {
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
        newGroup.setStatus("PROPOSED"); // Mint canonical NUMM code only on reviewer approval (BUG-03)

        long serial = codeGenerator.allocateSerial(material.getCategory());
        newGroup.setCodeSerial(serial);
        newGroup.setProvisionalRef(codeGenerator.generateProvisionalRef(material.getCategory(), serial));

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

    private MaterialMapping createOrUpdateMapping(Material material, MaterialGroup group, double score, String tier, String explanationJson, String matchBasis) {
        Optional<MaterialMapping> activeMapping = mappingRepository.findActiveByMaterialId(material.getMaterialId());
        MaterialMapping mapping;
        if (activeMapping.isPresent() && Set.of("CONFIRMED", "REJECTED").contains(activeMapping.get().getStatus())) {
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
        mapping.setStatus("PENDING");
        mapping.setExplanationJson(explanationJson);
        mapping.setMatchBasis(matchBasis);

        return mappingRepository.save(mapping);
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
}
