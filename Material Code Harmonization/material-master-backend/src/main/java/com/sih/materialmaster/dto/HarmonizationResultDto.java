package com.sih.materialmaster.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class HarmonizationResultDto {
    private Long materialId;
    private String materialDescription;
    private String cpseName;
    private String category;
    private List<MatchCandidateResultDto> matches;
    private Long mappingId;
    private Long groupId;
    private String proposedGroupCode;
    private Double confidenceScore;
    private String confidenceTier;
    private String routingDecision;
    private String status;
    private ExplanationDto explanation;
}
