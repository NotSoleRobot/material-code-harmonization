package com.sih.materialmaster.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

/**
 * WP1: Schema returned by Flask GET /schema/{category}.
 * Identifies which attribute keys are identity-critical (must match for dedup)
 * versus variant-critical (different variant, not same item).
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class CategorySchemaDto {
    private String category;

    @JsonProperty("identity_critical")
    private List<String> identityCritical;

    @JsonProperty("variant_critical")
    private List<String> variantCritical;

    @JsonProperty("all_fields")
    private List<String> allFields;

    public List<String> getIdentityCriticalAttributes() {
        return identityCritical != null ? identityCritical : List.of();
    }
}
