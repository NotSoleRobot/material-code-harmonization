package com.sih.materialmaster.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PublishableGroupDto {
    private Long groupId;
    private String provisionalRef;
    private String categoryName;
    private String standardizedDescription;
    private String standardizedSpecification;
    private String standardizedUom;
    private long confirmedMappingCount;
    private long distinctCpseCount;
    private List<MemberMaterialDto> members;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MemberMaterialDto {
        private Long materialId;
        private String cpseName;
        private String cpseMaterialCode;
        private String description;
        private String confirmedBy;
        private String reviewedAt;
    }
}
