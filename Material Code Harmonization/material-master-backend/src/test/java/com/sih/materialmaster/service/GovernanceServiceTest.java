package com.sih.materialmaster.service;

import com.sih.materialmaster.entity.*;
import com.sih.materialmaster.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GovernanceServiceTest {

    @Mock
    private MaterialMappingRepository mappingRepository;

    @Mock
    private MaterialGroupRepository groupRepository;

    @Mock
    private AuditService auditService;

    @Mock
    private ReviewerAssignmentRepository reviewerAssignmentRepository;

    @Mock
    private MatchingFeedbackRepository matchingFeedbackRepository;

    @InjectMocks
    private GovernanceService governanceService;

    private MaterialMapping mapping;
    private Material material;
    private MaterialGroup group;
    private Cpse ongc;
    private Cpse iocl;
    private User reviewerIocl;

    @BeforeEach
    void setUp() {
        ongc = new Cpse();
        ongc.setCpseId(1L);
        ongc.setName("ONGC");

        iocl = new Cpse();
        iocl.setCpseId(2L);
        iocl.setName("IOCL");

        MaterialCategory category = new MaterialCategory();
        category.setCategoryId(1L);
        category.setName("PIPE");
        category.setCodeSegment("40");
        category.setCodeFamily("14");
        category.setCodeClass("16");

        material = new Material();
        material.setMaterialId(10L);
        material.setCpse(ongc);
        material.setCpseMaterialCode("ONGC-P-101");
        material.setDescription("CS SEAMLESS PIPE 50MM SCH40");
        material.setCategory(category);

        group = new MaterialGroup();
        group.setGroupId(100L);
        group.setStatus("PROPOSED");
        group.setProvisionalRef("PROV-2026-000100");
        group.setStandardizedDescription("CS SEAMLESS PIPE 50MM SCH40");
        group.setCategory(category);

        mapping = new MaterialMapping();
        mapping.setMappingId(500L);
        mapping.setMaterial(material);
        mapping.setGroup(group);
        mapping.setStatus("PENDING");
        mapping.setConfidenceScore(BigDecimal.valueOf(0.92));

        reviewerIocl = new User();
        reviewerIocl.setUserId(2L);
        reviewerIocl.setName("Dr. S. Ananth");
        reviewerIocl.setEmail("reviewer@iocl.co.in");
        reviewerIocl.setRole("SENIOR_REVIEWER");
        reviewerIocl.setCpse(iocl);
    }

    @Test
    @DisplayName("Approve mapping confirms it without changing catalog group lifecycle")
    void testDecideMapping_Approve() {
        when(reviewerAssignmentRepository.findCategoryIdsByUserId(anyLong())).thenReturn(java.util.List.of(1L));
        when(mappingRepository.findById(500L)).thenReturn(Optional.of(mapping));
        when(mappingRepository.save(any(MaterialMapping.class))).thenAnswer(inv -> inv.getArgument(0));

        MaterialMapping approved = governanceService.decideMapping(500L, reviewerIocl, "CONFIRMED", "Verified specification equivalence");

        assertNotNull(approved);
        assertEquals("CONFIRMED", approved.getStatus());
        assertEquals("PROPOSED", group.getStatus());
        assertNull(group.getCommonMaterialCode());

        verify(auditService).logEvent(
                eq(reviewerIocl),
                eq("MAPPING_CONFIRMED"),
                eq("MATERIAL_MAPPING"),
                eq(500L),
                eq("status: PENDING"),
                anyString()
        );
    }

    @Test
    @DisplayName("Same-CPSE reviewer can approve without conflict-of-interest block")
    void testDecideMapping_SameCpseReviewerAllowed() {
        User reviewerOngc = new User();
        reviewerOngc.setUserId(1L);
        reviewerOngc.setName("Pavan ONGC");
        reviewerOngc.setEmail("operator@ongc.res.in");
        reviewerOngc.setRole("SENIOR_REVIEWER");
        reviewerOngc.setCpse(ongc); // Same as material's CPSE

        when(reviewerAssignmentRepository.findCategoryIdsByUserId(anyLong())).thenReturn(java.util.List.of(1L));
        when(mappingRepository.findById(500L)).thenReturn(Optional.of(mapping));
        when(mappingRepository.save(any(MaterialMapping.class))).thenAnswer(inv -> inv.getArgument(0));

        // Should NOT throw — COI restriction removed
        MaterialMapping approved = governanceService.decideMapping(500L, reviewerOngc, "CONFIRMED", "Self-CPSE approval is fine now");
        assertNotNull(approved);
        assertEquals("CONFIRMED", approved.getStatus());
    }

    @Test
    @DisplayName("Reject mapping transitions status to REJECTED and frees raw material")
    void testDecideMapping_Reject() {
        when(reviewerAssignmentRepository.findCategoryIdsByUserId(anyLong())).thenReturn(java.util.List.of(1L));
        when(mappingRepository.findById(500L)).thenReturn(Optional.of(mapping));
        when(mappingRepository.save(any(MaterialMapping.class))).thenAnswer(inv -> inv.getArgument(0));

        MaterialMapping rejected = governanceService.decideMapping(500L, reviewerIocl, "REJECTED", "Specification grade conflict");

        assertNotNull(rejected);
        assertEquals("REJECTED", rejected.getStatus());
        verify(auditService).logEvent(
                eq(reviewerIocl),
                eq("MAPPING_REJECTED"),
                eq("MATERIAL_MAPPING"),
                eq(500L),
                eq("status: PENDING"),
                anyString()
        );
    }

    @Test
    @DisplayName("Supersede preserves the original decision and links a replacement mapping")
    void testSupersedeMapping_PreservesLineage() {
        mapping.setStatus("CONFIRMED");
        User admin = new User();
        admin.setUserId(77L);
        admin.setRole("ADMIN");

        when(mappingRepository.findById(500L)).thenReturn(Optional.of(mapping));
        when(mappingRepository.saveAndFlush(any(MaterialMapping.class))).thenAnswer(invocation -> {
            MaterialMapping saved = invocation.getArgument(0);
            if (saved.getMappingId() == null) saved.setMappingId(501L);
            return saved;
        });
        when(mappingRepository.save(any(MaterialMapping.class))).thenAnswer(invocation -> invocation.getArgument(0));

        MaterialMapping replacement = governanceService.supersedeMapping(
                500L, admin, "REJECTED", "Engineering specification was revalidated");

        assertEquals("SUPERSEDED", mapping.getStatus());
        assertEquals("REJECTED", replacement.getStatus());
        assertSame(mapping, replacement.getSupersedesMapping());
        assertSame(replacement, mapping.getSupersededByMapping());
        verify(auditService).logEvent(eq(admin), eq("MAPPING_SUPERSEDED"),
                eq("MATERIAL_MAPPING"), eq(500L), anyString(), contains("replacementMappingId: 501"));
    }
}
