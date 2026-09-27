package com.sih.materialmaster.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CodeValidationDto {
    private String code;
    private boolean valid;
    private String checkCharacter;
    private String segment;
    private String family;
    private String commodityClass;
    private String serial;
    private String message;
}
