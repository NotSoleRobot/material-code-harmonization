package com.sih.materialmaster.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class NationalCodeDetailsDto {
    private String commonMaterialCode;
    private String provisionalRef;
    private String status; // PROPOSED | ACTIVE | SUPERSEDED | DEPRECATED
    private String standardizedDescription;
    private String standardizedSpecification;
    private String standardizedUom;
    private String categoryName;
    private String categoryPath; // e.g. "Distribution (40) > Fluid Distribution (40-14) > PIPES (40-14-07)"
    private String codeSegment;
    private String codeFamily;
    private String codeClass;
    private String attributeSignature;
    private Map<String, Object> signatureAttributes;
    private int distinctCpseCount;
    private List<MemberMaterialDto> members;
    private List<RelatedGroupDto> relatedGroups;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MemberMaterialDto {
        private Long materialId;
        private String cpseName;
        private String cpseMaterialCode;
        private String rawDescription;
        private String rawSpecification;
        private String unitOfMeasure;
        private String mappingStatus;
        private Double confidenceScore;
        private String confidenceTier;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RelatedGroupDto {
        private Long groupId;
        private String commonMaterialCode;
        private String provisionalRef;
        private String relationType; // FUNCTIONALLY_EQUIVALENT | VARIANT
        private String description;
        private Double confidence;
    }
}
