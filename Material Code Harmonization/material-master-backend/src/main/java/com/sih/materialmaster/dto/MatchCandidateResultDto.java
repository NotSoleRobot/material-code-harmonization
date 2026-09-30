package com.sih.materialmaster.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;
import java.util.List;

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
    private Map<String, Object> features;
    @JsonProperty("score_breakdown")
    private Map<String, Double> scoreBreakdown;
    @JsonProperty("critical_conflicts")
    private List<String> criticalConflicts;
    @JsonProperty("model_version")
    private String modelVersion;
    @JsonProperty("second_best_score")
    private Double secondBestScore;
    @JsonProperty("candidate_margin")
    private Double candidateMargin;
    @JsonProperty("recommended_route")
    private String recommendedRoute;
    private Map<String, Object> candidate;
}
