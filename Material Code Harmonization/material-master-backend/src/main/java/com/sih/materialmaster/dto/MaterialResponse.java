package com.sih.materialmaster.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * Shape of a material returned to the caller. Kept separate from the
 * Material entity so lazy-loaded relationships (cpse, category) never
 * leak into the JSON response by accident.
 */
@Getter
@AllArgsConstructor
public class MaterialResponse {
    private Long materialId;
    private Long cpseId;
    private String cpseName;
    private String cpseMaterialCode;
    private String description;
    private String specification;
    private String unitOfMeasure;
    private java.math.BigDecimal nominalPrice;
    private Long categoryId;
    private LocalDateTime createdAt;
}