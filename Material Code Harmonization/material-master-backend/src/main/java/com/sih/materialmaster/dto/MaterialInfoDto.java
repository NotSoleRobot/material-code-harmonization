package com.sih.materialmaster.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class MaterialInfoDto {
    @JsonProperty("material_id")
    private Long materialId;

    private String description;
    private String category;
    private String specification;

    @JsonProperty("cpse_material_code")
    private String cpseMaterialCode;

    @JsonProperty("cpse_name")
    private String cpseName;

    @JsonProperty("extracted_attributes")
    private Map<String, Object> extractedAttributes;
}
