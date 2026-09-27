package com.sih.materialmaster.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class MatchCandidateResultDto {
    @JsonProperty("predicted_relationship")
    private String predictedRelationship;
    private double confidence;
    @JsonProperty("match_probability")
    private double matchProbability;
    @JsonProperty("label_probability")
    private double labelProbability;
    @JsonProperty("confidence_tier")
    private String confidenceTier;
    private ExplanationDto explanation;
    private Map<String, Object> candidate;
}
