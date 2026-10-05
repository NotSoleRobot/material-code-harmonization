package com.sih.materialmaster.service;

import com.sih.materialmaster.entity.AuditChainHead;
import com.sih.materialmaster.entity.AuditTrail;
import com.sih.materialmaster.entity.User;
import com.sih.materialmaster.repository.AuditChainHeadRepository;
import com.sih.materialmaster.repository.AuditTrailRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * WP5 / D5: Hardened Cryptographic Audit Service.
 * - Hash payload: prev_hash | user_id | action | entity_type | entity_id | timestamp | old_value | new_value
 * - Serialized append via row-locking on audit_chain_head
 * - Constant-memory paginated chain integrity verification
 */
@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);
    private static final String GENESIS_HASH = "0000000000000000000000000000000000000000000000000000000000000000";

    private final AuditTrailRepository auditTrailRepository;
    private final AuditChainHeadRepository auditChainHeadRepository;

    public AuditService(AuditTrailRepository auditTrailRepository, AuditChainHeadRepository auditChainHeadRepository) {
        this.auditTrailRepository = auditTrailRepository;
        this.auditChainHeadRepository = auditChainHeadRepository;
    }

    /**
     * Appends an auditable event with serialized head-locking and cryptographic chaining.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AuditTrail logEvent(User user, String action, String entityType, Long entityId, String oldValue, String newValue) {
        validateEntityType(entityType);

        AuditChainHead head = auditChainHeadRepository.findByIdForUpdate(1)
                .orElseThrow(() -> new IllegalStateException("audit_chain_head row is missing"));
        String prevHash = head.getHeadHash();

        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
        Long userId = (user != null) ? user.getUserId() : 0L;
        String rowHash = computeRowHash(prevHash, userId, action, entityType, entityId, now, oldValue, newValue);

        AuditTrail audit = new AuditTrail();
        audit.setUser(user);
        audit.setAction(action);
        audit.setEntityType(entityType);
        audit.setEntityId(entityId);
        audit.setOldValue(oldValue);
        audit.setNewValue(newValue);
        audit.setPrevHash(prevHash);
        audit.setRowHash(rowHash);
        audit.setTimestamp(now);

        AuditTrail saved = auditTrailRepository.save(audit);

        head.setHeadHash(rowHash);
        auditChainHeadRepository.save(head);

        return saved;
    }

    /**
     * WP5 Task 5 / P-09: Paginated constant-memory verification of the entire cryptographic audit chain.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> verifyChainIntegrity() {
        Map<String, Object> result = new LinkedHashMap<>();

        long totalCount = auditTrailRepository.count();
        if (totalCount == 0) {
            result.put("valid", true);
            result.put("chainLength", 0);
            result.put("genesisHash", GENESIS_HASH);
            result.put("headHash", GENESIS_HASH);
            result.put("message", "Audit log is empty (genesis state).");
            return result;
        }

        String expectedPrevHash = GENESIS_HASH;
        int pageSize = 1000;
        int pageNumber = 0;
        long verifiedCount = 0;

        while (true) {
            Page<AuditTrail> page = auditTrailRepository.findAll(
                    PageRequest.of(pageNumber, pageSize, Sort.by("auditId").ascending())
            );

            for (AuditTrail row : page.getContent()) {
                if (!Objects.equals(row.getPrevHash(), expectedPrevHash)) {
                    result.put("valid", false);
                    result.put("brokenAtAuditId", row.getAuditId());
                    result.put("reason", "Broken prev_hash link. Expected: " + expectedPrevHash + ", Found: " + row.getPrevHash());
                    result.put("verifiedBlocks", verifiedCount);
                    return result;
                }

                Long userId = (row.getUser() != null) ? row.getUser().getUserId() : 0L;
                String computedHash = computeRowHash(
                        row.getPrevHash(),
                        userId,
                        row.getAction(),
                        row.getEntityType(),
                        row.getEntityId(),
                        row.getTimestamp(),
                        row.getOldValue(),
                        row.getNewValue()
                );

                if (!Objects.equals(row.getRowHash(), computedHash)) {
                    result.put("valid", false);
                    result.put("brokenAtAuditId", row.getAuditId());
                    result.put("reason", "Row data tampering detected. Stored hash: " + row.getRowHash() + ", Computed: " + computedHash);
                    result.put("verifiedBlocks", verifiedCount);
                    return result;
                }

                expectedPrevHash = row.getRowHash();
                verifiedCount++;
            }

            if (!page.hasNext()) {
                break;
            }
            pageNumber++;
        }

        result.put("valid", true);
        result.put("chainLength", verifiedCount);
        result.put("genesisHash", GENESIS_HASH);
        result.put("headHash", expectedPrevHash);
        result.put("verifiedAt", LocalDateTime.now().toString());
        result.put("message", "Cryptographic audit trail integrity verified. All " + verifiedCount + " blocks are unbroken and untampered.");
        return result;
    }

    private void validateEntityType(String entityType) {
        Set<String> allowed = Set.of(
                "MATERIAL", "MATERIAL_GROUP", "MATERIAL_MAPPING", "USER",
                "EXPORT", "JOB", "CATEGORY", "GROUP_RELATION"
        );
        if (entityType == null || !allowed.contains(entityType.toUpperCase())) {
            throw new IllegalArgumentException("Invalid audit entity_type: " + entityType);
        }
    }

    /**
     * D5: Canonical hash payload wire format:
     * prev_hash | user_id | action | entity_type | entity_id | timestamp | old_value | new_value
     */
    public String computeRowHash(String prevHash, Long userId, String action, String entityType,
                                 Long entityId, LocalDateTime timestamp, String oldValue, String newValue) {
        try {
            String payload = String.format("%s|%d|%s|%s|%d|%s|%s|%s",
                    prevHash != null ? prevHash : GENESIS_HASH,
                    userId != null ? userId : 0L,
                    action != null ? action : "",
                    entityType != null ? entityType : "",
                    entityId != null ? entityId : 0L,
                    timestamp != null ? timestamp.toString() : "",
                    oldValue != null ? oldValue : "",
                    newValue != null ? newValue : ""
            );
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(payload.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString().toUpperCase();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
