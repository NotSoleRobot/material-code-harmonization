package com.sih.materialmaster.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

/**
 * Shape of an incoming "create material" request (FR1).
 * Deliberately narrow: only fields a CPSE Operator actually provides.
 * System-managed fields (materialId, createdAt, extracted_* attributes)
 * are never accepted from the client - they're set internally.
 */
@Getter
@Setter
public class MaterialCreateRequest {

    @NotNull(message = "cpseId is required")
    private Long cpseId;

    @NotBlank(message = "cpseMaterialCode is required")
    private String cpseMaterialCode;

    @NotBlank(message = "description is required")
    private String description;

    private String specification;

    private String unitOfMeasure;

    private java.math.BigDecimal nominalPrice;

    // Optional - a CPSE Operator may not know the category yet;
    // AI-assigned categorization can happen later (FR3).
    private Long categoryId;
}