package com.sih.materialmaster.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CompareResponse {
    @JsonProperty("predicted_relationship")
    private String predictedRelationship;
    private double confidence;
    @JsonProperty("match_probability")
    private double matchProbability;
    @JsonProperty("label_probability")
    private double labelProbability;
    @JsonProperty("confidence_tier")
    private String confidenceTier;
    @JsonProperty("class_probabilities")
    private Map<String, Double> classProbabilities;
    private ExplanationDto explanation;
    private Map<String, Object> features;
    @JsonProperty("score_breakdown")
    private Map<String, Double> scoreBreakdown;
    @JsonProperty("critical_conflicts")
    private List<String> criticalConflicts;
    @JsonProperty("model_version")
    private String modelVersion;
    private String note;
}
