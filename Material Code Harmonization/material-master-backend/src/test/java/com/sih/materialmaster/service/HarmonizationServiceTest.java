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
    private MatchingClient matchingClient;

    @Mock
    private NationalCodeGenerator codeGenerator;

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

        when(matchingClient.getCategorySchema(anyString()))
                .thenReturn(new CategorySchemaDto("PIPING", List.of("material"), List.of(), List.of("material")));
    }

    @Test
    @DisplayName("Harmonize material when Python ML service returns match candidates")
    void testHarmonizeMaterial_SuccessWithMatch() {
        when(codeGenerator.computeAttributeSignature(any(), any(), any()))
                .thenReturn(new NationalCodeGenerator.SignatureResult("sig_pipe_partial", false));

        when(materialRepository.findById(100L)).thenReturn(Optional.of(queryMaterial));
        when(materialRepository.findCandidatesByCategory(anyLong(), anyLong(), any(Pageable.class)))
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

        when(codeGenerator.generateProvisionalRef(anyLong())).thenReturn("PROV-2026-000500");

        MaterialGroup savedGroup = new MaterialGroup();
        savedGroup.setGroupId(500L);
        savedGroup.setProvisionalRef("PROV-2026-000500");
        when(groupRepository.save(any(MaterialGroup.class))).thenReturn(savedGroup);

        MaterialMapping savedMapping = new MaterialMapping();
        savedMapping.setMappingId(999L);
        savedMapping.setMaterial(queryMaterial);
        savedMapping.setGroup(savedGroup);
        when(mappingRepository.save(any(MaterialMapping.class))).thenReturn(savedMapping);

        HarmonizationResultDto result = harmonizationService.harmonizeMaterial(100L);

        assertNotNull(result);
        assertEquals("MAPPING_PROPOSED", result.getStatus());
        assertEquals("PROV-2026-000500", result.getProposedGroupCode());
        assertEquals(0.92, result.getConfidenceScore());
        assertEquals("HIGH", result.getConfidenceTier());
    }

    @Test
    @DisplayName("Harmonize material when no candidates exist creates provisional novel group")
    void testHarmonizeMaterial_NoCandidates() {
        when(codeGenerator.computeAttributeSignature(any(), any(), any()))
                .thenReturn(new NationalCodeGenerator.SignatureResult("sig_pipe_novel", false));

        when(materialRepository.findById(100L)).thenReturn(Optional.of(queryMaterial));
        when(materialRepository.findCandidatesByCategory(anyLong(), anyLong(), any(Pageable.class)))
                .thenReturn(Collections.emptyList());

        when(codeGenerator.generateProvisionalRef(anyLong())).thenReturn("PROV-2026-000501");

        MaterialGroup savedGroup = new MaterialGroup();
        savedGroup.setGroupId(501L);
        savedGroup.setProvisionalRef("PROV-2026-000501");
        when(groupRepository.save(any(MaterialGroup.class))).thenReturn(savedGroup);

        MaterialMapping savedMapping = new MaterialMapping();
        savedMapping.setMappingId(1000L);
        savedMapping.setMaterial(queryMaterial);
        savedMapping.setGroup(savedGroup);
        when(mappingRepository.save(any(MaterialMapping.class))).thenReturn(savedMapping);

        HarmonizationResultDto result = harmonizationService.harmonizeMaterial(100L);

        assertNotNull(result);
        assertEquals("NOVEL_SPECIFICATION_REGISTERED", result.getStatus());
    }

    @Test
    @DisplayName("Step 1 (D1): Deterministic match joins existing group on identical complete signature")
    void testHarmonizeMaterial_DeterministicSignatureMatch() {
        when(materialRepository.findById(100L)).thenReturn(Optional.of(queryMaterial));
        when(codeGenerator.computeAttributeSignature(any(), any(), any()))
                .thenReturn(new NationalCodeGenerator.SignatureResult("sig_exact_hash_123", true));

        MaterialGroup existingGroup = new MaterialGroup();
        existingGroup.setGroupId(777L);
        existingGroup.setCommonMaterialCode("NUMM-40-14-16-000777-X");
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
        assertEquals("NUMM-40-14-16-000777-X", result.getProposedGroupCode());
    }
}
