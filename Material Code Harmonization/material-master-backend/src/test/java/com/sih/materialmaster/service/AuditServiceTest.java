package com.sih.materialmaster.service;

import com.sih.materialmaster.entity.AuditTrail;
import com.sih.materialmaster.entity.AuditChainHead;
import com.sih.materialmaster.entity.User;
import com.sih.materialmaster.repository.AuditChainHeadRepository;
import com.sih.materialmaster.repository.AuditTrailRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuditServiceTest {

    @Mock
    private AuditTrailRepository auditTrailRepository;

    @Mock
    private AuditChainHeadRepository auditChainHeadRepository;

    @InjectMocks
    private AuditService auditService;

    @Test
    @DisplayName("Chained SHA-256 hash computation from Genesis root")
    void testLogWithGenesisRoot() {
        AuditChainHead head = new AuditChainHead(1,
                "0000000000000000000000000000000000000000000000000000000000000000");
        when(auditChainHeadRepository.findByIdForUpdate(1)).thenReturn(Optional.of(head));
        when(auditChainHeadRepository.save(any(AuditChainHead.class))).thenAnswer(inv -> inv.getArgument(0));
        when(auditTrailRepository.save(any(AuditTrail.class))).thenAnswer(inv -> inv.getArgument(0));

        User user = new User();
        user.setUserId(1L);
        user.setName("test_reviewer");
        user.setEmail("reviewer@numm.gov.in");

        AuditTrail log = auditService.logEvent(
                user,
                "MAPPING_APPROVED",
                "MATERIAL_MAPPING",
                101L,
                "status: PENDING",
                "status: CONFIRMED"
        );

        assertNotNull(log);
        assertEquals("0000000000000000000000000000000000000000000000000000000000000000", log.getPrevHash(), "Initial entry must link to Genesis hash");
        assertNotNull(log.getRowHash(), "Row hash must be computed");
        assertEquals(64, log.getRowHash().length(), "Row hash must be 64-character SHA-256");
    }

    @Test
    @DisplayName("Verify empty audit log returns valid genesis status")
    void testVerifyChainIntegrity_Empty() {
        when(auditTrailRepository.count()).thenReturn(0L);

        Map<String, Object> verifyResult = auditService.verifyChainIntegrity();

        assertNotNull(verifyResult);
        assertEquals(true, verifyResult.get("valid"));
        assertEquals(0, verifyResult.get("chainLength"));
    }

    @Test
    @DisplayName("Detect tampered row hash in audit chain")
    void testVerifyChainIntegrity_TamperDetection() {
        AuditTrail entry1 = new AuditTrail();
        entry1.setAuditId(1L);
        entry1.setAction("MAPPING_PROPOSED");
        entry1.setEntityType("MATERIAL_MAPPING");
        entry1.setEntityId(101L);
        entry1.setTimestamp(LocalDateTime.of(2026, 9, 17, 10, 0, 0));
        entry1.setPrevHash("0000000000000000000000000000000000000000000000000000000000000000");
        entry1.setRowHash("CORRUPTED_TAMPERED_HASH_VALUE_THAT_FAILS_SHA256_VERIFICATION");

        when(auditTrailRepository.count()).thenReturn(1L);
        when(auditTrailRepository.findAll(any(org.springframework.data.domain.Pageable.class)))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(entry1)));

        Map<String, Object> verifyResult = auditService.verifyChainIntegrity();

        assertNotNull(verifyResult);
        assertEquals(false, verifyResult.get("valid"));
        assertEquals(1L, verifyResult.get("brokenAtAuditId"));
    }
}
