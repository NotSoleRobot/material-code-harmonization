package com.sih.materialmaster.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Full Reviewer Queue item carrying AI explanation evidence (BUG-11, NFR1, W4.5).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MappingReviewResponse {
    private Long mappingId;
    private String status; // PENDING | CONFIRMED | REJECTED | SUPERSEDED
    private BigDecimal confidenceScore;
    private String confidenceTier; // HIGH | MEDIUM | LOW

    private Long materialId;
    private String materialDescription;
    private String materialSpecification;
    private String unitOfMeasure;
    private String cpseMaterialCode;
    private String cpseName;

    private Long groupId;
    private String commonMaterialCode;
    private String provisionalRef;
    private String standardizedDescription;
    private String standardizedSpecification;
    private String standardizedUom;
    private String categoryName;

    private String explanationJson;
    private ExplanationDto explanation;

    private String reviewedByName;
    private Long reviewedByUserId;
    private Long supersedesMappingId;
    private Long supersededByMappingId;
    private LocalDateTime reviewedAt;
    private String decisionNotes;
    private LocalDateTime createdAt;
}
