package com.sih.materialmaster.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class MaterialExportDto {
    private Long materialId;
    private String cpseName;
    private String cpseMaterialCode;
    private String description;
    private String specification;
    private String unitOfMeasure;
    private String nationalMaterialCode;
    private String standardizedDescription;
    private String mappingStatus;
    private BigDecimal confidenceScore;
}
