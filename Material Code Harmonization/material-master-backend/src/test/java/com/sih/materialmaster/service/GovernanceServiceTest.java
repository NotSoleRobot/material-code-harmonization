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
    private NationalCodeGenerator codeGenerator;

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
        reviewerIocl.setRole("REVIEWER");
        reviewerIocl.setCpse(iocl);
    }

    @Test
    @DisplayName("Approve mapping with 4-eyes validation & mint national code when group is PROPOSED")
    void testDecideMapping_Approve() {
        when(reviewerAssignmentRepository.findCategoryIdsByUserId(anyLong())).thenReturn(java.util.List.of(1L));
        when(mappingRepository.findById(500L)).thenReturn(Optional.of(mapping));
        when(mappingRepository.save(any(MaterialMapping.class))).thenAnswer(inv -> inv.getArgument(0));

        MaterialMapping approved = governanceService.decideMapping(500L, reviewerIocl, "CONFIRMED", "Verified specification equivalence");

        assertNotNull(approved);
        assertEquals("CONFIRMED", approved.getStatus());
        assertEquals("PROPOSED", group.getStatus(), "Group remains PROPOSED until Senior Reviewer publishes and mints");

        verify(auditService).logEvent(
                eq(reviewerIocl),
                eq("MAPPING_CONFIRMED"),
                eq("MATERIAL_MAPPING"),
                eq(500L),
                eq("status: PENDING"),
                anyString()
        );

        // Step 2: Senior Reviewer mints the code
        User seniorReviewer = new User();
        seniorReviewer.setUserId(99L);
        seniorReviewer.setRole("SENIOR_REVIEWER");

        when(groupRepository.findLockedById(100L)).thenReturn(Optional.of(group));
        when(mappingRepository.findByGroup_GroupId(100L)).thenReturn(java.util.List.of(approved));
        when(codeGenerator.mintNationalCode(any(), any(), any())).thenReturn("NUMM-401416-CS-050-S40-000100-K");
        when(groupRepository.save(any(MaterialGroup.class))).thenAnswer(inv -> inv.getArgument(0));

        String code = governanceService.mintGroup(100L, seniorReviewer);
        assertEquals("NUMM-401416-CS-050-S40-000100-K", code);
        assertEquals("ACTIVE", group.getStatus());
        assertEquals("NUMM-401416-CS-050-S40-000100-K", group.getCommonMaterialCode());
    }

    @Test
    @DisplayName("Reject approval if reviewer belongs to the same CPSE (Conflict of Interest prevention)")
    void testDecideMapping_ConflictOfInterestBlocked() {
        User reviewerOngc = new User();
        reviewerOngc.setUserId(1L);
        reviewerOngc.setName("Pavan ONGC");
        reviewerOngc.setEmail("operator@ongc.res.in");
        reviewerOngc.setRole("REVIEWER");
        reviewerOngc.setCpse(ongc); // Same as material's CPSE

        when(reviewerAssignmentRepository.findCategoryIdsByUserId(anyLong())).thenReturn(java.util.List.of(1L));
        when(mappingRepository.findById(500L)).thenReturn(Optional.of(mapping));

        com.sih.materialmaster.exception.ConflictOfInterestException ex =
                assertThrows(com.sih.materialmaster.exception.ConflictOfInterestException.class, () ->
                        governanceService.decideMapping(500L, reviewerOngc, "CONFIRMED", "Self-approval attempt")
                );

        assertTrue(ex.getMessage().contains("Conflict of Interest"), "Self-approval by submitting CPSE must be blocked");
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
