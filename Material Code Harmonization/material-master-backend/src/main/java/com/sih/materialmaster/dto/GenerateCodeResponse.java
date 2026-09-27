package com.sih.materialmaster.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class GenerateCodeResponse {
    @JsonProperty("proposed_code")
    private String proposedCode;
    private String note;
}
