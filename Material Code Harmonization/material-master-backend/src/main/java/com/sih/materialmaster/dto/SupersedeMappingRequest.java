package com.sih.materialmaster.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SupersedeMappingRequest {

    @NotBlank(message = "New decision is required (CONFIRMED or REJECTED)")
    private String newDecision;

    @NotBlank(message = "Mandatory justification is required to supersede a decided mapping")
    private String reason;
}
