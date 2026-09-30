package com.sih.materialmaster.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "match_candidate")
@Getter
@Setter
public class MatchCandidate {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "candidate_id")
    private Long candidateId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "material_id", nullable = false)
    private Material material;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "candidate_material_id", nullable = false)
    private Material candidateMaterial;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "candidate_group_id")
    private MaterialGroup candidateGroup;

    @Column(name = "candidate_rank", nullable = false)
    private Integer candidateRank;

    @Column(name = "predicted_relationship", length = 40)
    private String predictedRelationship;

    @Column(name = "match_score", nullable = false, precision = 5, scale = 4)
    private BigDecimal matchScore;

    @Column(name = "score_breakdown", columnDefinition = "TEXT")
    private String scoreBreakdown;

    @Column(name = "critical_conflicts", columnDefinition = "TEXT")
    private String criticalConflicts;

    @Column(name = "model_version", length = 80)
    private String modelVersion;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}
