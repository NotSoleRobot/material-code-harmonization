package com.sih.materialmaster.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "group_relation", uniqueConstraints = {
        @UniqueConstraint(name = "uq_group_relation", columnNames = {"group_a_id", "group_b_id", "relation_type"})
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class GroupRelation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "relation_id")
    private Long relationId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "group_a_id", nullable = false)
    private MaterialGroup groupA;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "group_b_id", nullable = false)
    private MaterialGroup groupB;

    // FUNCTIONALLY_EQUIVALENT | VARIANT
    @Column(name = "relation_type", nullable = false, length = 30)
    private String relationType;

    @Column(name = "confidence", precision = 5, scale = 4)
    private BigDecimal confidence;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "approved_by")
    private User approvedBy;

    @Column(name = "approved_at")
    private LocalDateTime approvedAt;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (this.createdAt == null) {
            this.createdAt = LocalDateTime.now();
        }
    }
}
