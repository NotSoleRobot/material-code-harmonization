package com.sih.materialmaster.controller;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * WP5 Task 6 / D5: Tamper demonstration endpoint.
 * ONLY registered under the 'demo' profile. Mutates an audit record to demonstrate
 * cryptographic chain failure in presentation and testing.
 */
@RestController
@RequestMapping("/api/demo")
@Profile("demo")
public class DemoTamperController {

    @PersistenceContext
    private EntityManager entityManager;

    @PostMapping("/tamper-audit/{auditId}")
    @Transactional
    public ResponseEntity<Map<String, Object>> tamperAuditRow(
            @PathVariable Long auditId,
            @RequestParam(defaultValue = "TAMPERED_PAYLOAD_UNAUTHORIZED_MUTATION") String fakeValue) {

        // PostgreSQL DDL is transactional: if mutation fails, trigger disabling rolls back too.
        entityManager.createNativeQuery("ALTER TABLE audit_trail DISABLE TRIGGER trg_audit_no_update").executeUpdate();
        int rows = entityManager.createNativeQuery(
                "UPDATE audit_trail SET old_value = :fake WHERE audit_id = :id")
                .setParameter("fake", fakeValue)
                .setParameter("id", auditId)
                .executeUpdate();
        entityManager.createNativeQuery("ALTER TABLE audit_trail ENABLE TRIGGER trg_audit_no_update").executeUpdate();

        return ResponseEntity.ok(Map.of(
                "status", "TAMPERED",
                "auditId", auditId,
                "rowsAffected", rows,
                "message", "Audit record #" + auditId + " mutated for demonstration. Integrity verification will now report failure."
        ));
    }
}
