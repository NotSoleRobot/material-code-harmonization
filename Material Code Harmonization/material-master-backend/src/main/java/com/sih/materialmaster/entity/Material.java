package com.sih.materialmaster.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

@Entity
@Table(name = "material", uniqueConstraints = {
        @UniqueConstraint(name = "uq_cpse_material_code", columnNames = {"cpse_id", "cpse_material_code"})
})
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

    @Column(name = "unit_of_measure", length = 30)
    private String unitOfMeasure;

    @Column(name = "nominal_price", precision = 14, scale = 2)
    private BigDecimal nominalPrice;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id")
    private MaterialCategory category;

    @Column(name = "extracted_dimension", length = 50)
    private String extractedDimension;

    @Column(name = "extracted_grade", length = 50)
    private String extractedGrade;

    @Column(name = "extracted_standard_code", length = 50)
    private String extractedStandardCode;

    @Column(name = "extracted_material_type", length = 50)
    private String extractedMaterialType;

    // WP1: Full JSONB extracted attributes from Flask /extract-attributes
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "extracted_attributes", columnDefinition = "jsonb")
    private Map<String, Object> extractedAttributes;

    // WP1: Timestamp of when attributes were last extracted
    @Column(name = "attributes_extracted_at")
    private LocalDateTime attributesExtractedAt;

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
