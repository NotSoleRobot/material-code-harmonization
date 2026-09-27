package com.sih.materialmaster.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ExplanationDto {
    private List<String> checks = new ArrayList<>();
    private List<String> warnings = new ArrayList<>();
    private List<String> conflicts = new ArrayList<>();
}
