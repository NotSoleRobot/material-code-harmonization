package com.sih.materialmaster.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;
import java.util.Map;

/**
 * WP1: Result from Flask /extract-attributes for a single material.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class AttributeExtractionResult {
    private Long materialId;
    private String category;
    private Map<String, Object> attributes;
    private boolean identityCriticalPresent;
    private List<String> missingIdentityKeys;

    public Map<String, Object> getExtractedAttributes() {
        return attributes;
    }
}
