package com.sih.materialmaster.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

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
    private String note;
}
