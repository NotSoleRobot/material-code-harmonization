package com.sih.materialmaster.controller;

import com.sih.materialmaster.entity.ProcurementAssumption;
import com.sih.materialmaster.entity.User;
import com.sih.materialmaster.repository.ProcurementAssumptionRepository;
import com.sih.materialmaster.repository.UserRepository;
import com.sih.materialmaster.security.UserPrincipal;
import com.sih.materialmaster.service.AnalyticsService;
import com.sih.materialmaster.service.AuditService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/analytics")
public class AnalyticsController {

    private final AnalyticsService analyticsService;
    private final ProcurementAssumptionRepository assumptionRepository;
    private final UserRepository userRepository;
    private final AuditService auditService;

    public AnalyticsController(AnalyticsService analyticsService,
                               ProcurementAssumptionRepository assumptionRepository,
                               UserRepository userRepository,
                               AuditService auditService) {
        this.analyticsService = analyticsService;
        this.assumptionRepository = assumptionRepository;
        this.userRepository = userRepository;
        this.auditService = auditService;
    }

    /**
     * W6.2: View configurable procurement assumptions.
     */
    @GetMapping("/assumptions")
    public List<ProcurementAssumption> getAssumptions() {
        return assumptionRepository.findAll();
    }

    /**
     * W6.2: Update procurement assumption (Admin only).
     */
    @PutMapping("/assumptions/{key}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ProcurementAssumption> updateAssumption(
            @PathVariable String key,
            @RequestBody Map<String, Object> body,
            @AuthenticationPrincipal UserPrincipal adminPrincipal) {

        ProcurementAssumption assumption = assumptionRepository.findByKey(key)
                .orElseThrow(() -> new IllegalArgumentException("No assumption found with key: " + key));

        BigDecimal oldValue = assumption.getValue();
        if (body.containsKey("value")) {
            BigDecimal newValue = new BigDecimal(body.get("value").toString());
            assumption.setValue(newValue);
        }
        if (body.containsKey("description")) {
            assumption.setDescription(body.get("description").toString());
        }

        User admin = adminPrincipal != null ? userRepository.findById(adminPrincipal.getUserId()).orElse(null) : null;
        assumption.setUpdatedBy(admin);
        ProcurementAssumption saved = assumptionRepository.save(assumption);

        auditService.logEvent(admin, "ASSUMPTION_UPDATED", "PROCUREMENT_ASSUMPTION", saved.getAssumptionId(),
                "value: " + oldValue, "value: " + saved.getValue());

        return ResponseEntity.ok(saved);
    }

    /**
     * W6.3: Rate contract candidates (Cross-CPSE Demand Aggregation for GeM).
     */
    @GetMapping("/rate-contract-candidates")
    public List<Map<String, Object>> getRateContractCandidates() {
        return analyticsService.getRateContractCandidates();
    }

    /**
     * W6.4: Price variance report across CPSEs.
     */
    @GetMapping("/price-variance")
    public List<Map<String, Object>> getPriceVariance() {
        return analyticsService.getPriceVarianceReport();
    }
}
