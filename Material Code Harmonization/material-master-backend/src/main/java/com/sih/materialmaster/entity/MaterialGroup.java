package com.sih.materialmaster.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "material_group")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class MaterialGroup {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "group_id")
    private Long groupId;

    @Column(name = "common_material_code", unique = true, nullable = false, length = 50)
    private String commonMaterialCode;

    @Column(name = "standardized_description", nullable = false, columnDefinition = "TEXT")
    private String standardizedDescription;

    @Column(name = "standardized_specification", columnDefinition = "TEXT")
    private String standardizedSpecification;

    @Column(name = "standardized_uom", length = 20)
    private String standardizedUom;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id")
    private MaterialCategory category;

    // PENDING_REVIEW | APPROVED | REJECTED — implements FR8 at the group level.
    @Column(name = "status", nullable = false, length = 20)
    private String status = "PENDING_REVIEW";

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
