package com.sih.materialmaster.service;

import com.sih.materialmaster.dto.*;
import com.sih.materialmaster.entity.*;
import com.sih.materialmaster.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class HarmonizationServiceTest {

    @Mock
    private MaterialRepository materialRepository;

    @Mock
    private MaterialGroupRepository groupRepository;

    @Mock
    private MaterialMappingRepository mappingRepository;

    @Mock
    private GroupRelationRepository groupRelationRepository;

    @Mock
    private MatchingPolicyRepository matchingPolicyRepository;

    @Mock
    private MatchCandidateRepository matchCandidateRepository;

    @Mock
    private MatchingClient matchingClient;

    @Mock
    private MaterialIdentityService identityService;

    @Mock
    private AuditService auditService;

    @InjectMocks
    private HarmonizationService harmonizationService;

    private Material queryMaterial;
    private Material candidateMaterial;

    @BeforeEach
    void setUp() {
        Cpse cpse1 = new Cpse();
        cpse1.setCpseId(1L);
        cpse1.setName("ONGC");

        Cpse cpse2 = new Cpse();
        cpse2.setCpseId(2L);
        cpse2.setName("IOCL");

        MaterialCategory cat = new MaterialCategory();
        cat.setCategoryId(10L);
        cat.setName("Piping");

        queryMaterial = new Material();
        queryMaterial.setMaterialId(100L);
        queryMaterial.setCpse(cpse1);
        queryMaterial.setCategory(cat);
        queryMaterial.setCpseMaterialCode("PIPE-001");
        queryMaterial.setDescription("CS SEAMLESS PIPE 50MM SCH40 ASTM A106 GRB");

        candidateMaterial = new Material();
        candidateMaterial.setMaterialId(200L);
        candidateMaterial.setCpse(cpse2);
        candidateMaterial.setCategory(cat);
        candidateMaterial.setCpseMaterialCode("P-50-CS");
        candidateMaterial.setDescription("CARBON STEEL PIPE DN50 SCHEDULE 40 GR.B");

        lenient().when(matchingClient.getCategorySchema(anyString()))
                .thenReturn(new CategorySchemaDto("PIPING", List.of("material"), List.of(), List.of("material")));
    }

    @Test
    @DisplayName("Harmonize material when Python ML service returns match candidates")
    void testHarmonizeMaterial_SuccessWithMatch() {
        when(identityService.computeAttributeSignature(any(), any(), any()))
                .thenReturn(new MaterialIdentityService.SignatureResult("sig_pipe_partial", false));

        when(materialRepository.findById(100L)).thenReturn(Optional.of(queryMaterial));
        when(materialRepository.findRelevantCandidates(anyLong(), anyLong(), anyString(), any(), any()))
                .thenReturn(List.of(candidateMaterial));

        MatchCandidateResultDto candidateResult = new MatchCandidateResultDto();
        candidateResult.setPredictedRelationship("NEAR_DUPLICATE");
        candidateResult.setConfidence(0.92);
        candidateResult.setConfidenceTier("HIGH");

        Map<String, Object> candMap = new HashMap<>();
        candMap.put("material_id", 200L);
        candMap.put("cpse_material_code", "P-50-CS");
        candMap.put("description", candidateMaterial.getDescription());
        candidateResult.setCandidate(candMap);

        FindMatchesResponse findResponse = new FindMatchesResponse(List.of(candidateResult));
        when(matchingClient.findMatches(any(), anyList(), eq(5))).thenReturn(findResponse);

        MaterialGroup savedGroup = new MaterialGroup();
        savedGroup.setGroupId(500L);
        savedGroup.setProvisionalRef("DRAFT-000000000500");
        savedGroup.setCommonMaterialCode("NUMM-401407-000500");
        savedGroup.setStatus("ACTIVE");
        MaterialMapping candidateMapping = new MaterialMapping();
        candidateMapping.setStatus("CONFIRMED");
        candidateMapping.setGroup(savedGroup);
        when(mappingRepository.findActiveByMaterialId(200L)).thenReturn(Optional.of(candidateMapping));

        MaterialMapping savedMapping = new MaterialMapping();
        savedMapping.setMappingId(999L);
        savedMapping.setMaterial(queryMaterial);
        savedMapping.setGroup(savedGroup);
        when(mappingRepository.save(any(MaterialMapping.class))).thenReturn(savedMapping);

        HarmonizationResultDto result = harmonizationService.harmonizeMaterial(100L);

        assertNotNull(result);
        assertEquals("AUTO_HARMONIZED", result.getStatus());
        assertEquals("AUTO_CONFIRM", result.getRoutingDecision());
        assertEquals("NUMM-401407-000500", result.getProposedGroupCode());
        assertEquals(0.92, result.getConfidenceScore());
        assertEquals("HIGH", result.getConfidenceTier());
    }

    @Test
    @DisplayName("Incomplete novel material is routed to review")
    void testHarmonizeMaterial_IncompleteNovelRequiresReview() {
        when(identityService.computeAttributeSignature(any(), any(), any()))
                .thenReturn(new MaterialIdentityService.SignatureResult("sig_pipe_novel", false));

        when(materialRepository.findById(100L)).thenReturn(Optional.of(queryMaterial));
        when(materialRepository.findRelevantCandidates(anyLong(), anyLong(), anyString(), any(), any()))
                .thenReturn(Collections.emptyList());

        when(identityService.generateDraftReference()).thenReturn("DRAFT-000000000501");

        MaterialGroup savedGroup = new MaterialGroup();
        savedGroup.setGroupId(501L);
        savedGroup.setProvisionalRef("DRAFT-000000000501");
        when(groupRepository.save(any(MaterialGroup.class))).thenReturn(savedGroup);

        MaterialMapping savedMapping = new MaterialMapping();
        savedMapping.setMappingId(1000L);
        savedMapping.setMaterial(queryMaterial);
        savedMapping.setGroup(savedGroup);
        when(mappingRepository.save(any(MaterialMapping.class))).thenReturn(savedMapping);

        HarmonizationResultDto result = harmonizationService.harmonizeMaterial(100L);

        assertNotNull(result);
        assertEquals("REVIEW_REQUIRED", result.getStatus());
        assertEquals("REVIEW_REQUIRED", result.getRoutingDecision());
    }

    @Test
    @DisplayName("Complete novel material receives a National Material Code without review")
    void testHarmonizeMaterial_CompleteNovelAutoRegisters() {
        queryMaterial.setSpecification("ASTM A106 GR B");
        queryMaterial.setUnitOfMeasure("MTR");
        when(identityService.computeAttributeSignature(any(), any(), any()))
                .thenReturn(new MaterialIdentityService.SignatureResult("sig_pipe_novel_complete", true));
        when(materialRepository.findById(100L)).thenReturn(Optional.of(queryMaterial));
        when(groupRepository.findByAttributeSignature("sig_pipe_novel_complete"))
                .thenReturn(Optional.empty());
        when(materialRepository.findRelevantCandidates(anyLong(), anyLong(), anyString(), any(), any()))
                .thenReturn(Collections.emptyList());
        when(identityService.generateDraftReference()).thenReturn("DRAFT-000000000503");
        when(identityService.allocateReferenceSerial(any())).thenReturn(1L);
        when(identityService.generateCatalogReference(any(), eq(1L))).thenReturn("NUMM-401407-000001");
        when(groupRepository.save(any(MaterialGroup.class))).thenAnswer(invocation -> {
            MaterialGroup group = invocation.getArgument(0);
            group.setGroupId(503L);
            return group;
        });
        when(groupRepository.saveAndFlush(any(MaterialGroup.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(mappingRepository.save(any(MaterialMapping.class))).thenAnswer(invocation -> {
            MaterialMapping mapping = invocation.getArgument(0);
            mapping.setMappingId(1003L);
            return mapping;
        });

        HarmonizationResultDto result = harmonizationService.harmonizeMaterial(100L);

        assertEquals("NOVEL_AUTO_HARMONIZED", result.getStatus());
        assertEquals("AUTO_CONFIRM", result.getRoutingDecision());
        assertEquals("NUMM-401407-000001", result.getProposedGroupCode());
        verify(groupRepository).saveAndFlush(argThat(group -> "ACTIVE".equals(group.getStatus())
                && "NUMM-401407-000001".equals(group.getCommonMaterialCode())));
    }

    @Test
    @DisplayName("Explicit model uncertainty is routed to review even at a low score")
    void testHarmonizeMaterial_NeedsReviewNeverBecomesNovel() {
        when(identityService.computeAttributeSignature(any(), any(), any()))
                .thenReturn(new MaterialIdentityService.SignatureResult("sig_uncertain", false));
        when(materialRepository.findById(100L)).thenReturn(Optional.of(queryMaterial));
        when(materialRepository.findRelevantCandidates(anyLong(), anyLong(), anyString(), any(), any()))
                .thenReturn(List.of(candidateMaterial));

        MatchCandidateResultDto uncertain = new MatchCandidateResultDto();
        uncertain.setPredictedRelationship("NEEDS_REVIEW");
        uncertain.setMatchProbability(0.0587);
        uncertain.setConfidenceTier("LOW");
        uncertain.setCandidate(Map.of("material_id", 200L));
        when(matchingClient.findMatches(any(), anyList(), eq(5)))
                .thenReturn(new FindMatchesResponse(List.of(uncertain)));

        when(identityService.generateDraftReference()).thenReturn("DRAFT-000000000502");
        MaterialGroup group = new MaterialGroup();
        group.setGroupId(502L);
        group.setProvisionalRef("DRAFT-000000000502");
        when(groupRepository.save(any(MaterialGroup.class))).thenReturn(group);
        when(mappingRepository.save(any(MaterialMapping.class))).thenAnswer(invocation -> {
            MaterialMapping mapping = invocation.getArgument(0);
            mapping.setMappingId(1002L);
            return mapping;
        });

        HarmonizationResultDto result = harmonizationService.harmonizeMaterial(100L);

        assertEquals("REVIEW_REQUIRED", result.getRoutingDecision());
        assertEquals("REVIEW_REQUIRED", result.getStatus());
    }

    @Test
    @DisplayName("Batch candidate matching falls back to the single-query endpoint")
    void prepareBatchMatchesFallsBackWhenBatchEndpointFails() {
        when(materialRepository.findById(100L)).thenReturn(Optional.of(queryMaterial));
        when(materialRepository.findRelevantCandidates(anyLong(), anyLong(), anyString(), any(), any()))
                .thenReturn(List.of(candidateMaterial));
        when(matchingClient.findMatchesBatch(anyList()))
                .thenThrow(new RuntimeException("batch endpoint unavailable"));

        FindMatchesResponse fallbackResponse = new FindMatchesResponse(List.of());
        when(matchingClient.findMatches(any(MaterialInfoDto.class), anyList(), eq(5)))
                .thenReturn(fallbackResponse);

        Map<Long, FindMatchesResponse> result = harmonizationService.prepareBatchMatches(List.of(100L));

        assertSame(fallbackResponse, result.get(100L));
        verify(matchingClient).findMatchesBatch(anyList());
        verify(matchingClient).findMatches(any(MaterialInfoDto.class), anyList(), eq(5));
    }

    @Test
    @DisplayName("Step 1 (D1): Deterministic match joins existing group on identical complete signature")
    void testHarmonizeMaterial_DeterministicSignatureMatch() {
        when(materialRepository.findById(100L)).thenReturn(Optional.of(queryMaterial));
        when(identityService.computeAttributeSignature(any(), any(), any()))
                .thenReturn(new MaterialIdentityService.SignatureResult("sig_exact_hash_123", true));

        MaterialGroup existingGroup = new MaterialGroup();
        existingGroup.setGroupId(777L);
        existingGroup.setProvisionalRef("DRAFT-000000000777");
        existingGroup.setCommonMaterialCode("NUMM-401407-000777");
        existingGroup.setStatus("ACTIVE");
        existingGroup.setAttributeSignature("sig_exact_hash_123");

        when(groupRepository.findByAttributeSignature("sig_exact_hash_123")).thenReturn(Optional.of(existingGroup));

        MaterialMapping savedMapping = new MaterialMapping();
        savedMapping.setMappingId(2000L);
        savedMapping.setMaterial(queryMaterial);
        savedMapping.setGroup(existingGroup);
        savedMapping.setMatchBasis("DETERMINISTIC_SIGNATURE");
        when(mappingRepository.save(any(MaterialMapping.class))).thenReturn(savedMapping);

        HarmonizationResultDto result = harmonizationService.harmonizeMaterial(100L);

        assertNotNull(result);
        assertEquals("DETERMINISTIC_MATCH", result.getStatus());
        assertEquals(1.0, result.getConfidenceScore());
        assertEquals("HIGH", result.getConfidenceTier());
        assertEquals("AUTO_CONFIRM", result.getRoutingDecision());
        assertEquals("NUMM-401407-000777", result.getProposedGroupCode());
    }

    @Test
    @DisplayName("Extract heuristic attributes correctly parses dimension, grade, schedule, standard, and material")
    void testExtractHeuristicAttributes() {
        Map<String, Object> attrs = harmonizationService.extractHeuristicAttributes(
                "CS SEAMLESS PIPE 50MM SCH40 ASTM A106 GRB", "ASTM A106 GR.B");
        assertNotNull(attrs);
        assertEquals("CS", attrs.get("material"));
        assertEquals(50.0, attrs.get("nominal_size_mm"));
        assertEquals("SCH40", attrs.get("schedule"));
        assertEquals("ASTM A106", attrs.get("standard"));
        assertEquals("B", attrs.get("grade"));
    }

    @Test
    @DisplayName("Fallback compare returns valid CompareResponse with explanation when matching client throws exception")
    void testCompare_FallsBackToHeuristicWhenClientFails() {
        when(matchingClient.compareDetailed(any(), any()))
                .thenThrow(new com.sih.materialmaster.exception.MatchingServiceException("Service unavailable"));

        MaterialInfoDto a = new MaterialInfoDto(1L, "CS SEAMLESS PIPE 50MM SCH40 ASTM A106 GRB", "PIPE", "ASTM A106 GR.B", "CPSE-1", "ONGC", null);
        MaterialInfoDto b = new MaterialInfoDto(2L, "CARBON STEEL PIPE DN50 SCHEDULE 40 GR.B IS1239", "PIPE", "IS 1239", "CPSE-2", "IOCL", null);

        CompareResponse resp = harmonizationService.compare(a, b);
        assertNotNull(resp);
        assertTrue(resp.getConfidence() > 0.5);
        assertNotNull(resp.getExplanation());
        assertFalse(resp.getExplanation().getChecks().isEmpty());
        assertTrue(resp.getExplanation().getChecks().stream().anyMatch(c -> c.contains("Same material")));
    }

    @Test
    @DisplayName("Fallback category schema returns valid schema for standard and open domain categories")
    void testFallbackCategorySchema() {
        CategorySchemaDto pipeSchema = harmonizationService.fallbackCategorySchema("PIPE");
        assertNotNull(pipeSchema);
        assertEquals("PIPE", pipeSchema.getCategory());
        assertTrue(pipeSchema.getIdentityCriticalAttributes().contains("material"));

        CategorySchemaDto generalSchema = harmonizationService.fallbackCategorySchema("ELECTRICAL");
        assertNotNull(generalSchema);
        assertEquals("ELECTRICAL", generalSchema.getCategory());
        assertTrue(generalSchema.getIdentityCriticalAttributes().contains("material"));
    }
}
