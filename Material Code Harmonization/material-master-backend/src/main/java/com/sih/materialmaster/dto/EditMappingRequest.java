package com.sih.materialmaster.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class EditMappingRequest {
    private String standardizedDescription;
    private String standardizedSpecification;
    private String standardizedUom;
    private String notes;
}
