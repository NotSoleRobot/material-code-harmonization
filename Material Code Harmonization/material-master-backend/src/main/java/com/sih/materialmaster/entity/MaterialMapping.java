package com.sih.materialmaster.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "material_mapping")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class MaterialMapping {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "mapping_id")
    private Long mappingId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "material_id", nullable = false)
    private Material material;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "group_id", nullable = false)
    private MaterialGroup group;

    // Feeds NFR1 (explainability) and Innovation #2 (confidence-tiering).
    @Column(name = "confidence_score", nullable = false, precision = 5, scale = 4)
    private BigDecimal confidenceScore;

    // HIGH | MEDIUM | LOW (Innovation #2)
    @Column(name = "confidence_tier", nullable = false, length = 10)
    private String confidenceTier = "MEDIUM";

    // PENDING | CONFIRMED | REJECTED | SUPERSEDED
    @Column(name = "status", nullable = false, length = 30)
    private String status = "PENDING";

    // Structured attribute comparison checks, warnings, and conflicts
    @Column(name = "explanation_json", columnDefinition = "TEXT")
    private String explanationJson;

    // WP1: How this mapping was determined.
    // DETERMINISTIC_SIGNATURE | ML_PROPOSED | NOVEL
    @Column(name = "match_basis", length = 30)
    private String matchBasis;

    // AUTO | HUMAN | LEGACY. Publication remains a separate governance decision.
    @Column(name = "decision_source", nullable = false, length = 20)
    private String decisionSource = "LEGACY";

    // AUTO_CONFIRM | REVIEW_REQUIRED | NOVEL
    @Column(name = "routing_decision", nullable = false, length = 30)
    private String routingDecision = "REVIEW_REQUIRED";

    @Column(name = "model_version", length = 80)
    private String modelVersion;

    @Column(name = "second_best_score", precision = 5, scale = 4)
    private BigDecimal secondBestScore;

    @Column(name = "candidate_margin", precision = 5, scale = 4)
    private BigDecimal candidateMargin;

    @Column(name = "score_breakdown", columnDefinition = "TEXT")
    private String scoreBreakdown;

    @Column(name = "critical_conflicts", columnDefinition = "TEXT")
    private String criticalConflicts;

    @Column(name = "automatically_decided_at")
    private LocalDateTime automaticallyDecidedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reviewed_by")
    private User reviewedBy;

    @Column(name = "reviewed_at")
    private LocalDateTime reviewedAt;

    @Column(name = "decision_notes", columnDefinition = "TEXT")
    private String decisionNotes;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "supersedes_mapping_id")
    private MaterialMapping supersedesMapping;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "superseded_by_mapping_id")
    private MaterialMapping supersededByMapping;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (this.createdAt == null) {
            this.createdAt = LocalDateTime.now();
        }
        if (this.status == null) {
            this.status = "PENDING";
        }
        if (this.confidenceTier == null) {
            this.confidenceTier = "MEDIUM";
        }
        if (this.decisionSource == null) this.decisionSource = "LEGACY";
        if (this.routingDecision == null) this.routingDecision = "REVIEW_REQUIRED";
    }
}
