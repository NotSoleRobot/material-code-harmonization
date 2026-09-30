package com.sih.materialmaster.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Entity
@Table(name = "matching_policy")
@Getter
@Setter
public class MatchingPolicy {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "policy_id")
    private Long policyId;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id", nullable = false, unique = true)
    private MaterialCategory category;

    @Column(name = "auto_confirm_threshold", nullable = false, precision = 5, scale = 4)
    private BigDecimal autoConfirmThreshold = new BigDecimal("0.9000");

    @Column(name = "review_threshold", nullable = false, precision = 5, scale = 4)
    private BigDecimal reviewThreshold = new BigDecimal("0.7000");

    @Column(name = "minimum_candidate_margin", nullable = false, precision = 5, scale = 4)
    private BigDecimal minimumCandidateMargin = new BigDecimal("0.1000");

    @Column(name = "auto_confirm_enabled", nullable = false)
    private boolean autoConfirmEnabled = true;

    @Column(name = "required_attributes", columnDefinition = "TEXT")
    private String requiredAttributes;

    @Column(name = "policy_version", nullable = false, length = 40)
    private String policyVersion = "1.0";
}
