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

    // PENDING | CONFIRMED | REJECTED - per-mapping, separate from
    // MaterialGroup.status, so one bad link can be rejected without
    // invalidating the whole group (FR8).
    @Column(name = "status", nullable = false, length = 20)
    private String status = "PENDING";

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reviewed_by")
    private User reviewedBy;

    @Column(name = "reviewed_at")
    private LocalDateTime reviewedAt;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }

    // IMPORTANT: the rule "only one active (PENDING/CONFIRMED) mapping per
    // material, but unlimited REJECTED history" is enforced by a PARTIAL
    // UNIQUE INDEX at the DB level:
    //   CREATE UNIQUE INDEX uq_active_mapping_per_material
    //   ON material_mapping (material_id) WHERE status != 'REJECTED';
    // JPA/Hibernate cannot express a conditional (WHERE-clause) unique index
    // through annotations - this MUST be added via schema.sql, run after
    // Hibernate creates the base table. Flagging here so it isn't forgotten
    // when we get to that file.
}
