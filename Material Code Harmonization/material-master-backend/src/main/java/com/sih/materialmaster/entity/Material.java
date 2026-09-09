package com.sih.materialmaster.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "material")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class Material {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "material_id")
    private Long materialId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cpse_id", nullable = false)
    private Cpse cpse;

    @Column(name = "cpse_material_code", nullable = false, length = 100)
    private String cpseMaterialCode;

    @Column(name = "description", nullable = false, columnDefinition = "TEXT")
    private String description;

    @Column(name = "specification", columnDefinition = "TEXT")
    private String specification;

    @Column(name = "unit_of_measure", length = 20)
    private String unitOfMeasure;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id")
    private MaterialCategory category;

    // Attribute-aware matching fields (Innovation #1) - populated by the
    // Python matching service via regex/rule extraction. Nullable: NULL means
    // "nothing extracted," which matching logic treats as skip-this-attribute,
    // not as a mismatch.
    @Column(name = "extracted_dimension", length = 50)
    private String extractedDimension;

    @Column(name = "extracted_grade", length = 50)
    private String extractedGrade;

    @Column(name = "extracted_standard_code", length = 50)
    private String extractedStandardCode;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
