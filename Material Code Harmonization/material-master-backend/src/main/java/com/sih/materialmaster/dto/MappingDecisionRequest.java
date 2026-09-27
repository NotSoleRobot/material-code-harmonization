package com.sih.materialmaster.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Body for POST /api/mappings/{id}/approve and /reject.
 * Identity is authenticated via JWT SecurityContext (W1.1 #5).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MappingDecisionRequest {
    private String notes;
}