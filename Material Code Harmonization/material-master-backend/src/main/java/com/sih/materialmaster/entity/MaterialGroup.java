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

    // SHA-256 hash of canonical normalized identity attributes (WP1)
    // NULL when identity-critical attributes are incomplete — no blind merge.
    @Column(name = "attribute_signature", unique = false, nullable = true, length = 64)
    private String attributeSignature;

    // WP1: true when ALL identity-critical keys for this category were present
    // at signature computation time. Only complete signatures participate in dedup.
    @Column(name = "signature_complete", nullable = false)
    private Boolean signatureComplete = false;

    @Column(name = "signature_attributes", columnDefinition = "TEXT")
    private String signatureAttributes;

    @Column(name = "signature_version", nullable = false)
    private Integer signatureVersion = 1;

    // Human-readable provisional reference (e.g. PROV-2026-000001) shown before reviewer approval
    @Column(name = "provisional_ref", unique = true, nullable = false, length = 50)
    private String provisionalRef;

    @Column(name = "code_serial")
    private Long codeSerial;

    // Minted national code (NUMM-SS-FF-CC-NNNNNN-K) - NULL until human reviewer sign-off (fixes BUG-03)
    @Column(name = "common_material_code", unique = true, length = 50)
    private String commonMaterialCode;

    @Column(name = "standardized_description", nullable = false, columnDefinition = "TEXT")
    private String standardizedDescription;

    @Column(name = "standardized_specification", columnDefinition = "TEXT")
    private String standardizedSpecification;

    @Column(name = "standardized_uom", length = 30)
    private String standardizedUom;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id")
    private MaterialCategory category;

    // PROPOSED | ACTIVE | SUPERSEDED | DEPRECATED
    @Column(name = "status", nullable = false, length = 30)
    private String status = "PROPOSED";

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "superseded_by_group_id")
    private MaterialGroup supersededByGroup;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        if (this.createdAt == null) {
            this.createdAt = LocalDateTime.now();
        }
        if (this.updatedAt == null) {
            this.updatedAt = LocalDateTime.now();
        }
        if (this.status == null) {
            this.status = "PROPOSED";
        }
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
